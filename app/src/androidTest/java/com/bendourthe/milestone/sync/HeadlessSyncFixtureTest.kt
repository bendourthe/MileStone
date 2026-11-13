package com.bendourthe.milestone.sync

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.data.session.TinkSessionStore
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.BenefitsSpec
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.sync.webview.ActivityWebViewHost
import com.bendourthe.milestone.sync.webview.HeadlessWebViewBalanceSource
import com.bendourthe.milestone.sync.webview.HeadlessWebViewBenefitsSource
import com.bendourthe.milestone.sync.webview.WebViewProfileBackend
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Refreshes a connected fixture account through a hidden WebView inside a visible activity. */
@RunWith(AndroidJUnit4::class)
class HeadlessSyncFixtureTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val testAssets = InstrumentationRegistry.getInstrumentation().context
    private val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(testAssets)).build()
    private val db = Room.inMemoryDatabaseBuilder(app, MileStoneDatabase::class.java).build()
    private val accounts = RoomAccountRepository(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionFile = File(app.cacheDir, "headless-fixture-sessions.pb").apply { delete() }
    private val sessions = TinkSessionStore(sessionFile, PassThroughAead, scope) {}
    private val scenario = ActivityScenario.launch(ComponentActivity::class.java)

    private val program = Program(
        programId = "fixture",
        displayName = "Fixture Rewards",
        category = ProgramCategory.HOTEL,
        monogram = "FR",
        logoKey = null,
        isPopular = false,
        brandColorArgb = 0xFF336699,
        unitLabel = "pts",
        loginUrl = "https://$HOST/assets/fixtures/login-at-dashboard.html",
        dashboardUrl = "https://$HOST/assets/fixtures/dashboard.html",
        allowedHosts = setOf(HOST),
        identityProviderHosts = emptySet(),
        syncCapability = SyncCapability.Automatic(
            ExtractionSpec.Selector(SelectorStrategy.CSS, ".points-balance", loggedInUrlPattern = Regex("/fixtures/"), loginUrlPattern = Regex("/signin")),
        ),
    )
    private val catalog = object : CatalogRepository {
        override val programs = listOf(program)
        override fun program(programId: String) = programs.firstOrNull { it.programId == programId }
        override fun search(query: String) = programs
    }

    @After
    fun tearDown() {
        scenario.close()
        db.close()
        scope.cancel()
        sessionFile.delete()
    }

    @Test
    fun connectedAccountRefreshesWhileActivityIsVisible() = runBlocking {
        lateinit var host: WebViewHost
        scenario.onActivity { host = ActivityWebViewHost(it) }
        val manager = SyncManager(
            accounts, sessions, catalog,
            HeadlessWebViewBalanceSource(WebViewProfileBackend(), interceptor = { loader.shouldInterceptRequest(it.url) }),
            scope, host = { host },
        )
        accounts.addAccount("fixture", AccountStatus.CONNECTED, 1)
        sessions.markConnected("fixture", 1)

        withTimeout(40_000) { manager.refreshAll(RefreshReason.ON_START).join() }

        val account = accounts.observeAccounts().first().single()
        assertEquals(142_500L, account.balance)
        assertEquals(AccountStatus.CONNECTED, account.status)
        assertTrue(sessions.get("fixture")!!.lastVerifiedEpochMs > 1)
    }

    /** A manager whose benefits read goes through [intercept], over the synthetic Rewards, activity, and view-all pages. */
    private fun benefitsManager(host: WebViewHost, intercept: (WebResourceRequest) -> WebResourceResponse?): Pair<SyncManager, RoomCardRepository> {
        val spec = BenefitsSpec(
            entryUrl = "https://$HOST/assets/fixtures/benefits-rewards.html",
            host = HOST,
            paths = setOf("/assets/fixtures"),
            selectors = StaticCatalogRepository.AMEX_BENEFITS.selectors + mapOf(
                "activityLink" to "a[href*='benefits-activity']",
                "viewAllLink" to "a[href*='benefits-view-all']",
                "activityPath" to "/assets/fixtures/benefits-activity",
                "rewardsPath" to "/assets/fixtures/benefits-",
                "viewAllPath" to "/assets/fixtures/benefits-view-all",
            ),
        )
        val withBenefits = program.copy(benefits = spec)
        val cards = RoomCardRepository(db)
        val manager = SyncManager(
            accounts, sessions,
            object : CatalogRepository {
                override val programs = listOf(withBenefits)
                override fun program(programId: String) = programs.firstOrNull { it.programId == programId }
                override fun search(query: String) = programs
            },
            HeadlessWebViewBalanceSource(WebViewProfileBackend(), interceptor = intercept),
            scope, host = { host },
            benefitsSource = HeadlessWebViewBenefitsSource(WebViewProfileBackend(), interceptor = intercept),
            cards = cards,
        )
        return manager to cards
    }

    private suspend fun storedAfter(cards: RoomCardRepository, afterEpochMs: Long) = withTimeout(120_000) {
        cards.observeCards("fixture").first { list -> list.size == 3 && list.all { (it.benefitsReadEpochMs ?: 0) > afterEpochMs } }
    }

    private suspend fun sections(cards: RoomCardRepository, digits: String) =
        cards.observeBenefits(CardKey("fixture", digits)).first().groupingBy { if (it.section == BenefitSection.LISTED) "listed" else "trackers" }.eachCount()

    @Test
    fun aRefreshStoresTheBalanceThenEveryCardsBenefits() = runBlocking {
        lateinit var host: WebViewHost
        scenario.onActivity { host = ActivityWebViewHost(it) }
        val (manager, cards) = benefitsManager(host) { loader.shouldInterceptRequest(it.url) }
        accounts.addAccount("fixture", AccountStatus.CONNECTED, 1)
        sessions.markConnected("fixture", 1)

        withTimeout(40_000) { manager.refreshAll(RefreshReason.ON_START).join() }
        assertEquals(142_500L, accounts.observeAccounts().first().single().balance)

        val stored = storedAfter(cards, 0)
        assertEquals(
            listOf("71008" to "Fixture Platinum Card", "42005" to "Fixture Hotel Card", "33006" to "Fixture Basic Card"),
            stored.map { it.lastDigits to it.name },
        )
        assertEquals(mapOf("trackers" to 4, "listed" to 3), sections(cards, "71008"))
        // The Hotel and Basic cards have no activity page: their Rewards trackers and their view-all lists are stored together.
        assertEquals(mapOf("trackers" to 3, "listed" to 4), sections(cards, "42005"))
        assertEquals(mapOf("listed" to 4), sections(cards, "33006"))
    }

    @Test
    fun aFailingViewAllPageKeepsTheStoredListWhileTheTrackersUpdate() = runBlocking {
        lateinit var host: WebViewHost
        scenario.onActivity { host = ActivityWebViewHost(it) }
        var failViewAll = false
        val (manager, cards) = benefitsManager(host) { request ->
            if (failViewAll && request.url.path.orEmpty().contains("benefits-view-all")) {
                WebResourceResponse("text/html", "utf-8", 500, "Server Error", emptyMap(), "".byteInputStream())
            } else {
                loader.shouldInterceptRequest(request.url)
            }
        }
        accounts.addAccount("fixture", AccountStatus.CONNECTED, 1)
        sessions.markConnected("fixture", 1)

        withTimeout(40_000) { manager.refreshAll(RefreshReason.ON_START).join() }
        val firstRead = storedAfter(cards, 0).maxOf { it.benefitsReadEpochMs!! }
        assertEquals(mapOf("trackers" to 3, "listed" to 4), sections(cards, "42005"))

        failViewAll = true
        // The first read's job ends just after its last write; a refresh while it still runs would start no second read.
        delay(2_000)
        withTimeout(40_000) { manager.refreshAll(RefreshReason.ON_START).join() }
        storedAfter(cards, firstRead)
        // The second read reached only the Rewards page: the trackers were replaced, the list from the first read stays.
        assertEquals(mapOf("trackers" to 3, "listed" to 4), sections(cards, "42005"))
        assertEquals(mapOf("listed" to 4), sections(cards, "33006"))
    }

    private object PassThroughAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}
