package com.bendourthe.milestone.sync.webview

import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.testProgram
import com.bendourthe.milestone.sync.extract.BridgeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSessionTest {
    private val program = testProgram(
        "hilton",
        capability = SyncCapability.Automatic(
            ExtractionSpec.Selector(SelectorStrategy.CSS, ".points", Regex("/account"), Regex("/login|/verify")),
        ),
    ).copy(allowedHosts = setOf("www.hilton.com"), identityProviderHosts = setOf("login.hilton-id.com"))

    private val origin = "https://www.hilton.com"
    private var nonces = 0
    private val session = BridgeSession(program) { "nonce-${++nonces}" }

    private fun ready() = session.onMessage("""{"type":"ready"}""", origin, isMainFrame = true)
    private fun send(json: String) = session.onMessage(json, origin, isMainFrame = true)
    private fun balance(raw: String, nonce: String = "nonce-$nonces") = send("""{"type":"balance","raw":"$raw","matchCount":1,"nonce":"$nonce"}""")
    private fun timeout() = send("""{"type":"timeout","nonce":"nonce-$nonces"}""")

    @Test
    fun originRulesAreExactHttpsHosts() {
        assertEquals(setOf("https://www.hilton.com"), session.originRules)
    }

    @Test
    fun readyGetsAFreshNonceAndOldNoncesAreIgnored() {
        assertTrue((ready() as BridgeAction.Reply).config.contains("nonce-1"))
        assertTrue((ready() as BridgeAction.Reply).config.contains("nonce-2"))
        assertNull(balance("82,410", nonce = "nonce-1"))
        assertEquals(BridgeAction.Emit(BridgeEvent.Extracted(82_410)), balance("82,410"))
    }

    @Test
    fun subframesAndForeignOriginsAreIgnored() {
        assertNull(session.onMessage("""{"type":"ready"}""", origin, isMainFrame = false))
        assertNull(session.onMessage("""{"type":"ready"}""", "https://evil.example", isMainFrame = true))
        assertNull(session.onMessage("""{"type":"ready"}""", "http://www.hilton.com", isMainFrame = true))
    }

    @Test
    fun secondExtractionIsIgnored() {
        ready()
        session.onNavigated("https://www.hilton.com/account")
        assertEquals(BridgeAction.Emit(BridgeEvent.Extracted(1)), balance("1"))
        assertTrue(session.finished)
        ready()
        assertNull(balance("2"))
        assertNull(session.fail(FailureReason.Tls))
    }

    @Test
    fun unparseableBalanceFails() {
        ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.Failed(FailureReason.Unparseable)), balance("1,234.56"))
    }

    @Test
    fun timeoutWhileOwnerIsLoggingInKeepsWaiting() {
        ready()
        session.onNavigated("https://www.hilton.com/verify/push")
        assertRearmed(timeout())
        session.onNavigated("https://login.hilton-id.com/authorize")
        assertRearmed(timeout())
        session.onNavigated("https://www.hilton.com/somewhere")
        assertEquals(BridgeAction.Emit(BridgeEvent.LoginRequired), send("""{"type":"login","nonce":"nonce-$nonces"}"""))
        assertRearmed(timeout())
        assertFalse(session.finished)
    }

    @Test
    fun timeoutOnOtherPageNavigatesToDashboardOnceThenKeepsWatching() {
        ready()
        session.onNavigated("https://www.hilton.com/welcome")
        assertEquals(BridgeAction.NavigateToDashboard, timeout())
        ready()
        session.onNavigated("https://www.hilton.com/welcome")
        assertRearmed(timeout())
    }

    @Test
    fun interactiveTimeoutOnDashboardKeepsWatchingUntilTheBalanceAppears() {
        ready()
        session.onNavigated("https://www.hilton.com/account")
        assertRearmed(timeout())
        assertRearmed(timeout())
        assertFalse(session.finished)
        assertEquals(BridgeAction.Emit(BridgeEvent.Extracted(82_410)), balance("82,410"))
    }

    private fun assertRearmed(action: BridgeAction?) {
        assertTrue((action as BridgeAction.Reply).config.contains("nonce-$nonces"))
    }

    @Test
    fun timeoutWhilePageAwaitsTypedCodeRearmsInsteadOfClosingOrRedirecting() {
        ready()
        listOf("https://www.hilton.com/account", "https://www.hilton.com/welcome").forEach { url ->
            session.onNavigated(url)
            val rearm = send("""{"type":"timeout","awaitingInput":true,"nonce":"nonce-$nonces"}""")
            assertTrue((rearm as BridgeAction.Reply).config.contains("nonce-$nonces"))
        }
        assertFalse(session.finished)
        assertEquals(BridgeAction.Emit(BridgeEvent.Extracted(82_410)), balance("82,410"))
    }

    @Test
    fun headlessTimeoutIgnoresAwaitingInput() {
        val headless = BridgeSession(program, headless = true) { "h" }
        headless.onMessage("""{"type":"ready"}""", origin, isMainFrame = true)
        headless.onNavigated("https://www.hilton.com/account")
        assertEquals(
            BridgeAction.Emit(BridgeEvent.Failed(FailureReason.Timeout)),
            headless.onMessage("""{"type":"timeout","awaitingInput":true,"nonce":"h"}""", origin, isMainFrame = true),
        )
    }

    @Test
    fun navigationStaysOnlyOnProgramAndIdentityHosts() {
        assertTrue(session.staysInWebView("https://www.hilton.com/account"))
        assertTrue(session.staysInWebView("https://login.hilton-id.com/authorize"))
        assertFalse(session.staysInWebView("https://www.hilton.com.evil.io/"))
        assertFalse(session.staysInWebView("http://www.hilton.com/"))
        assertFalse(session.staysInWebView("intent://scan#Intent;end"))
    }

    @Test
    fun pastedLinkOpensOnlyProgramHttpsLinksInInteractiveSessions() {
        val link = "https://www.hilton.com/signin?token=abc"
        assertEquals(link, session.pastedLink("  $link\n"))
        assertNull(session.pastedLink("http://www.hilton.com/signin"))
        assertNull(session.pastedLink("https://www.hilton.com.evil.example/signin"))
        assertNull(session.pastedLink("member 1234"))
        assertNull(session.pastedLink(null))
        assertNull(BridgeSession(program, headless = true).pastedLink(link))
        ready()
        balance("12,345")
        assertNull(session.pastedLink(link))
    }

    @Test
    fun dashboardDetection() {
        assertTrue(session.isDashboard("https://www.hilton.com/account"))
        assertFalse(session.isDashboard("https://www.hilton.com/login"))
    }
}
