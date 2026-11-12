package com.bendourthe.milestone.sync.webview

import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.testProgram
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.SignInStop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSignInSessionTest {
    private val program = testProgram(
        "chase",
        capability = SyncCapability.Automatic(
            ExtractionSpec.Selector(SelectorStrategy.CSS, ".points", Regex("/dashboard"), Regex("/logon")),
        ),
    ).copy(allowedHosts = setOf("secure.chase.com"), textKeywords = setOf("chase"))

    private val origin = "https://secure.chase.com"
    private var nonces = 0

    private fun session(headless: Boolean = true, plan: AutoSignInPlan = AutoSignInPlan(canFill = true, readTexts = true)) =
        BridgeSession(program, headless, autoSignIn = plan) { "nonce-${++nonces}" }

    private fun BridgeSession.ready() = onMessage("""{"type":"ready"}""", origin, isMainFrame = true)
    private fun BridgeSession.send(type: String, extra: String = "") =
        onMessage("""{"type":"$type","nonce":"nonce-$nonces"$extra}""", origin, isMainFrame = true)

    private fun BridgeAction?.configFlag(name: String) =
        Json.parseToJsonElement((this as BridgeAction.Reply).config).jsonObject[name]!!.jsonPrimitive.content

    private fun BridgeSession.filled(fields: String) = send("filled", ""","submitted":true,"fields":"$fields"""")

    @Test
    fun firstSignInPageIsFilledOnceAndTheFormBackWithAnErrorMeansRefused() {
        val session = session()
        assertEquals("false", session.ready().configFlag("afterSignIn"))
        assertEquals(BridgeAction.FillCredentials, session.send("login"))
        session.filled("username,password")
        assertTrue(session.signInSubmitted)
        assertEquals("true", session.ready().configFlag("afterSignIn"))
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.REFUSED)), session.send("refused"))
    }

    @Test
    fun theFormBackWithoutAnErrorIsUnfinishedSoItMayBeTriedAgain() {
        val session = session()
        session.ready()
        session.send("login")
        session.filled("username,password")
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.UNFINISHED)), session.send("login"))
    }

    @Test
    fun anErrorBeforeThePasswordWasSentIsAnsweredLikeASignInPage() {
        val session = session()
        session.ready()
        assertEquals(BridgeAction.FillCredentials, session.send("refused"))
    }

    @Test
    fun aUsernameOnlyStepIsFollowedByOneFillOfThePasswordPage() {
        val session = session()
        session.ready()
        assertEquals(BridgeAction.FillCredentials, session.send("login"))
        session.filled("username")
        val passwordPage = session.ready()
        assertEquals("the password page may still offer a password route", "true", passwordPage.configFlag("openSignIn"))
        assertEquals("false", passwordPage.configFlag("afterSignIn"))
        assertEquals(BridgeAction.FillCredentials, session.send("login"))
        session.filled("password")
        assertEquals("true", session.ready().configFlag("afterSignIn"))
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.REFUSED)), session.send("refused"))
        assertEquals("username then password", session.filledFields)
    }

    @Test
    fun aSignInThatNeverReachesThePasswordStopsAsUnfinishedNotRefused() {
        val session = session()
        session.ready()
        session.send("login")
        session.filled("username")
        session.ready()
        session.send("login")
        session.filled("username")
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.UNFINISHED)), session.send("login"))
    }

    @Test
    fun theSignInControlIsPressedOnlyBeforeAHiddenSignInWithASavedPassword() {
        val session = session()
        assertEquals("true", session.ready().configFlag("openSignIn"))
        session.send("login")
        session.filled("username,password")
        assertEquals("false", session.ready().configFlag("openSignIn"))
        assertEquals("false", session(headless = false).ready().configFlag("openSignIn"))
        assertEquals("false", session(plan = AutoSignInPlan.NONE).ready().configFlag("openSignIn"))
    }

    @Test
    fun fillCommandCarriesTheCurrentNonceOnly() {
        val session = session()
        session.ready()
        session.send("login")
        val command = Json.parseToJsonElement(session.fillCommand("owner", "secret")!!).jsonObject
        assertEquals("fill", command["command"]!!.jsonPrimitive.content)
        assertEquals("nonce-1", command["nonce"]!!.jsonPrimitive.content)
        assertEquals(null, command["lastName"])
    }

    @Test
    fun aSavedLastNameRidesAlongWithTheFillCommand() {
        val session = session()
        session.ready()
        session.send("login")
        val command = Json.parseToJsonElement(session.fillCommand("owner", "secret", "Doe")!!).jsonObject
        assertEquals("Doe", command["lastName"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutASavedSignInHeadlessStopsAtTheSignInPage() {
        val session = session(plan = AutoSignInPlan.NONE)
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.LoginRequired), session.send("login"))
    }

    @Test
    fun codeAfterSignInIsAwaitedOnceThenStops() {
        val session = session()
        session.ready()
        session.send("login")
        session.ready()
        assertEquals(BridgeAction.AwaitCode, session.send("code"))
        assertTrue(session.codeCommand("123456")!!.contains("123456"))
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.NO_CODE)), session.send("code"))
    }

    @Test
    fun timeoutsWhileWaitingForATextKeepTheSessionOpen() {
        val session = session()
        session.ready()
        session.send("login")
        session.ready()
        session.send("code")
        assertTrue(session.send("timeout") is BridgeAction.Reply)
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.NO_CODE)), session.codeUnavailable())
    }

    @Test
    fun codeIsNotAwaitedWhenTextsAreOff() {
        val session = session(plan = AutoSignInPlan(canFill = true, readTexts = false))
        session.ready()
        session.send("login")
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.NO_CODE)), session.send("code"))
    }

    @Test
    fun formNotFoundStopsHeadlessSignIn() {
        val session = session()
        session.ready()
        session.send("login")
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.NOT_FILLED)), session.send("filled", ""","submitted":false"""))
    }

    @Test
    fun unfinishedSignInTimesOutAsStopped() {
        val session = session()
        session.onNavigated("https://secure.chase.com/logon/verify")
        session.ready()
        session.send("login")
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.UNFINISHED)), session.send("timeout"))
    }

    @Test
    fun aPageAfterThePasswordThatAsksForNothingOpensTheBalancePageOnceThenGetsThreeMorePageTimes() {
        val session = session()
        session.ready()
        session.send("login")
        session.filled("username,password")
        session.onNavigated("https://secure.chase.com/choose-account")
        session.ready()
        assertEquals(BridgeAction.NavigateToDashboard, session.send("timeout"))
        session.ready()
        repeat(3) { assertTrue(session.send("timeout") is BridgeAction.Reply) }
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.UNFINISHED)), session.send("timeout"))
    }

    @Test
    fun aPageAfterTheSignInThatWaitsForInputStopsAtOnce() {
        val session = session()
        session.ready()
        session.send("login")
        session.filled("username,password")
        session.onNavigated("https://secure.chase.com/choose-account")
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.UNFINISHED)), session.send("timeout", ""","awaitingInput":true"""))
    }

    @Test
    fun aSignedOutBalancePageOpensTheSignInPageOnceWhenASavedSignInExists() {
        val session = session()
        session.onNavigated("https://secure.chase.com/dashboard")
        session.ready()
        assertEquals(BridgeAction.NavigateToLogin, session.send("timeout"))
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.Failed(FailureReason.Timeout)), session.send("timeout"))
    }

    @Test
    fun aHiddenSignInStartsAtTheSignInPageOnlyWhenItCanFillAndThePagesDiffer() {
        val saved = AutoSignInPlan(canFill = true, readTexts = false)
        assertEquals(program.loginUrl, BridgeSession(program, headless = true, autoSignIn = saved, startAtLogin = true).firstUrl)
        assertEquals(program.dashboardUrl, BridgeSession(program, headless = true, autoSignIn = saved).firstUrl)
        assertEquals(program.dashboardUrl, BridgeSession(program, headless = true, autoSignIn = AutoSignInPlan.NONE, startAtLogin = true).firstUrl)
        val onePage = program.copy(loginUrl = program.dashboardUrl)
        assertEquals(onePage.dashboardUrl, BridgeSession(onePage, headless = true, autoSignIn = saved, startAtLogin = true).firstUrl)
    }

    @Test
    fun aSignInPageThatShowsNoFormOpensTheBalancePageOnceInsteadOfReturningToIt() {
        val session = BridgeSession(program, headless = true, autoSignIn = AutoSignInPlan(canFill = true, readTexts = true), startAtLogin = true) { "nonce-${++nonces}" }
        session.onNavigated("https://secure.chase.com/logon")
        session.ready()
        assertEquals(BridgeAction.NavigateToDashboard, session.send("timeout"))
        session.onNavigated("https://secure.chase.com/dashboard")
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.Failed(FailureReason.Timeout)), session.send("timeout"))
    }

    @Test
    fun aSignInPageStartedDirectlyIsFilledAsUsual() {
        val session = BridgeSession(program, headless = true, autoSignIn = AutoSignInPlan(canFill = true, readTexts = true), startAtLogin = true) { "nonce-${++nonces}" }
        session.onNavigated("https://secure.chase.com/logon")
        session.ready()
        assertEquals(BridgeAction.FillCredentials, session.send("login"))
    }

    @Test
    fun withoutASavedSignInASignedOutBalancePageTimesOut() {
        val session = session(plan = AutoSignInPlan.NONE)
        session.onNavigated("https://secure.chase.com/dashboard")
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.Failed(FailureReason.Timeout)), session.send("timeout"))
    }

    @Test
    fun aSecondPressIsLoggedAndARefusalAfterItStillStops() {
        val session = session()
        session.ready()
        session.send("login")
        session.filled("username,password")
        assertNull(session.send("pressedAgain"))
        assertEquals(BridgeAction.Emit(BridgeEvent.AutoSignInStopped(SignInStop.REFUSED)), session.send("refused"))
        assertEquals("username,password then pressed again", session.filledFields)
    }

    @Test
    fun interactiveSessionFillsOnceAndLetsTheOwnerContinue() {
        val session = session(headless = false)
        session.ready()
        assertEquals(BridgeAction.FillCredentials, session.send("login"))
        session.filled("username,password")
        session.ready()
        assertEquals(BridgeAction.Emit(BridgeEvent.LoginRequired), session.send("login"))
        assertEquals("false", session.ready().configFlag("afterSignIn"))
        assertNull(session.codeUnavailable())
    }
}
