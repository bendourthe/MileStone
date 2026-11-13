package com.bendourthe.milestone.sync

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.session.TinkSessionStore
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.BridgeProtocol
import com.bendourthe.milestone.sync.extract.SignInStop
import com.bendourthe.milestone.data.credentials.Credentials
import com.bendourthe.milestone.sync.webview.ActivityWebViewHost
import com.bendourthe.milestone.sync.webview.AutoSignInPlan
import com.bendourthe.milestone.sync.webview.AutoSignInSupport
import com.bendourthe.milestone.sync.webview.LoginBridge
import com.bendourthe.milestone.sync.webview.WebViewProfileBackend
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Drives the real bridge and extraction script against synthetic pages served on an allowlisted host. */
@RunWith(AndroidJUnit4::class)
class BridgeFixtureTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val testAssets = InstrumentationRegistry.getInstrumentation().context
    private val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(testAssets)).build()
    private val db = Room.inMemoryDatabaseBuilder(app, MileStoneDatabase::class.java).build()
    private val accounts = RoomAccountRepository(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionFile = File(app.cacheDir, "bridge-fixture-sessions.pb").apply { delete() }
    private val sessions = TinkSessionStore(sessionFile, PassThroughAead, scope) {}
    private val noCatalog = object : CatalogRepository {
        override val programs = emptyList<Program>()
        override fun program(programId: String): Program? = null
        override fun search(query: String) = emptyList<Program>()
    }
    private val syncManager = SyncManager(accounts, sessions, noCatalog, { _, _, _, _ -> SyncResult.Unsupported }, scope, host = { null })
    private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
    private var bridge: LoginBridge? = null

    @After
    fun tearDown() {
        scenario.onActivity { bridge?.destroy() }
        scenario.close()
        db.close()
        scope.cancel()
        sessionFile.delete()
    }

    private fun fixtureProgram(page: String, dashboard: String = "dashboard.html", reveal: String? = null, textKeywords: Set<String> = emptySet()) = Program(
        programId = "fixture",
        displayName = "Fixture Rewards",
        category = ProgramCategory.HOTEL,
        monogram = "FR",
        logoKey = null,
        isPopular = false,
        brandColorArgb = 0xFF336699,
        unitLabel = "pts",
        loginUrl = "https://$HOST/assets/fixtures/$page",
        dashboardUrl = "https://$HOST/assets/fixtures/$dashboard",
        allowedHosts = setOf(HOST),
        identityProviderHosts = emptySet(),
        syncCapability = SyncCapability.Automatic(
            ExtractionSpec.Selector(SelectorStrategy.CSS, ".points-balance", loggedInUrlPattern = Regex("/fixtures/"), loginUrlPattern = Regex("/signin"), revealSelector = reveal),
        ),
        textKeywords = textKeywords,
    )

    private fun open(
        page: String,
        headless: Boolean = false,
        autoSignIn: AutoSignInSupport = AutoSignInSupport.NONE,
        serve: (WebResourceRequest) -> WebResourceResponse? = { loader.shouldInterceptRequest(it.url) },
        login: String = page,
        scriptTimeoutMs: Long = BridgeProtocol.DEFAULT_TIMEOUT_MS,
        reveal: String? = null,
        textKeywords: Set<String> = emptySet(),
    ): LoginBridge {
        lateinit var created: LoginBridge
        scenario.onActivity { activity ->
            created = LoginBridge(
                WebView(activity), fixtureProgram(login, dashboard = if (headless) page else "dashboard.html", reveal, textKeywords), WebViewProfileBackend(), serve,
                headless = headless, scriptTimeoutMs = scriptTimeoutMs, autoSignIn = autoSignIn,
            )
            activity.setContentView(created.webView)
            created.start()
        }
        bridge = created
        return created
    }

    private fun firstEvent(page: String): BridgeEvent = runBlocking { withTimeout(15_000) { open(page).events.first() } }

    @Test
    fun dashboardFixtureCreatesConnectedAccount() = runBlocking {
        val event = firstEvent("dashboard.html")
        assertEquals(BridgeEvent.Extracted(142_500), event)
        assertEquals(SyncResult.Success(142_500), syncManager.applyInteractive("fixture", SyncResult.Success((event as BridgeEvent.Extracted).balance)))
        val account = accounts.observeAccounts().first().single()
        assertEquals(142_500L, account.balance)
        assertEquals(AccountStatus.CONNECTED, account.status)
    }

    @Test
    fun forgedMessageFromSubframeIsIgnored() = runBlocking {
        val event = withTimeoutOrNull(6_000) { open("forged.html").events.first() }
        assertNull(event)
    }

    @Test
    fun twoMatchesAreAmbiguous() {
        assertEquals(BridgeEvent.Failed(FailureReason.SelectorAmbiguous), firstEvent("ambiguous.html"))
    }

    @Test
    fun passwordFieldAtDashboardUrlRequiresLogin() {
        assertEquals(BridgeEvent.LoginRequired, firstEvent("login-at-dashboard.html"))
    }

    @Test
    fun aMomentaryServerErrorOnTheBalancePageIsLoadedOnceMore() = runBlocking {
        var balancePageLoads = 0
        val event = withTimeout(20_000) {
            open("dashboard.html", headless = true, serve = { request ->
                if (request.isForMainFrame && request.url.path.orEmpty().endsWith("/dashboard.html") && balancePageLoads++ == 0) {
                    WebResourceResponse("text/html", "utf-8", 500, "Internal Server Error", emptyMap(), "".byteInputStream())
                } else {
                    loader.shouldInterceptRequest(request.url)
                }
            }).events.first()
        }
        assertEquals(BridgeEvent.Extracted(142_500), event)
        assertEquals(2, balancePageLoads)
    }

    @Test
    fun aZeroShownWhileTheAccountLoadsIsNotReadAsTheBalance() = runBlocking {
        val event = withTimeout(20_000) { open("zero-then-balance.html", headless = true).events.first() }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun anEmailFirstSignInGetsTheEmailThenThePasswordAndReadsTheBalance() = runBlocking {
        val saved = AutoSignInSupport(
            AutoSignInPlan(canFill = true, readTexts = false),
            credentials = { Credentials("owner@example.test", "fixture-password", "") },
            awaitCode = { null },
        )
        val event = withTimeout(30_000) { open("identity-first.html", headless = true, autoSignIn = saved).events.first() }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun anEmailedLinkSignInTakesThePasswordRouteAndFillsEmailPasswordAndLastName() = runBlocking {
        val saved = AutoSignInSupport(
            AutoSignInPlan(canFill = true, readTexts = false),
            credentials = { Credentials("owner@example.test", "fixture-password", "Fixture") },
            awaitCode = { null },
        )
        val event = withTimeout(30_000) { open("password-route.html", headless = true, autoSignIn = saved).events.first() }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun aSubmitTheSiteNeverAnsweredIsPressedOnceMoreAndSignsIn() = runBlocking {
        val event = withTimeout(40_000) { open("ignored-first-press.html", headless = true, autoSignIn = saved()).events.first() }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun aPasswordRefusedWithAnErrorStopsWithoutPressingAgain() = runBlocking {
        val event = withTimeout(40_000) { open("refused-with-error.html", headless = true, autoSignIn = saved()).events.first() }
        assertEquals(BridgeEvent.AutoSignInStopped(SignInStop.REFUSED), event)
    }

    @Test
    fun aVisiblePasswordRefusalIsKeptWhenThePageDeadlineWins() = runBlocking {
        // The fixture refuses synchronously; its 15 s refusal settle outlasts this page's deadline.
        val event = withTimeout(20_000) {
            open(
                "refused-with-error.html",
                headless = true,
                autoSignIn = saved(),
                scriptTimeoutMs = 10_000,
            ).events.first()
        }
        assertEquals(BridgeEvent.AutoSignInStopped(SignInStop.REFUSED), event)
    }

    @Test
    fun aStandingNoticeAndAScreenReaderAnnouncementAreNotARefusedPassword() = runBlocking {
        val event = withTimeout(45_000) { open("standing-notice-signin.html", headless = true, autoSignIn = saved()).events.first() }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun aRefusalShownByChangingAStandingNoticeStillStopsAsRefused() = runBlocking {
        val event = withTimeout(40_000) { open("refused-in-standing-notice.html", headless = true, autoSignIn = saved()).events.first() }
        assertEquals(BridgeEvent.AutoSignInStopped(SignInStop.REFUSED), event)
    }

    @Test
    fun aUsernameMarkedInvalidWithoutAMessageIsNotARefusedPassword() = runBlocking {
        val event = withTimeout(45_000) { open("invalid-username-no-message.html", headless = true, autoSignIn = saved()).events.first() }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun loadingWordsInTheBalanceElementAreWaitedOutInsteadOfReadAsTheBalance() = runBlocking {
        val event = withTimeout(20_000) { open("loading-text-then-balance.html", headless = true).events.first() }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun aSignedOutHomePageOpensTheSignInPageAndReadsTheBalance() = runBlocking {
        val event = withTimeout(40_000) {
            open("signed-out-home.html", headless = true, autoSignIn = saved(), login = "identity-first.html", scriptTimeoutMs = 15_000).events.first()
        }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun aPersonalOrBusinessChoiceAfterSignInPicksPersonal() = runBlocking {
        val event = withTimeout(30_000) { open("account-choice.html", headless = true, autoSignIn = saved()).events.first() }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun aBalanceShownOnlyInAnAccountPanelIsReadAfterPressingItsButton() = runBlocking {
        val event = withTimeout(20_000) {
            open("panel-balance.html", headless = true, reveal = "[data-testid='account-drawer']").events.first()
        }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun theHiddenWebViewBehindTheAppKeepsDrawingSoAnimationFramesRun() = runBlocking {
        lateinit var activity: ComponentActivity
        scenario.onActivity { activity = it }
        val event = withTimeout(20_000) {
            ActivityWebViewHost(activity).withAttachedWebView { webView ->
                val hidden = LoginBridge(webView, fixtureProgram("after-frames.html", dashboard = "after-frames.html"), WebViewProfileBackend(), { loader.shouldInterceptRequest(it.url) }, headless = true)
                try {
                    hidden.start()
                    withTimeoutOrNull(15_000) { hidden.events.first() }
                } finally {
                    hidden.destroy()
                }
            }
        }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun aTextedCodeBoxNamedOnlyByItsLabelGetsTheCodeAndSignsIn() = runBlocking {
        val texts = AutoSignInSupport(
            AutoSignInPlan(canFill = true, readTexts = true),
            credentials = { Credentials("12345678", "fixture-password", "") },
            awaitCode = { "123456" },
        )
        val event = withTimeout(30_000) {
            open("code-by-label.html", headless = true, autoSignIn = texts, textKeywords = setOf("fixture")).events.first()
        }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    @Test
    fun aTextedCodeSplitIntoOneBoxPerDigitIsTypedDigitByDigit() = runBlocking {
        val texts = AutoSignInSupport(
            AutoSignInPlan(canFill = true, readTexts = true),
            credentials = { Credentials("12345678", "fixture-password", "") },
            awaitCode = { "123456" },
        )
        val event = withTimeout(30_000) {
            open("code-by-digits.html", headless = true, autoSignIn = texts, textKeywords = setOf("fixture")).events.first()
        }
        assertEquals(BridgeEvent.Extracted(9_876), event)
    }

    private fun saved() = AutoSignInSupport(
        AutoSignInPlan(canFill = true, readTexts = false),
        credentials = { Credentials("owner@example.test", "fixture-password", "") },
        awaitCode = { null },
    )

    private object PassThroughAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}
