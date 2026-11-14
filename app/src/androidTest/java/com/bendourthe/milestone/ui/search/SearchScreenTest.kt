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
import androidx.lifecycle.ViewModelProvider.Factory
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

/** Real app composables and navigation over an isolated synthetic Room corpus; no issuer or sign-in access. */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
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
    private val key = CardKey("amex", "1008")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, MileStoneDatabase::class.java).build()
        accounts = RoomAccountRepository(db); cards = RoomCardRepository(db)
        accounts.addAccount("amex", AccountStatus.CONNECTED, 1)
        accounts.addAccount("delta", AccountStatus.NEEDS_RECONNECT, 1)
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Delta Gold", "1008")), true), 10)
        cards.applyCardBenefits(key, assignBenefitKeys(key, listOf(
            BenefitDraft(BenefitSection.CREDIT_EARNED, "Lounge Credit", 0, 5000, 5000, "January", false, description = "Airport terminal access"),
            BenefitDraft(BenefitSection.COMPLETED, "Dining Credit", 1000, 0, 1000, "February", true)
        )), 11)
    }
    @After fun close() { db.close() }
    private fun model(repository: CardRepository = cards) = SearchViewModel(accounts, repository, catalog)
    private fun launchApp(fontScale: Float = 1f) {
        val dashboard = DashboardViewModel(catalog, ObserveDashboard(accounts, catalog), settings, accounts,
            MutableStateFlow<Map<String, ProgramSyncState>>(emptyMap()))
        val benefits = BenefitsViewModel(cards, catalog, accounts)
        val add = CatalogViewModel(catalog, accounts, debounceMs = 0)
        val prefs = SettingsViewModel(settings)
        rule.setContent {
            nav = rememberNavController()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) { MileStoneApp(dashboard, add, prefs, catalog, "fixture", nav,
                    benefitsViewModel = benefits, createSearchViewModel = { model() }) } }
            }
        }
    }
    private fun waitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun snapshot(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "search-phase2-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // Shared emulator-only evidence survives the test runner uninstalling its isolated fixture app.
        val path = File(context.getExternalFilesDir(null), "search-phase2-$name.png").absolutePath
        val output = "/sdcard/Download/milestone-search-phase2-$name.png"
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp $path $output").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
    }

    @Test fun groupedContextClearAndFreshEntryUseRealNavigation() {
        launchApp()
        rule.onNodeWithContentDescription("Search").performClick()
        waitText("Search programs, cards, and benefits")
        rule.onNode(hasText("Rewards") and hasClickAction()).assertDoesNotExist()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("Delta lounge")
        waitText("1 result")
        rule.onNodeWithText("Lounge Credit").assertIsDisplayed()
        rule.onNodeWithText("Delta Gold").assertIsDisplayed()
        snapshot("context")
        rule.onNodeWithContentDescription("Clear search").performClick()
        waitText("Search programs, cards, and benefits")
        rule.onNodeWithTag("globalSearchQuery").performTextInput("no-such-item")
        waitText("No matches in your stored items")
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("Search").performClick()
        waitText("Search programs, cards, and benefits")
        rule.onNodeWithTag("globalSearchQuery").assertTextContains("")
        rule.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    @Test fun everyBenefitsHeaderAndSystemBackReturnToTheOrigin() {
        launchApp()
        rule.onNode(hasText("Benefits") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("benefitsInstitutions").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("benefitsInstitutions").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("American Express", substring = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("benefitsList").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("benefitsList").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Delta Gold", substring = true).performClick()
        waitText("Card benefits")
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        waitText("Card benefits")
        rule.onNodeWithTag("benefitsSort").assertIsDisplayed()
        rule.onNodeWithTag("benefitsFilters").assertIsDisplayed()
    }

    @Test fun descriptionAndCompletedContextAreVisibleAndCountsDoNotIncludeHeadings() {
        val vm = model()
        rule.setContent { MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) { SearchScreen(vm, {}, {}) } } }
        rule.onNodeWithTag("globalSearchQuery").performTextInput("terminal")
        waitText("1 result")
        rule.onNodeWithText("Airport terminal access").assertIsDisplayed()
        rule.onNodeWithTag("globalSearchQuery").performTextClearance()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("Dining")
        waitText("Dining Credit")
        rule.onNodeWithText("Completed").assertIsDisplayed()
        rule.onNodeWithText("February").assertIsDisplayed()
    }

    @Test fun delayedReadAndReadErrorAreDistinctAndRetryReadsOnlyLocalData() {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val repository = object : CardRepository by cards {
            override suspend fun allCardsWithBenefits(): Map<Card, List<Benefit>> {
                reads++
                if (reads == 1) { release.await(); throw java.io.IOException("fixture read failure") }
                return cards.allCardsWithBenefits()
            }
        }
        val vm = model(repository)
        rule.setContent { MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) { SearchScreen(vm, {}, {}) } } }
        rule.onNodeWithTag("searchLoading").assertIsDisplayed()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("lounge")
        release.complete(Unit)
        waitText("Stored items could not be read")
        rule.onNodeWithText("Retry").performClick()
        waitText("1 result")
        assertEquals(2, reads)
    }

    @Test fun actualActivityRecreationRetainsOnlyTheInMemoryModelAndDoesNotRepeatFocus() {
        val factory = object : Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = model() as T
        }
        lateinit var original: SearchViewModel
        rule.activityRule.scenario.onActivity { activity ->
            original = ViewModelProvider(activity, factory)[SearchViewModel::class.java]
        }
        rule.setContent { MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) { SearchScreen(original, {}, {}) } } }
        rule.onNodeWithTag("globalSearchQuery").performTextInput("lounge")
        waitText("1 result")
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { activity ->
            val retained = ViewModelProvider(activity, factory)[SearchViewModel::class.java]
            assertSame(original, retained)
            assertFalse(retained.consumeInitialFocus())
            activity.setContent { MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) { SearchScreen(retained, {}, {}) } } }
        }
        waitText("1 result")
        rule.onNodeWithTag("globalSearchQuery").assertTextContains("lounge")
        assertEquals("", model().query.value)
    }

    @Test fun doubledFontKeepsSearchBackClearAndRowsUsable() {
        val vm = model()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) { SearchScreen(vm, {}, {}) } }
            }
        }
        rule.onNodeWithTag("globalSearchQuery").performTextInput("lounge")
        waitText("1 result")
        rule.onNodeWithContentDescription("Back", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Clear search").assertIsDisplayed()
        rule.onNodeWithTag("globalSearchQuery").performImeAction()
        rule.onNodeWithTag("searchResults").performScrollToNode(hasText("Lounge Credit"))
        rule.onNodeWithText("Lounge Credit").assertIsDisplayed()
        snapshot("font200")
        rule.onNodeWithContentDescription("Clear search").performClick()
        waitText("Search programs, cards, and benefits")
    }

    @Test fun keyboardBackDismissesImeBeforeLeavingSearch() {
        launchApp()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithTag("globalSearchQuery").performTextInput("lounge")
        waitText("1 result")
        rule.waitUntil(5_000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("globalSearchQuery").assertIsDisplayed()
        rule.waitUntil(5_000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false }
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithContentDescription("Add program").assertIsDisplayed()
    }

    @Test fun rewardsHeaderAtDoubledFontRetainsAllActions() {
        launchApp(fontScale = 2f)
        waitText("Delta SkyMiles")
        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
        rule.onNodeWithContentDescription("Refresh All").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sort programs").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add program").assertIsDisplayed()
        val mark = rule.onNodeWithContentDescription("MileStone").fetchSemanticsNode().boundsInRoot
        listOf("Search", "Refresh All", "Sort programs", "Add program").forEach { label ->
            assertFalse(mark.overlaps(rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot))
        }
        snapshot("rewards-font200")
    }

    @Test fun rewardsHeaderPreservesExistingActionsAtNarrowWidth() {
        launchApp()
        waitText("Delta SkyMiles")
        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
        rule.onNodeWithContentDescription("Refresh All").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sort programs").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add program").assertIsDisplayed()
        snapshot("rewards")
    }
}
