package com.bendourthe.milestone.sync.webview

import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.bendourthe.milestone.DiagnosticLog
import com.bendourthe.milestone.data.session.SessionStore
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitsAdapter
import com.bendourthe.milestone.domain.BenefitsSpec
import com.bendourthe.milestone.domain.Connection
import com.bendourthe.milestone.domain.ConnectionInventory
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SourceCard
import com.bendourthe.milestone.sync.BenefitsRead
import com.bendourthe.milestone.sync.BenefitsSource
import com.bendourthe.milestone.sync.BenefitsStop
import com.bendourthe.milestone.sync.WebViewHost
import com.bendourthe.milestone.sync.extract.BindingHasher
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.BridgeMessage
import com.bendourthe.milestone.sync.extract.BridgeProtocol
import com.bendourthe.milestone.sync.extract.ChaseCardOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads Chase cards and benefits (ADR-0009) through every signed-in connection, one at a time, within one shared
 * budget. Each connection uses its own profile; the page follows the owner's path to Benefits, lists the card-selector
 * sheet, then reads each card it has not already read through an earlier connection. Never signs in: a sign-in form
 * stops that connection only. Raw card control ids stay in memory for the read and are stored only as binding hashes.
 * Logs hosts, counts, and fixed outcomes only.
 */
class ChaseBenefitsSource(
    private val profiles: WebViewProfileBackend,
    private val sessions: SessionStore,
    private val connections: suspend (Program) -> List<Connection>,
    private val hasher: () -> BindingHasher,
    private val interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) : BenefitsSource {
    override suspend fun read(program: Program, host: WebViewHost, budgetMs: Long): BenefitsRead {
        val spec = program.benefits?.takeIf { it.adapter == BenefitsAdapter.CHASE }
            ?: return BenefitsRead(null, emptyMap(), stop = BenefitsStop.UNSUPPORTED)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return BenefitsRead(null, emptyMap(), stop = BenefitsStop.UNSUPPORTED)
        }
        val deadline = SystemClock.elapsedRealtime() + budgetMs - SCRIPT_MARGIN_MS
        val keys = hasher()
        val inventories = mutableListOf<ConnectionInventory>()
        val read = linkedMapOf<String, List<BenefitDraft>>()
        var stop: BenefitsStop? = null
        for (connection in connections(program)) {
            if (sessions.get(connection.connectionId) == null) continue
            val left = deadline - SystemClock.elapsedRealtime()
            if (left < MIN_CONNECTION_MS) {
                stop = BenefitsStop.BUDGET
                break
            }
            val result = host.withAttachedWebView { webView ->
                val run = Run(webView, program, spec, connection, keys, read.keys.toSet(), SystemClock.elapsedRealtime() + left)
                try {
                    run.start()
                    withTimeoutOrNull(left.coerceAtLeast(1_000)) { run.done.await() } ?: run.result(BenefitsStop.BUDGET)
                } finally {
                    run.destroy()
                    DiagnosticLog.i(TAG, "${program.programId} benefits connection=${connection.sortOrder} read: ${run.summary()}")
                }
            }
            result.inventory?.let(inventories::add)
            read.putAll(result.benefits)
            // A stopped sign-in (page limit, renderer loss, or its own time) leaves later sign-ins to the shared
            // deadline, which the loop checks before starting each one.
            if (result.stop == BenefitsStop.BUDGET) stop = BenefitsStop.BUDGET
            if (result.stop == BenefitsStop.SIGNED_OUT) stop = stop ?: BenefitsStop.SIGNED_OUT
        }
        return BenefitsRead(null, emptyMap(), stop = stop, inventories = inventories, bindingBenefits = read)
    }

    /** What one connection's read produced. */
    private class ConnectionResult(val inventory: ConnectionInventory?, val benefits: Map<String, List<BenefitDraft>>, val stop: BenefitsStop?)

    /** One connection's read; every method runs on the main thread. */
    private inner class Run(
        private val webView: WebView,
        private val program: Program,
        private val spec: BenefitsSpec,
        private val connection: Connection,
        private val keys: BindingHasher,
        private val alreadyRead: Set<String>,
        private val deadline: Long,
    ) {
        val done = CompletableDeferred<ConnectionResult>()
        private val profileName = ConnectionSessions.profileName(connection.connectionId)
        private val script = webView.context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        private val origins = program.allowedHosts.map { "https://$it" }.toSet()
        private var nonce: String? = null
        private var reply: JavaScriptReplyProxy? = null
        private var cards: List<ChaseCardOption>? = null
        private var complete = false
        private var target: String? = null
        private val benefits = linkedMapOf<String, List<BenefitDraft>>()
        private val failed = linkedSetOf<String>()
        private var pages = 0
        private var unreadable: String? = null
        private val startedAt = SystemClock.elapsedRealtime()
        private var destroyed = false

        fun start() {
            WebViewConfig.configure(webView, profileName)
            profiles.register(profileName, webView)
            WebViewCompat.addWebMessageListener(webView, BridgeProtocol.JS_OBJECT_NAME, origins) { view, message, _, isMainFrame, proxy ->
                if (!isMainFrame || done.isCompleted) return@addWebMessageListener
                if (message.data == READY) {
                    pages++
                    // A page that keeps reloading stops this connection. The limit grows with the listed cards, since a
                    // site may reload the page for each card; after the list, stopping leaves the rest unread (BUDGET).
                    if (pages > BASE_PAGES + PAGES_PER_CARD * (cards?.size ?: 0)) {
                        unreadable = "navigation"
                        return@addWebMessageListener finish(if (cards == null) null else BenefitsStop.BUDGET)
                    }
                    reply = proxy
                    sendConfig()
                    return@addWebMessageListener
                }
                onMessage(BridgeProtocol.parse(message.data, nonce, pageUrl = view.url, benefits = spec))
            }
            WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
            webView.webViewClient = Client()
            webView.webChromeClient = WebViewConfig.DenyingChromeClient()
            webView.loadUrl(spec.entryUrl)
        }

        private fun sendConfig() {
            val fresh = newNonce()
            nonce = fresh
            val timeout = (deadline - SystemClock.elapsedRealtime()).coerceIn(1_000, PAGE_TIMEOUT_MS)
            reply?.postMessage(BridgeProtocol.chaseBenefitsConfig(spec, fresh, timeout, listCards = cards == null, targetControl = target))
        }

        private fun onMessage(message: BridgeMessage?) {
            when (message) {
                is BridgeMessage.ChaseCards -> {
                    cards = message.cards
                    complete = message.complete
                    // The page goes on to read the card it shows; when an earlier connection already read that card,
                    // move straight to the next card instead (a new config replaces the page's current run).
                    val shown = message.cards.firstOrNull { it.selected }
                    if (shown != null && keys.hash(program.programId, shown.control) in alreadyRead) next()
                }
                is BridgeMessage.ChaseBenefits -> {
                    val known = cards?.any { it.control == message.control } == true
                    val expected = target == null || message.control == target
                    if (known && expected) benefits[keys.hash(program.programId, message.control)] = message.drafts
                    else target?.let(failed::add)
                    DiagnosticLog.i(TAG, "${program.programId} benefits card: outcome=${if (known && expected) "read" else "mismatch"} rows=${message.rows} trackers=${message.trackers}")
                    next()
                }
                is BridgeMessage.ChaseUnreadable -> {
                    DiagnosticLog.i(TAG, "${program.programId} benefits page unreadable (${message.why}${message.step?.let { " at $it" }.orEmpty()})")
                    if (cards == null) {
                        unreadable = message.why
                        return finish(null)
                    }
                    target?.let(failed::add)
                    next()
                }
                is BridgeMessage.Event -> if (message.event == BridgeEvent.LoginRequired) finish(BenefitsStop.SIGNED_OUT)
                else -> Unit
            }
        }

        /** The next card this connection lists and nobody has read yet, or finish; past the budget the rest stay unread. */
        private fun next() {
            val waiting = cards.orEmpty().filter { option ->
                val hash = keys.hash(program.programId, option.control)
                hash !in benefits && hash !in alreadyRead && option.control !in failed
            }
            if (waiting.isEmpty()) return finish(null)
            if (deadline - SystemClock.elapsedRealtime() < MIN_CARD_MS) return finish(BenefitsStop.BUDGET)
            target = waiting.first().control
            sendConfig()
        }

        private fun finish(stop: BenefitsStop?) {
            if (!done.isCompleted) done.complete(result(stop))
        }

        fun result(stop: BenefitsStop?): ConnectionResult {
            val listed = cards
            val inventory = listed?.let { options ->
                ConnectionInventory(
                    program.programId,
                    connection.connectionId,
                    options.map { SourceCard(keys.hash(program.programId, it.control), it.name, it.lastDigits) },
                    complete = complete && stop == null,
                )
            }
            return ConnectionResult(inventory, benefits.toMap(), stop)
        }

        fun summary(): String =
            "pages=$pages cards=${cards?.size ?: "none"} complete=$complete read=${benefits.size} failed=${failed.size} " +
                "unreadable=${unreadable ?: "none"} total=${SystemClock.elapsedRealtime() - startedAt}ms " +
                "stop=${if (done.isCompleted) done.getCompleted().stop ?: "none" else "budget"}"

        fun destroy() {
            if (destroyed) return
            destroyed = true
            reply = null
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
                if (request.isForMainFrame) onMessage(BridgeMessage.ChaseUnreadable("timeout", null))
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptor?.invoke(request)

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
        private const val PAGE_TIMEOUT_MS = 30_000L
        /** A connection or a card is started only with at least this much budget left. */
        private const val MIN_CONNECTION_MS = 20_000L
        private const val MIN_CARD_MS = 8_000L
        /** Page loads (ready messages) one connection may use before its card list: dashboard, card, Benefits, plus slack. */
        private const val BASE_PAGES = 8

        /** Extra page loads allowed per listed card; the shared deadline still bounds the whole read. */
        private const val PAGES_PER_CARD = 2

        private val random = java.security.SecureRandom()

        private fun newNonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
