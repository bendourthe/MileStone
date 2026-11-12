package com.bendourthe.milestone.sync.webview

import android.graphics.Bitmap
import android.net.http.SslError
import com.bendourthe.milestone.DiagnosticLog
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.BridgeProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Drives one unused [webView] for one program, isolated in that program's session profile. The same
 * navigation policy and message listener serve the interactive sign-in and the [headless] refresh.
 */
class LoginBridge(
    val webView: WebView,
    private val program: Program,
    private val profiles: WebViewProfileBackend,
    private val interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    headless: Boolean = false,
    scriptTimeoutMs: Long = BridgeProtocol.DEFAULT_TIMEOUT_MS,
    private val autoSignIn: AutoSignInSupport = AutoSignInSupport.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    startAtLogin: Boolean = false,
) {
    private val session = BridgeSession(program, headless, scriptTimeoutMs, autoSignIn = autoSignIn.plan, startAtLogin = startAtLogin)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signInSubmittedAtMs: Long? = null
    private val profileName = SessionProfiles.profileName(program.programId)
    private val script = webView.context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
    private val _events = Channel<BridgeEvent>(Channel.BUFFERED)
    private val _externalLinks = Channel<String>(Channel.CONFLATED)
    private val _host = MutableStateFlow<String?>(null)
    private val _progress = MutableStateFlow(0)
    private val _canGoBack = MutableStateFlow(false)
    private var destroyed = false
    private var loggedSignInErrors = 0
    private var serverErrorReloads = 0

    val events: Flow<BridgeEvent> = _events.receiveAsFlow()

    /** Off-site https links the user may choose to open in their browser. */
    val externalLinks: Flow<String> = _externalLinks.receiveAsFlow()
    val host: StateFlow<String?> = _host.asStateFlow()
    val progress: StateFlow<Int> = _progress.asStateFlow()
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    fun start() {
        WebViewConfig.configure(webView, profileName)
        profiles.register(profileName, webView)
        if (!session.isSupported ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            _events.trySend(BridgeEvent.Unsupported)
            return
        }
        WebViewCompat.addWebMessageListener(webView, BridgeProtocol.JS_OBJECT_NAME, session.originRules) { _, message, sourceOrigin, isMainFrame, reply ->
            handle(session.onMessage(message.data, sourceOrigin.toString(), isMainFrame), reply)
        }
        WebViewCompat.addDocumentStartJavaScript(webView, script, session.originRules)
        webView.webViewClient = Client()
        webView.webChromeClient = object : WebViewConfig.DenyingChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _progress.value = newProgress
            }
        }
        webView.loadUrl(session.firstUrl)
    }

    /** Opens a copied sign-in link when it belongs to this program; returns false otherwise. */
    fun openPastedLink(text: CharSequence?): Boolean {
        val url = session.pastedLink(text) ?: return false
        webView.loadUrl(url)
        return true
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    /**
     * Writes this program's cookies to disk now. WebView keeps session cookies across app restarts only
     * once they are written, and its own periodic write is lost when the process is killed first.
     */
    private fun flushCookies() {
        val manager = if (WebViewConfig.supportsProfiles) {
            ProfileStore.getInstance().getProfile(profileName)?.cookieManager
        } else {
            CookieManager.getInstance()
        }
        manager?.flush()
    }

    private fun handle(action: BridgeAction?, reply: JavaScriptReplyProxy) {
        when (action) {
            is BridgeAction.Reply -> reply.postMessage(action.config)
            is BridgeAction.Emit -> if (session.headless && action.event is BridgeEvent.Failed) {
                webView.evaluateJavascript(PAGE_SHAPE_JS) { counts ->
                    DiagnosticLog.i(TAG, "${program.programId} page shape (elements,frames,lists,rewardTiles,listItems,inputs,buttons,state,readableFrames): $counts")
                    deliver(action.event)
                }
            } else if (session.headless && action.event is BridgeEvent.AutoSignInStopped) {
                webView.evaluateJavascript(SIGN_IN_SHAPE_JS) { shape ->
                    DiagnosticLog.i(TAG, "${program.programId} sign-in stopped (${action.event.why}); filled ${session.filledFields}; shape (passwords,texts,codes,radios,checkboxes,buttons,textOption,maskedOption,sendCode,saveDevice,appOption): $shape")
                    webView.evaluateJavascript(TEXT_FIELDS_JS) { fields ->
                        DiagnosticLog.i(TAG, "${program.programId} text boxes (type/autocomplete/maxlength/inputmode): $fields")
                        webView.evaluateJavascript(ERROR_MARKERS_JS) { markers ->
                            DiagnosticLog.i(TAG, "${program.programId} error markers (alertShown,alertHidden,liveShown,liveHidden,invalidPasswords,invalidOthers): $markers")
                            deliver(action.event)
                        }
                    }
                }
            } else {
                deliver(action.event)
            }
            BridgeAction.NavigateToDashboard -> webView.loadUrl(program.dashboardUrl)
            BridgeAction.NavigateToLogin -> webView.loadUrl(program.loginUrl)
            BridgeAction.FillCredentials -> scope.launch {
                val saved = autoSignIn.credentials()
                if (saved == null) {
                    handle(session.credentialsUnavailable(), reply)
                } else {
                    session.fillCommand(saved.username, saved.password, saved.lastName)?.let {
                        signInSubmittedAtMs = clock()
                        DiagnosticLog.i(TAG, "${program.programId} automatic sign-in submitted on ${_host.value}")
                        reply.postMessage(it)
                    }
                }
            }
            BridgeAction.AwaitCode -> scope.launch {
                val since = (signInSubmittedAtMs ?: clock()) - CODE_LOOKBACK_MS
                val code = autoSignIn.awaitCode(since)
                if (code == null) {
                    DiagnosticLog.i(TAG, "${program.programId} no verification text arrived")
                    handle(session.codeUnavailable(), reply)
                } else {
                    session.codeCommand(code)?.let {
                        DiagnosticLog.i(TAG, "${program.programId} verification code entered")
                        reply.postMessage(it)
                    }
                }
            }
            null -> Unit
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        scope.cancel()
        flushCookies()
        profiles.unregister(profileName, webView)
        webView.stopLoading()
        webView.destroy()
    }

    private fun deliver(event: BridgeEvent) {
        if (event is BridgeEvent.Extracted) flushCookies()
        if (session.headless && event !is BridgeEvent.Extracted) {
            DiagnosticLog.i(TAG, "${program.programId} headless $event on ${_host.value}, pages=${session.readyCount}, ${session.lastTimeout}")
        } else if (session.headless) {
            DiagnosticLog.i(TAG, "${program.programId} headless balance read on ${_host.value}")
        }
        _events.trySend(event)
    }

    private fun fail(reason: FailureReason) {
        (session.fail(reason) as? BridgeAction.Emit)?.let { deliver(it.event) }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (session.staysInWebView(url)) return false
            val mode = if (session.headless) "headless" else "interactive"
            DiagnosticLog.i(TAG, "${program.programId} $mode navigation blocked to ${HostPolicy.httpsHost(url) ?: request.url.scheme}")
            if (!session.headless && HostPolicy.httpsHost(url) != null) _externalLinks.trySend(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = navigated(view, url)

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = navigated(view, url)

        override fun onPageFinished(view: WebView, url: String) = flushCookies()

        private fun navigated(view: WebView, url: String) {
            session.onNavigated(url)
            _host.value = HostPolicy.httpsHost(url) ?: url.toUri().host
            _canGoBack.value = view.canGoBack()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            DiagnosticLog.w(TAG, "TLS error on ${HostPolicy.httpsHost(error.url)}")
            fail(FailureReason.Tls)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            val url = request.url.toString()
            if (request.isForMainFrame && response.statusCode >= 400 && session.isDashboard(url)) {
                DiagnosticLog.w(TAG, "HTTP ${response.statusCode} on ${HostPolicy.httpsHost(url)}")
                // A bot check answers 429 with a page that verifies the browser and reloads itself (Hyatt), so the
                // page keeps its normal time limit instead of failing at once.
                // A server error on the balance page is often momentary (Hilton's 500s), so the page is loaded once more.
                if (response.statusCode >= HTTP_SERVER_ERROR && serverErrorReloads < MAX_SERVER_ERROR_RELOADS) {
                    serverErrorReloads++
                    view.postDelayed({ if (!destroyed) view.loadUrl(url) }, SERVER_ERROR_RELOAD_DELAY_MS)
                } else if (response.statusCode != HTTP_TOO_MANY_REQUESTS) {
                    fail(FailureReason.Network(response.statusCode))
                }
            } else if (session.signInSubmitted && response.statusCode >= 400 && loggedSignInErrors++ < MAX_SIGN_IN_ERROR_LOGS) {
                // Tells a refused password (401) from a bot check that rejected the submit (403, 429).
                DiagnosticLog.i(TAG, "${program.programId} HTTP ${response.statusCode} ${request.method} on ${HostPolicy.httpsHost(url)} after sign-in")
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) fail(FailureReason.Network(null))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            DiagnosticLog.w(TAG, "Renderer gone for ${program.programId}")
            destroy()
            fail(FailureReason.RendererGone)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            interceptor?.invoke(request)
    }

    private companion object {
        const val TAG = "MileStoneBridge"
        const val SCRIPT_ASSET = "extract.js"
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        const val MAX_SERVER_ERROR_RELOADS = 1
        const val SERVER_ERROR_RELOAD_DELAY_MS = 3_000L
        const val MAX_SIGN_IN_ERROR_LOGS = 8

        /** A text sent just before the form was submitted, such as one the bank sent on the previous page, still counts. */
        const val CODE_LOOKBACK_MS = 60_000L

        /** Counts only, across open shadow roots; never text, values, or URLs. */
        const val PAGE_SHAPE_JS = "(function(){function q(s){var n=0;(function v(r){n+=r.querySelectorAll(s).length;" +
            "var a=r.querySelectorAll('*');for(var i=0;i<a.length;i++)if(a[i].shadowRoot)v(a[i].shadowRoot)})(document);return n}" +
            "return [q('*'),q('iframe'),q('mds-list'),q('[data-testid=\"rewards-multi-navigation-tile-TileListId\"]')," +
            "q('mds-list-item'),q('input'),q('button'),document.readyState," +
            "Array.prototype.filter.call(document.querySelectorAll('iframe'),function(f){try{return !!f.contentDocument}catch(e){return false}}).length" +
            "].join(',')})()"

        /**
         * Visible sign-in controls after a stopped automatic sign-in: counts, and whether any short control label
         * matches each delivery or confirmation pattern. Never text, values, or URLs.
         */
        const val SIGN_IN_SHAPE_JS = "(function(){var all=[];(function v(r){var a=r.querySelectorAll('*');" +
            "for(var i=0;i<a.length;i++){all.push(a[i]);if(a[i].shadowRoot)v(a[i].shadowRoot);" +
            "if(a[i].tagName==='IFRAME'){var d=null;try{d=a[i].contentDocument}catch(e){}if(d)v(d)}}})(document);" +
            "function vis(e){if(!e.getClientRects().length)return false;var s=(e.ownerDocument.defaultView||window).getComputedStyle(e);" +
            "return s.visibility!=='hidden'&&s.display!=='none'}" +
            "function n(sel){return all.filter(function(e){return e.matches&&e.matches(sel)&&!e.disabled&&vis(e)})}" +
            "function lab(e){var t=(e.getAttribute('aria-label')||(e.labels&&e.labels[0]&&e.labels[0].textContent)||e.textContent||'')" +
            ".replace(/\\s+/g,' ').trim();return t.length<=80?t:''}" +
            "var c=n('button,[role=button],[role=radio],input[type=radio],label,a[href]').map(lab);" +
            "function any(re){return c.some(function(t){return re.test(t)})?1:0}" +
            "return [n('[type=password]').length,n('input:not([type]),input[type=text],input[type=tel],input[type=number],input[type=email]').length," +
            "n('[autocomplete=one-time-code]').length,n('input[type=radio],[role=radio]').length,n('input[type=checkbox]').length," +
            "n('button,[role=button],input[type=submit]').length,any(/\\b(text( message)?|sms)\\b/i),any(/([*\\u2022x]{2,}|\\d{4}\\b)/i)," +
            "any(/^(continue|next|send( (the |my )?code)?|get (a )?code|text me( the code)?|send text)$/i)," +
            "any(/(save|trust|remember).{0,12}(device|browser|computer)/i),any(/\\b(app|notification|push)\\b/i)].join(',')})()"

        /** Attribute tokens of the visible text boxes, at most 12; never names, labels, or values. */
        const val TEXT_FIELDS_JS = "(function(){var all=[];(function v(r){var a=r.querySelectorAll('input');" +
            "for(var i=0;i<a.length;i++)all.push(a[i]);var s=r.querySelectorAll('*');for(var j=0;j<s.length;j++){" +
            "if(s[j].shadowRoot)v(s[j].shadowRoot);if(s[j].tagName==='IFRAME'){var d=null;try{d=s[j].contentDocument}catch(e){}if(d)v(d)}}})(document);" +
            "function t(x){return /^[a-z-]{0,20}$/i.test(x||'')?(x||'-'):'?'}" +
            "return all.filter(function(e){return /^(|text|tel|number|email|password)$/.test(e.getAttribute('type')||'')&&!e.disabled&&e.getClientRects().length})" +
            ".slice(0,12).map(function(e){return [t(e.getAttribute('type')),t(e.getAttribute('autocomplete')),e.maxLength,t(e.getAttribute('inputmode'))].join('/')}).join(' ')})()"

        /**
         * Counts of the error markers extract.js weighs at a stop: alerts and assertive live regions with text, split by
         * whether they are on screen or screen-reader only (1 px), and fields marked invalid. Never text or values.
         */
        const val ERROR_MARKERS_JS = "(function(){var all=[];(function v(r){var a=r.querySelectorAll('*');" +
            "for(var i=0;i<a.length;i++){all.push(a[i]);if(a[i].shadowRoot)v(a[i].shadowRoot);" +
            "if(a[i].tagName==='IFRAME'){var d=null;try{d=a[i].contentDocument}catch(e){}if(d)v(d)}}})(document);" +
            "function vis(e){if(!e.getClientRects().length)return false;var s=(e.ownerDocument.defaultView||window).getComputedStyle(e);" +
            "return s.visibility!=='hidden'&&s.display!=='none'}" +
            "function on(e){var b=e.getBoundingClientRect();return b.width>1&&b.height>1}" +
            "function said(sel){return all.filter(function(e){return e.matches(sel)&&vis(e)&&(e.textContent||'').trim().length>0})}" +
            "var al=said('[role=alert]'),li=said('[aria-live=assertive]'),bad=all.filter(function(e){return e.matches('[aria-invalid=true]')&&vis(e)});" +
            "function n(l,f){return l.filter(f).length}" +
            "return [n(al,on),n(al,function(e){return !on(e)}),n(li,on),n(li,function(e){return !on(e)})," +
            "n(bad,function(e){return e.type==='password'}),n(bad,function(e){return e.type!=='password'})].join(',')})()"
    }
}
