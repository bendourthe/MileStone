package com.bendourthe.milestone.sync.webview

import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
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
import com.bendourthe.milestone.sync.extract.BenefitsReadBudget
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.BridgeMessage
import com.bendourthe.milestone.sync.extract.BridgeProtocol
import com.bendourthe.milestone.sync.extract.PageDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads a program's cards and their benefits in a hidden WebView that reuses the program's session profile
 * (ADR-0008): the first page lists the cards and reads the card it shows, then each other card is reached by
 * pressing its switcher option. Never signs in: a sign-in form stops the read. Cards not reached before the budget
 * ends are reported as not read, so their stored benefits stay. Logs hosts, counts, and stop reasons only: one line
 * per page from its parsed count-only diagnostics, one per card (elapsed time and pages used), and a run summary.
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
            val run = Run(webView, program, spec, BenefitsReadBudget(SystemClock.elapsedRealtime() + budgetMs - SCRIPT_MARGIN_MS))
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
        private val budget: BenefitsReadBudget,
    ) {
        val done = CompletableDeferred<BenefitsRead>()
        private val profileName = SessionProfiles.profileName(program.programId)
        private val script = webView.context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        private val origins = program.allowedHosts.map { "https://$it" }.toSet()
        /** Null between a source-driven load and the new page's ready message, so nothing from the old page is accepted. */
        private var nonce: String? = null
        private var cards: CardList? = null
        private val read = linkedMapOf<String, List<BenefitDraft>>()
        private val failed = linkedSetOf<String>()
        private var target: String? = null
        private var pages = 0
        private var destroyed = false
        private val startedAt = SystemClock.elapsedRealtime()
        private var cardsDone = 0
        private var pagesAtCardStart = 0
        private var secondPageCutLogged = false

        fun start() {
            WebViewConfig.configure(webView, profileName)
            profiles.register(profileName, webView)
            WebViewCompat.addWebMessageListener(webView, BridgeProtocol.JS_OBJECT_NAME, origins) { view, message, _, isMainFrame, reply ->
                if (!isMainFrame || done.isCompleted) return@addWebMessageListener
                val data = message.data
                if (data == READY) {
                    pages++
                    val fresh = newNonce()
                    nonce = fresh
                    val now = SystemClock.elapsedRealtime()
                    // Decided where a card starts (its Rewards page); a second page's own ready message changes nothing.
                    val secondPage = if (spec.kindOf(view.url) == "rewards") budget.allowsSecondPage(now, cardsAfterCurrent()) else true
                    if (!secondPage && !secondPageCutLogged) {
                        secondPageCutLogged = true
                        DiagnosticLog.i(TAG, "${program.programId} benefits cut: second pages skipped from card index=${cardsDone + 1} left=${budget.timeLeftMs(now)}")
                    }
                    reply.postMessage(
                        BridgeProtocol.benefitsConfig(
                            spec, fresh, budget.pageTimeoutMs(now), listCards = cards == null && read.isEmpty(), targetDigits = target, secondPage = secondPage,
                        ),
                    )
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
                    logPage(message.ready, null)
                    read[message.lastDigits] = message.drafts
                    val mismatched = target != null && message.lastDigits != target
                    if (mismatched) failed += target!!
                    logCard(if (mismatched) "mismatch" else "read")
                    next()
                }
                is BridgeMessage.BenefitsUnreadable -> {
                    DiagnosticLog.i(TAG, "${program.programId} benefits page unreadable (${message.why}) on ${HostPolicy.httpsHost(webView.url.orEmpty())}")
                    logPage(message.ready, message.why)
                    // The first page's card failed: with a card list, the other cards are still read.
                    val current = target
                    if (current != null) failed += current
                    if (current != null || cards != null) logCard("failed")
                    if (current == null && cards == null) finish(null) else next()
                }
                is BridgeMessage.Event -> if (message.event == BridgeEvent.LoginRequired) finish(BenefitsStop.SIGNED_OUT)
                else -> Unit
            }
        }

        /** [why] is the fixed unreadable reason, or null for a read page; neither line names a card or a path. */
        private fun logPage(ready: PageDiagnostics?, why: String?) {
            val facts = ready?.logLine() ?: "diagnostics=none"
            DiagnosticLog.i(TAG, "${program.programId} benefits page: $facts${why?.let { " why=$it" }.orEmpty()}")
        }

        /** One line per card, by position in the read only: [outcome] is read, mismatch, or failed. */
        private fun logCard(outcome: String) {
            cardsDone++
            DiagnosticLog.i(TAG, "${program.programId} benefits card: index=$cardsDone outcome=$outcome elapsed=${elapsedMs()} pages=${pages - pagesAtCardStart}")
            pagesAtCardStart = pages
        }

        private fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAt

        /** Listed cards still waiting after the one being read now (0 before the card list arrives). */
        private fun cardsAfterCurrent(): Int =
            cards?.cards?.count { it.lastDigits !in read && it.lastDigits !in failed && it.lastDigits != target }?.let { if (target == null) it - 1 else it }?.coerceAtLeast(0) ?: 0

        /** Moves to the next listed card not yet read or failed, or finishes; past the deadline's last cut the rest stay unread. */
        private fun next() {
            val waiting = cards?.cards?.map { it.lastDigits }?.filter { it !in read && it !in failed }.orEmpty()
            if (waiting.isEmpty()) return finish(null)
            val now = SystemClock.elapsedRealtime()
            if (!budget.allowsNewCard(now)) {
                DiagnosticLog.i(TAG, "${program.programId} benefits cut: cards unread=${waiting.size} left=${budget.timeLeftMs(now)}")
                return finish(BenefitsStop.BUDGET)
            }
            target = waiting.first()
            nonce = null
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
                "benefits=${read.values.sumOf { it.size }} total=${elapsedMs()}ms " +
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

            /** A crashed renderer ends the read with what was read so far, instead of taking the app down. */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                DiagnosticLog.w(TAG, "Renderer gone during the ${program.programId} benefits read")
                finish(BenefitsStop.BUDGET)
                return true
            }
        }
    }

    companion object {
        private const val TAG = "MileStoneBenefits"
        private const val SCRIPT_ASSET = "extract.js"
        private const val READY = """{"type":"ready"}"""
        private const val SCRIPT_MARGIN_MS = 2_000L

        private val random = java.security.SecureRandom()

        /** A fresh 128-bit nonce per page, so a message from an earlier page or another script is dropped. */
        private fun newNonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
