package com.bendourthe.milestone.ui.search

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.navigation.toRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.domain.usecase.ObserveDashboard
import com.bendourthe.milestone.sync.ProgramSyncState
import com.bendourthe.milestone.ui.*
import com.bendourthe.milestone.ui.benefits.BenefitsViewModel
import com.bendourthe.milestone.ui.catalog.CatalogViewModel
import com.bendourthe.milestone.ui.dashboard.DashboardViewModel
import com.bendourthe.milestone.ui.settings.SettingsViewModel
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import com.bendourthe.milestone.ui.theme.AuroraBackground
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bendourthe.milestone.data.settings.DataStoreBenefitsDisplaySettings
import androidx.navigation.NavDestination.Companion.hasRoute
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real app composables and navigation over an isolated synthetic Room corpus; no issuer or sign-in access. */
@RunWith(AndroidJUnit4::class)
class SearchNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: MileStoneDatabase
    private lateinit var accounts: RoomAccountRepository
    private lateinit var cards: RoomCardRepository
    private val catalog = StaticCatalogRepository()
    private val settings = object : SettingsRepository {
        override val sortMode = MutableStateFlow(SortMode.ALPHABETICAL)
        override val refreshInterval = MutableStateFlow(RefreshInterval.WEEKLY)
        override suspend fun setSortMode(mode: SortMode) { sortMode.value = mode }
        override suspend fun setRefreshInterval(interval: RefreshInterval) { refreshInterval.value = interval }
    }
    private lateinit var nav: NavHostController
    private lateinit var retainedContent: @Composable () -> Unit
    private lateinit var prefs: DataStoreBenefitsDisplaySettings
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceFile: File
    private lateinit var lastSearch: SearchViewModel
    private class HeldSearchDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private var held = false
        private val queued = mutableListOf<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()
        fun hold() = synchronized(lock) { held = true }
        fun release() {
            val tasks = synchronized(lock) { held = false; queued.toList().also { queued.clear() } }
            tasks.forEach { (context, task) -> Dispatchers.Default.dispatch(context, task) }
        }
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            val deferred = synchronized(lock) {
                if (held) { queued += context to block; true } else false
            }
            if (!deferred) Dispatchers.Default.dispatch(context, block)
        }
    }
    private val searchWorker = HeldSearchDispatcher()
    private val selectionReads = AtomicInteger()
    @Volatile private var beforeRead: (suspend (Int) -> Unit)? = null
    private fun guardedCards() = object : CardRepository by cards {
        override suspend fun allCardsWithBenefits(): Map<Card, List<Benefit>> {
            val hook = beforeRead
            if (hook != null) hook(selectionReads.incrementAndGet())
            return cards.allCardsWithBenefits()
        }
    }
    private val key = CardKey("amex", "1008")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, MileStoneDatabase::class.java).build()
        accounts = RoomAccountRepository(db); cards = RoomCardRepository(db)
        preferenceFile = File(context.cacheDir, "search-navigation-${java.util.UUID.randomUUID()}.preferences_pb")
        prefs = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = preferenceScope) { preferenceFile })
        accounts.addAccount("amex", AccountStatus.CONNECTED, 1)
        accounts.addAccount("delta", AccountStatus.NEEDS_RECONNECT, 1)
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Delta Gold", "1008")), true), 10)
        cards.applyCardBenefits(key, assignBenefitKeys(key, listOf(
            BenefitDraft(BenefitSection.CREDIT_EARNED, "Lounge Credit", 0, 5000, 5000, "January", false, description = "Airport terminal access"),
            BenefitDraft(BenefitSection.COMPLETED, "Dining Credit", 1000, 0, 1000, "February", true),
            BenefitDraft(BenefitSection.LISTED, "Statement Lounge Benefit", null, null, null, null, false, description = "Terminal club", enrollment = Enrollment.ENROLLED),
            BenefitDraft(BenefitSection.LISTED, "Museum access", null, null, null, null, false, description = "Gallery passes")
        )), 11)
        BenefitsCategory.entries.forEach { prefs.setCollapsed(key, it, true) }
        prefs.setSort(key, BenefitsSort.entries.last())
        prefs.setCompletedLast(key, true)
    }
    @After fun close(): Unit = runBlocking {
        searchWorker.release()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        beforeRead = null
        preferenceScope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        preferenceFile.delete()
    }
    private fun model(repository: CardRepository = guardedCards()) = SearchViewModel(accounts, repository, catalog, displaySettings = prefs, worker = searchWorker).also { lastSearch = it }
    private fun launchApp(fontScale: Float = 1f) {
        val dashboard = DashboardViewModel(catalog, ObserveDashboard(accounts, catalog), settings, accounts,
            MutableStateFlow<Map<String, ProgramSyncState>>(emptyMap()))
        val benefits = BenefitsViewModel(cards, catalog, accounts, displaySettings = prefs)
        val add = CatalogViewModel(catalog, accounts, debounceMs = 0)
        val prefs = SettingsViewModel(settings)
        val content: @Composable () -> Unit = {
            nav = rememberNavController()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) { MileStoneApp(dashboard, add, prefs, catalog, "fixture", nav,
                    benefitsViewModel = benefits, createSearchViewModel = { model() }) } }
            }
        }
        retainedContent = content
        rule.setContent(content)
    }
    private fun waitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun snapshot(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "search-phase3-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // Shared emulator-only evidence survives the test runner uninstalling its isolated fixture app.
        val path = File(context.getExternalFilesDir(null), "search-phase3-$name.png").absolutePath
        val output = "/sdcard/Download/milestone-search-phase3-$name.png"
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp $path $output").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
    }


    private fun search(query: String) {
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithTag("globalSearchQuery").performTextInput(query)
        rule.waitUntil(10_000) { lastSearch.state.value is SearchUiState.Results }
    }
    private fun select(kind: String, title: String) {
        rule.onNodeWithTag("globalSearchQuery").performImeAction()
        rule.onNodeWithTag(if (kind == "Program") "searchTabREWARDS" else "searchTabBENEFITS").performClick()
        val result = hasClickAction() and hasText(title) and when (kind) {
            "Benefit" -> hasTestTag("searchCompactBenefit")
            "Program" -> hasTestTag("searchProgramTile")
            else -> hasText("Card ending", substring = true)
        }
        rule.onNodeWithTag("searchResults").performScrollToNode(result)
        rule.waitUntil(10_000) { rule.onAllNodes(result).fetchSemanticsNodes().size == 1 }
        rule.onNode(result).performClick()
    }
    private fun openInstitution() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("benefitsProgram_amex").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag("benefitsProgram_amex").performClick()
    }
    private fun openCard() {
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Delta Gold", substring = true).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithContentDescription("Delta Gold", substring = true).performClick()
    }
    private fun returnToSearch(query: String) {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("globalSearchQuery").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("globalSearchQuery").assertTextContains(query)
    }
    private fun hook(action: suspend (Int) -> Unit) { selectionReads.set(0); beforeRead = action }
    private fun highlighted(text: String) = rule.onNode(hasStateDescription("Search result") and hasText(text))

    @Test fun programAndCardTargetsReturnToTheSameSearch() {
        launchApp(); search("Delta")
        select("Program", "Delta SkyMiles")
        rule.waitUntil(10_000) { nav.currentDestination?.hasRoute(Dashboard::class) == true }
        rule.onNodeWithText("Delta SkyMiles").assertIsDisplayed()
        rule.onNodeWithTag("searchProgramHighlight").assertIsDisplayed().assert(hasStateDescription("Search result"))
        snapshot("program-outline")
        rule.mainClock.advanceTimeBy(4_100)
        rule.onNodeWithTag("searchProgramHighlight").assertDoesNotExist()
        returnToSearch("Delta")
        select("Card", "Delta Gold")
        waitText("Card benefits")
        rule.onNodeWithText("Delta Gold").assertIsDisplayed()
        returnToSearch("Delta")
        rule.onNodeWithTag("searchTabBENEFITS").assertIsSelected()
        rule.onNodeWithTag("searchResultCount").assertTextEquals("1 result")
    }

    @Test fun combinedAliasRevealsOneCanonicalEnrolledRowWithoutAnyStoredChanges() {

        val beforeRows = runBlocking { cards.allCardsWithBenefits() }
        val beforePrefs = runBlocking { prefs.observe(key).first() }
        launchApp(); search("Statement")
        rule.onNodeWithTag("searchResultCount").assertTextEquals("1 result")
        select("Benefit", "Lounge Credit")
        waitText("Search result")
        highlighted("Lounge Credit").assertIsDisplayed()
        rule.onNodeWithText("Enrolled").assertIsDisplayed()
        snapshot("canonical-reveal")
        assertEquals(beforePrefs, runBlocking { prefs.observe(key).first() })
        assertEquals(beforeRows, runBlocking { cards.allCardsWithBenefits() })
        returnToSearch("Statement")
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        rule.onNode(hasText("Benefits") and hasClickAction()).performClick()
        openInstitution()
        openCard()
        waitText("Card benefits")
        rule.onNodeWithText("Lounge Credit").assertDoesNotExist()
    }

    @Test fun descriptionOnlyOtherCompletedAndLocalWelcomeTargetsAreExact() {

        runBlocking { prefs.setWelcomeBonus(key, WelcomeBonusSetup(100000, 500000, null, "80000 points")) }
        val before = runBlocking { prefs.observe(key).first() }
        launchApp(); search("Gallery")
        select("Benefit", "Museum access")
        waitText("Search result"); highlighted("Museum access").assertIsDisplayed()
        returnToSearch("Gallery")
        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("Dining")
        waitText("1 result"); select("Benefit", "Dining Credit")
        waitText("Search result"); highlighted("Dining Credit").assertIsDisplayed()
        returnToSearch("Dining")
        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("points")
        rule.waitUntil(10_000) {
            lastSearch.query.value == "points" &&
                (lastSearch.state.value as? SearchUiState.Results)?.results?.groups?.any { group ->
                    group.hits.any { (it.entry.target as? SearchTarget.BenefitTarget)?.benefitKey == "local:welcome" }
                } == true
        }
        waitText("1 result")
        select("Benefit", "Welcome bonus")
        waitText("Search result")
        highlighted("Welcome bonus").assertIsDisplayed()
        assertEquals(before, runBlocking { prefs.observe(key).first() })
    }

    @Test fun sameTitleAcrossCardsAndPeriodsRevealsOnlyTheSelectedIdentity() {
        val second = CardKey("amex", "2009")
        runBlocking {
            cards.applyCardList("amex", CardList.from(listOf(CardDraft("Delta Gold", "1008"), CardDraft("Platinum", "2009")), true), 20)
            cards.applyCardBenefits(second, assignBenefitKeys(second, listOf("January", "February").map { period ->
                BenefitDraft(BenefitSection.CREDIT_EARNED, "Lounge Credit", 0, 5000, 5000, period, false, description = "$period lounge access")
            }), 21)
        }
        launchApp(); search("February lounge")
        select("Benefit", "Lounge Credit")
        waitText("Search result")
        highlighted("Period: February").assertIsDisplayed()
        highlighted("Period: January").assertDoesNotExist()
        snapshot("period-reveal")
    }

    @Test fun removalsDuringSelectionKeepValidSearchAndNeverOpenAReplacement() {
        launchApp(); search("terminal")
        hook { beforeRead = null; cards.applyCardBenefits(key, emptyList(), 30) }
        select("Benefit", "Lounge Credit")
        waitText("That stored item is no longer available")
        rule.onNodeWithTag("globalSearchQuery").assertIsDisplayed()
        rule.onNodeWithText("Search result").assertDoesNotExist()
    }

    @Test fun cardAndProgramRemovalDuringSelectionAreSafe() {
        launchApp(); search("Delta")
        hook { beforeRead = null; cards.deleteProgramCards("amex") }
        select("Card", "Delta Gold")
        waitText("That stored item is no longer available")
        rule.waitUntil(10_000) { (lastSearch.state.value as? SearchUiState.Results)?.results?.count == 1 }
        hook { beforeRead = null; accounts.removeAccount("delta") }
        select("Program", "Delta SkyMiles")
        waitText("That stored item is no longer available")
        rule.onNodeWithTag("globalSearchQuery").assertIsDisplayed()
    }

    @Test fun reusedOccurrenceKeyBetweenNavigationAndRevealIsRejected() {
        val earlier = BenefitDraft(BenefitSection.CREDIT_EARNED, "Lounge Credit", 0, 5000, 5000, "January", false, description = "Atrium lounge")
        val later = earlier.copy(description = "Terminal lounge")
        runBlocking { cards.applyCardBenefits(key, assignBenefitKeys(key, listOf(earlier, later)), 30) }
        val oldKey = runBlocking { cards.observeBenefits(key).first().first { it.description == "Atrium lounge" }.stableKey }
        launchApp(); search("Atrium")
        hook { number -> if (number == 2) { beforeRead = null; cards.applyCardBenefits(key, assignBenefitKeys(key, listOf(later)), 31) } }
        select("Benefit", "Lounge Credit")
        waitText("Results changed. Select the item again")
        rule.onNodeWithTag("globalSearchQuery").assertIsDisplayed()
        assertEquals(oldKey, runBlocking { cards.observeBenefits(key).first().single().stableKey })
        rule.onNodeWithText("Search result").assertDoesNotExist()
    }

    @Test fun lateValidationCannotNavigateAfterAQueryChangeAndFailureCanRetryLocally() {
        val gate = CompletableDeferred<Unit>()
        launchApp(); search("terminal")
        hook { beforeRead = null; gate.await() }
        select("Benefit", "Lounge Credit")
        rule.onNodeWithTag("searchSelectionLoading").assertIsDisplayed()
        rule.onNodeWithTag("globalSearchQuery").performTextClearance()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("Dining")
        gate.complete(Unit)
        waitText("Dining Credit")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("searchSelectionLoading").fetchSemanticsNodes().isEmpty() }
        hook { beforeRead = null; throw java.io.IOException("synthetic selection read failure") }
        select("Benefit", "Dining Credit")
        waitText("Stored items could not be read. Please retry")
        rule.onNodeWithTag("globalSearchQuery").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        waitText("1 result")
        select("Benefit", "Dining Credit")
        waitText("Search result")
    }

    @Test fun resultsPositionAndQuerySurviveExactVisitAndRepeatedTapsDoNotStack() {
        runBlocking {
            cards.applyCardBenefits(key, assignBenefitKeys(key, (0 until 45).map { index ->
                BenefitDraft(BenefitSection.LISTED, "Benefit %02d".format(index), null, null, null, null, false)
            }), 40)
        }
        launchApp(); search("Benefit")
        rule.onNodeWithTag("globalSearchQuery").performImeAction()
        rule.onNodeWithTag("searchResults").performScrollToNode(hasText("Benefit 30"))
        rule.onNodeWithText("Benefit 30").performTouchInput { click(); click() }
        waitText("Search result")
        highlighted("Benefit 30").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, nav.currentBackStack.value.count { it.destination.hasRoute(CardBenefits::class) }) }
        returnToSearch("Benefit")
        rule.onNodeWithText("Benefit 30").assertIsDisplayed()
        assertTrue(lastSearch.position(SearchTab.BENEFITS).first > 0)
        rule.onNodeWithTag("searchTabBENEFITS").assertIsSelected()
        rule.onNodeWithTag("searchTabREWARDS").performClick()
        rule.onNodeWithText("No matching rewards items").assertIsDisplayed()
        rule.onNodeWithTag("searchTabBENEFITS").performClick()
        rule.onNodeWithText("Benefit 30").assertIsDisplayed()
        rule.onNodeWithTag("globalSearchQuery").performTextClearance()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("Benefit 00")
        waitText("1 result")
        rule.onNode(hasText("Benefit 00") and hasAnyAncestor(hasTestTag("searchResults"))).assertIsDisplayed()
        rule.onNodeWithText("Benefit 30").assertDoesNotExist()
        rule.onNodeWithTag("globalSearchQuery").performTextClearance()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("Benefit 30")
        waitText("1 result")
        rule.onNode(hasText("Benefit 30") and hasAnyAncestor(hasTestTag("searchResults"))).assertIsDisplayed()
        searchWorker.hold()
        try {
            rule.onNodeWithTag("globalSearchQuery").performTextClearance()
            rule.onNodeWithTag("globalSearchQuery").performTextInput("Benefit")
            rule.onNodeWithTag("globalSearchQuery").performImeAction()
            rule.waitForIdle()
            rule.onNode(hasText("Benefit 30") and hasAnyAncestor(hasTestTag("searchResults"))).assertIsDisplayed()
            rule.onNodeWithTag("searchResults").captureToImage()
        } finally {
            searchWorker.release()
        }
        waitText("45 results")
        rule.onNodeWithText("Benefit 00").assertIsDisplayed()
        rule.onNodeWithText("Benefit 30").assertIsNotDisplayed()
    }

    @Test fun pendingQueryDisposalCannotSaveAnEarlierViewport(): Unit = runBlocking {
        cards.applyCardBenefits(key, assignBenefitKeys(key, (0 until 45).map { index ->
            BenefitDraft(BenefitSection.LISTED, "Benefit %02d".format(index), null, null, null, null, false)
        }), 40)
        val vm = model()
        val store = ViewModelStore().apply { put("pending-search", vm) }
        val attached = mutableStateOf(true)
        rule.setContent {
            MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) {
                if (attached.value) SearchScreen(vm, onBack = {}, onSelect = {})
            } }
        }
        try {
            rule.onNodeWithTag("globalSearchQuery").performTextInput("Benefit")
            waitText("45 results")
            rule.onNodeWithTag("globalSearchQuery").performImeAction()
            rule.onNodeWithTag("searchResults").performScrollToNode(hasText("Benefit 30"))
            rule.onNodeWithText("Benefit 30").assertIsDisplayed()
            assertTrue(rule.onNodeWithTag("searchResults").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 0)
            searchWorker.hold()
            try {
                rule.onNodeWithTag("globalSearchQuery").performTextClearance()
                rule.onNodeWithTag("globalSearchQuery").performTextInput("benefit")
                rule.onNodeWithText("Benefit 30").assertIsDisplayed()
                snapshot("pending-before-disposal")
                rule.runOnIdle { attached.value = false }
                rule.waitForIdle()
                rule.runOnIdle {
                    SearchTab.entries.forEach { assertEquals(0 to 0, vm.position(it)) }
                    attached.value = true
                }
                rule.waitForIdle()
            } finally { searchWorker.release() }
            waitText("45 results")
            rule.onNodeWithTag("globalSearchQuery").assertTextContains("benefit")
            rule.onNodeWithText("Benefit 00").assertIsDisplayed()
            rule.onNodeWithText("Benefit 30").assertIsNotDisplayed()
            snapshot("pending-after-remount")
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun rewardsAndCardOriginScrollPositionsSurviveOpeningAndClosingSearch() {
        val last = catalog.programs.maxBy { it.displayName }
        runBlocking {
            catalog.programs.filter { it.programId !in setOf("amex", "delta") }.forEach { accounts.addAccount(it.programId, AccountStatus.NEEDS_RECONNECT, 1) }
            prefs.setCollapsed(key, BenefitsCategory.OTHER, false)
            cards.applyCardBenefits(key, assignBenefitKeys(key, (0 until 45).map { index ->
                BenefitDraft(BenefitSection.LISTED, "Benefit %02d".format(index), null, null, null, null, false)
            }), 60)
        }
        fun position(tag: String): Float = rule.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        launchApp()
        rule.onNodeWithTag("dashboardList").performScrollToNode(hasText(last.displayName))
        val rewardsPosition = position("dashboardList")
        assertTrue(rewardsPosition > 0)
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        assertEquals(rewardsPosition, position("dashboardList"), 0.01f)
        rule.onNode(hasText("Benefits") and hasClickAction()).performClick()
        openInstitution()
        openCard()
        waitText("Card benefits")
        rule.onNodeWithTag("cardBenefitsList").performScrollToNode(hasText("Benefit 15"))
        val cardPosition = position("cardBenefitsList")
        assertTrue(cardPosition > 0)
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Benefit 15").assertIsDisplayed()
        assertEquals(cardPosition, position("cardBenefitsList"), 0.01f)
    }

    @Test fun removedOriginatingCardFallsBackToItsInstitution() {
        launchApp()
        rule.onNode(hasText("Benefits") and hasClickAction()).performClick()
        openInstitution()
        openCard()
        waitText("Card benefits")
        search("Delta")
        runBlocking { cards.deleteProgramCards("amex") }
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Back to institutions").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Back to institutions").assertIsDisplayed()
    }

    @Test fun returningToTheSameQueryDoesNotReviveAnObsoleteSelection() {
        val gate = CompletableDeferred<Unit>()
        launchApp(); search("terminal")
        hook { beforeRead = null; gate.await() }
        select("Benefit", "Lounge Credit")
        rule.onNodeWithTag("searchSelectionLoading").assertIsDisplayed()
        rule.onNodeWithTag("globalSearchQuery").performTextClearance()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("Dining")
        rule.onNodeWithTag("globalSearchQuery").performTextClearance()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("terminal")
        gate.complete(Unit)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("searchSelectionLoading").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("globalSearchQuery").assertTextContains("terminal")
        rule.runOnIdle { assertTrue(nav.currentBackStackEntry!!.destination.hasRoute(Search::class)) }
        rule.onNodeWithText("Search result").assertDoesNotExist()
    }

    @Test fun savedCollapsedCategoryCanBeHiddenAfterSearchRevealsIt() {
        val before = preferenceFile.readBytes()
        launchApp(); search("Museum")
        select("Benefit", "Museum access")
        waitText("Search result")
        highlighted("Museum access").assertIsDisplayed()
        rule.onNodeWithTag("cardBenefitsList").performScrollToNode(hasText("Other") and hasClickAction())
        rule.onNode(hasText("Other") and hasClickAction()).performClick()
        rule.onNodeWithText("Museum access").assertDoesNotExist()
        assertArrayEquals(before, preferenceFile.readBytes())
    }

    @Test fun identicalCardProductsAndBenefitsShowDistinctCardEndingContext() {
        val other = CardKey("amex", "2009")
        runBlocking {
            cards.applyCardList("amex", CardList.from(listOf(CardDraft("Delta Gold", "1008"), CardDraft("Delta Gold", "2009")), true), 70)
            cards.applyCardBenefits(other, assignBenefitKeys(other, listOf(
                BenefitDraft(BenefitSection.LISTED, "Museum access", null, null, null, null, false, description = "Gallery passes")
            )), 71)
        }
        launchApp(); search("Museum")
        waitText("2 results")
        rule.onNodeWithTag("globalSearchQuery").performImeAction()
        rule.onNodeWithTag("searchResults").performScrollToNode(hasText("Card ending 1008"))
        rule.onNodeWithText("Card ending 1008").assertIsDisplayed()
        rule.onNodeWithTag("searchResults").performScrollToNode(hasText("Card ending 2009"))
        rule.onNodeWithText("Card ending 2009").assertIsDisplayed()
        snapshot("same-name-context")
        val exact = hasTestTag("searchCompactBenefit") and hasContentDescription("Card ending 2009", substring = true)
        rule.onNodeWithTag("searchResults").performScrollToNode(exact)
        rule.onNode(exact).performClick()
        waitText("Search result")
        rule.runOnIdle { assertEquals(other.lastDigits, nav.currentBackStackEntry!!.toRoute<CardBenefits>().lastDigits) }
        returnToSearch("Museum")
        rule.onNodeWithTag("globalSearchQuery").performTextClearance()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("2009")
        waitText("No matches in your stored items")
    }

    @Test fun actualNavigationRotationKeepsQueryInMemoryWithoutSavingIt() {
        val token = "SearchPrivacyFixtureTokenAlpha"
        val control = "SavedStatePositiveControlMarker"
        val captured = AtomicReference<ByteArray?>()
        val application = context as android.app.Application
        val targetActivity = rule.activity
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityPostSaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {
                if (activity === targetActivity) {
                    val parcel = android.os.Parcel.obtain()
                    try { parcel.writeBundle(state); captured.set(parcel.marshall()) } finally { parcel.recycle() }
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        }
        val beforeRows = runBlocking { cards.allCardsWithBenefits() }
        val beforePreferences = preferenceFile.readBytes()
        launchApp()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithTag("globalSearchQuery").performTextInput(token)
        waitText("No matches in your stored items")
        val original = lastSearch
        rule.runOnUiThread {
            application.registerActivityLifecycleCallbacks(callbacks)
            targetActivity.savedStateRegistry.registerSavedStateProvider("search_privacy_control") {
                android.os.Bundle().apply { putString("marker", control) }
            }
        }
        try {
            rule.activityRule.scenario.recreate()
            rule.activityRule.scenario.onActivity { activity -> activity.setContent(content = retainedContent) }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("globalSearchQuery").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithTag("globalSearchQuery").assertTextContains(token)
            assertSame(original, lastSearch)
            val bytes = requireNotNull(captured.get()) { "No completed Activity saved-state callback was captured" }
            fun contains(text: String): Boolean = listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).any { charset ->
                val needle = text.toByteArray(charset)
                (0..(bytes.size - needle.size).coerceAtLeast(-1)).any { offset -> needle.indices.all { bytes[offset + it] == needle[it] } }
            }
            assertTrue("Saved-state positive control was not captured", contains(control))
            assertFalse("Query text was serialized into the Activity state", contains(token))
            assertEquals(beforeRows, runBlocking { cards.allCardsWithBenefits() })
            assertArrayEquals(beforePreferences, preferenceFile.readBytes())
            val report = org.json.JSONObject().put("saved_state_positive_control", true).put("serialized_query_present", false)
                .put("configuration_query_retained", true).put("same_navigation_scoped_model", true)
                .put("issuer_rows_unchanged", true).put("preferences_unchanged", true)
            val output = File(context.getExternalFilesDir(null), "search-state-privacy.json")
            output.writeText(report.toString(2))
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /sdcard/Download/milestone-search-state-privacy.json").use {
                java.io.FileInputStream(it.fileDescriptor).readBytes()
            }
        } finally { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }

}
