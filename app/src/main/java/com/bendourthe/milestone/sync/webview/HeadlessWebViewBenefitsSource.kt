package com.bendourthe.milestone.sync.webview

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.bendourthe.milestone.DiagnosticLog
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitsSpec
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.sync.BenefitsRead
import com.bendourthe.milestone.sync.BenefitsSource
import com.bendourthe.milestone.sync.BenefitsStop
import com.bendourthe.milestone.sync.WebViewHost
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.BridgeMessage
import com.bendourthe.milestone.sync.extract.BridgeProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads a program's cards and their benefits in a hidden WebView that reuses the program's session profile
 * (ADR-0008): the first page lists the cards and reads the card it shows, then each other card is reached by
 * pressing its switcher option. Never signs in: a sign-in form stops the read. Cards not reached before the budget
 * ends are reported as not read, so their stored benefits stay. Logs hosts, counts, and stop reasons only.
 */
class HeadlessWebViewBenefitsSource(
    private val profiles: WebViewProfileBackend,
    private val interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) : BenefitsSource {
    override suspend fun read(program: Program, host: WebViewHost, budgetMs: Long): BenefitsRead {
        val spec = program.benefits ?: return BenefitsRead(null, emptyMap(), stop = BenefitsStop.UNSUPPORTED)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return BenefitsRead(null, emptyMap(), stop = BenefitsStop.UNSUPPORTED)
        }
        return host.withAttachedWebView { webView ->
            val run = Run(webView, program, spec, minOf(budgetMs, PAGE_BUDGET_MS) - SCRIPT_MARGIN_MS)
            try {
                run.start()
                withTimeoutOrNull((budgetMs - SCRIPT_MARGIN_MS).coerceAtLeast(1_000)) { run.done.await() } ?: run.result(BenefitsStop.BUDGET)
            } finally {
                run.destroy()
                DiagnosticLog.i(TAG, "${program.programId} benefits read: ${run.summary()}")
            }
        }
    }

    /** One read; every method runs on the main thread. */
    private inner class Run(
        private val webView: WebView,
        private val program: Program,
        private val spec: BenefitsSpec,
        private val pageTimeoutMs: Long,
    ) {
        val done = CompletableDeferred<BenefitsRead>()
        private val profileName = SessionProfiles.profileName(program.programId)
        private val script = webView.context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        private val origins = program.allowedHosts.map { "https://$it" }.toSet()
        private var nonce = newNonce()
        private var cards: CardList? = null
        private val read = linkedMapOf<String, List<BenefitDraft>>()
        private val failed = linkedSetOf<String>()
        private var target: String? = null
        private var pages = 0
        private var destroyed = false

        fun start() {
            WebViewConfig.configure(webView, profileName)
            profiles.register(profileName, webView)
            WebViewCompat.addWebMessageListener(webView, BridgeProtocol.JS_OBJECT_NAME, origins) { view, message, _, isMainFrame, reply ->
                if (!isMainFrame || done.isCompleted) return@addWebMessageListener
                val data = message.data
                if (data == READY) {
                    pages++
                    nonce = newNonce()
                    reply.postMessage(BridgeProtocol.benefitsConfig(spec, nonce, pageTimeoutMs, listCards = cards == null && read.isEmpty(), targetDigits = target))
                    return@addWebMessageListener
                }
                onMessage(BridgeProtocol.parse(data, nonce, pageUrl = view.url, benefits = spec))
            }
            WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
            webView.webViewClient = Client()
            webView.webChromeClient = WebViewConfig.DenyingChromeClient()
            webView.loadUrl(spec.entryUrl)
        }

        private fun onMessage(message: BridgeMessage?) {
            when (message) {
                is BridgeMessage.Cards -> cards = message.list
                is BridgeMessage.Benefits -> {
                    read[message.lastDigits] = message.drafts
                    if (target != null && message.lastDigits != target) failed += target!!
                    next()
                }
                is BridgeMessage.BenefitsUnreadable -> {
                    DiagnosticLog.i(TAG, "${program.programId} benefits page unreadable (${message.why}) on ${HostPolicy.httpsHost(webView.url.orEmpty())}")
                    val current = target
                    if (current == null) finish(null) else {
                        failed += current
                        next()
                    }
                }
                is BridgeMessage.Event -> if (message.event == BridgeEvent.LoginRequired) finish(BenefitsStop.SIGNED_OUT)
                else -> Unit
            }
        }

        /** Moves to the next listed card not yet read or failed, or finishes. */
        private fun next() {
            val remaining = cards?.cards?.map { it.lastDigits }?.firstOrNull { it !in read && it !in failed }
            if (remaining == null) return finish(null)
            target = remaining
            webView.loadUrl(spec.entryUrl)
        }

        private fun finish(stop: BenefitsStop?) {
            if (!done.isCompleted) done.complete(result(stop))
        }

        fun result(stop: BenefitsStop?): BenefitsRead {
            val listed = cards?.cards?.map { it.lastDigits }.orEmpty()
            val unread = if (stop == null) emptySet() else listed.filterNot { it in read }.toSet()
            return BenefitsRead(cards, read.toMap(), failed + unread, stop)
        }

        fun summary(): String =
            "pages=$pages cards=${cards?.cards?.size ?: "none"} complete=${cards?.complete} read=${read.size} failed=${failed.size} " +
                "benefits=${read.values.sumOf { it.size }} " +
                "stop=${if (done.isCompleted) done.getCompleted().stop ?: "none" else "budget"}"

        fun destroy() {
            if (destroyed) return
            destroyed = true
            profiles.unregister(profileName, webView)
            webView.stopLoading()
        }

        private inner class Client : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (HostPolicy.isAllowed(url, program.allowedHosts)) return false
                DiagnosticLog.i(TAG, "${program.programId} benefits navigation blocked to ${HostPolicy.httpsHost(url) ?: request.url.scheme}")
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    DiagnosticLog.i(TAG, "${program.programId} benefits page load failed on ${HostPolicy.httpsHost(request.url.toString())}")
                    onMessage(BridgeMessage.BenefitsUnreadable("timeout"))
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptor?.invoke(request)
        }
    }

    companion object {
        private const val TAG = "MileStoneBenefits"
        private const val SCRIPT_ASSET = "extract.js"
        private const val READY = """{"type":"ready"}"""
        private const val SCRIPT_MARGIN_MS = 2_000L

        /** How long one page may take to show its trackers. */
        const val PAGE_BUDGET_MS = 30_000L

        private val random = java.security.SecureRandom()

        /** A fresh 128-bit nonce per page, so a message from an earlier page or another script is dropped. */
        private fun newNonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
