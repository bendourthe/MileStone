package com.bendourthe.milestone.sync.webview

import android.webkit.WebView
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.domain.testProgram
import com.bendourthe.milestone.sync.WebViewHost
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.SignInStop
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessWebViewBalanceSourceTest {
    private val program = testProgram(
        "hilton",
        capability = SyncCapability.Automatic(ExtractionSpec.Selector(SelectorStrategy.CSS, ".points", Regex("/account"), Regex("/login"))),
    ).copy(allowedHosts = setOf("www.hilton.com"), identityProviderHosts = setOf("login.hilton-id.com"))
    private val origin = "https://www.hilton.com"
    private var nonces = 0

    private fun session() = BridgeSession(program, headless = true, scriptTimeoutMs = 28_000) { "n${++nonces}" }

    private fun BridgeSession.ready() = onMessage("""{"type":"ready"}""", origin, isMainFrame = true)
    private fun BridgeSession.send(json: String) = onMessage(json, origin, isMainFrame = true)
    private fun BridgeSession.timeoutAt(url: String): BridgeAction? {
        onNavigated(url)
        return send("""{"type":"timeout","nonce":"n$nonces"}""")
    }

    @Test
    fun manualOnlyProgramIsUnsupportedWithoutLoading() = runTest {
        var hostUsed = false
        val host = object : WebViewHost {
            override suspend fun <T> withAttachedWebView(block: suspend (WebView) -> T): T {
                hostUsed = true
                error("must not load")
            }
        }
        assertEquals(SyncResult.Unsupported, HeadlessWebViewBalanceSource(WebViewProfileBackend()).fetch(testProgram("manual"), host, 30_000, signIn = true))
        assertFalse(hostUsed)
    }

    @Test
    fun eventsMapToSyncResults() {
        assertEquals(SyncResult.Success(142_500), HeadlessWebViewBalanceSource.toSyncResult(BridgeEvent.Extracted(142_500)))
        assertEquals(SyncResult.NeedsReconnect, HeadlessWebViewBalanceSource.toSyncResult(BridgeEvent.LoginRequired))
        assertEquals(SyncResult.Failure(FailureReason.Tls), HeadlessWebViewBalanceSource.toSyncResult(BridgeEvent.Failed(FailureReason.Tls)))
        assertEquals(SyncResult.Unsupported, HeadlessWebViewBalanceSource.toSyncResult(BridgeEvent.Unsupported))
        val stops = mapOf(
            SignInStop.REFUSED to FailureReason.PasswordRefused,
            SignInStop.NO_CODE to FailureReason.CodeMissing,
            SignInStop.NOT_FILLED to FailureReason.SignInUnfinished,
            SignInStop.UNFINISHED to FailureReason.SignInUnfinished,
        )
        stops.forEach { (why, reason) ->
            assertEquals(SyncResult.Failure(reason), HeadlessWebViewBalanceSource.toSyncResult(BridgeEvent.AutoSignInStopped(why)))
        }
    }

    @Test
    fun scriptTimeoutLeavesTwoSecondsOfBudget() {
        assertEquals(28_000L, HeadlessWebViewBalanceSource.scriptTimeout(30_000))
        assertEquals(1_000L, HeadlessWebViewBalanceSource.scriptTimeout(500))
        assertTrue((session().ready() as BridgeAction.Reply).config.contains("\"timeoutMs\":28000"))
    }

    @Test
    fun passwordFieldEndsTheSessionAsLoginRequired() {
        val s = session()
        s.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.LoginRequired), s.send("""{"type":"login","nonce":"n1"}"""))
        assertTrue(s.finished)
    }

    @Test
    fun timeoutOutcomesDependOnPageState() {
        assertEquals(BridgeAction.Emit(BridgeEvent.LoginRequired), session().apply { ready() }.timeoutAt("https://login.hilton-id.com/authorize"))
        assertEquals(BridgeAction.Emit(BridgeEvent.LoginRequired), session().apply { ready() }.timeoutAt("https://www.hilton.com/login"))
        assertEquals(BridgeAction.Emit(BridgeEvent.Failed(FailureReason.Timeout)), session().apply { ready() }.timeoutAt("https://www.hilton.com/account"))
        assertEquals(BridgeAction.Emit(BridgeEvent.Failed(FailureReason.NeedsAttention)), session().apply { ready() }.timeoutAt("https://www.hilton.com/promo"))
    }

    @Test
    fun extractedBalanceAndPageErrorsEndTheSession() {
        val s = session()
        s.ready()
        s.onNavigated("https://www.hilton.com/account")
        assertEquals(BridgeAction.Emit(BridgeEvent.Extracted(82_410)), s.send("""{"type":"balance","raw":"82,410","matchCount":1,"nonce":"n1"}"""))
        assertEquals(BridgeAction.Emit(BridgeEvent.Failed(FailureReason.RendererGone)), session().fail(FailureReason.RendererGone))
    }
}
