package com.bendourthe.milestone.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.SortMode
import com.bendourthe.milestone.domain.usecase.ObserveDashboard
import com.bendourthe.milestone.sync.ProgramSyncState
import com.bendourthe.milestone.ui.benefits.BenefitsViewModel
import com.bendourthe.milestone.ui.catalog.CatalogViewModel
import com.bendourthe.milestone.ui.dashboard.DashboardViewModel
import com.bendourthe.milestone.ui.settings.SettingsViewModel
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class MileStoneAppTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val accounts = FakeAccounts()
    private val settings = FakeSettings()
    private lateinit var nav: NavHostController

    private fun stack() = nav.currentBackStack.value.mapNotNull { it.destination.route?.substringAfterLast('.') }

    private fun launch() {
        val sync = MutableStateFlow<Map<String, ProgramSyncState>>(emptyMap())
        val dashboard = DashboardViewModel(uiTestCatalog, ObserveDashboard(accounts, uiTestCatalog), settings, accounts, sync)
        val catalog = CatalogViewModel(uiTestCatalog, accounts, debounceMs = 0)
        val settingsViewModel = SettingsViewModel(settings)
        val benefits = BenefitsViewModel(FakeCards(), uiTestCatalog, accounts)
        rule.setContent {
            nav = rememberNavController()
            MileStoneTheme { MileStoneApp(dashboard, catalog, settingsViewModel, uiTestCatalog, "0.1.0", nav, benefitsViewModel = benefits) }
        }
    }

    private fun pressBack() = rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun barItem(label: String) = rule.onNode(hasText(label) and hasClickAction())

    @Test
    fun theBarReadsRewardsBenefitsSettingsAndBenefitsOpens() {
        launch()
        val labels = rule.onAllNodes(hasClickAction() and (hasText("Rewards") or hasText("Benefits") or hasText("Settings")))
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.left }
            .map { node -> node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty() }
        check(labels == listOf("Rewards", "Benefits", "Settings")) { "bar order: $labels" }
        barItem("Benefits").performClick()
        rule.onNodeWithText("No card benefits yet").assertIsDisplayed()
        check(stack().lastOrNull() == "Benefits") { "stack: ${stack()}" }
    }

    @Test
    fun backFromFirstSettingsVisitReturnsToDashboard() {
        launch()
        barItem("Settings").performClick()
        rule.onNodeWithText("Default sort").assertIsDisplayed()
        pressBack()
        rule.waitForIdle()
        check(stack().lastOrNull() == "Dashboard") { "after back: ${stack()}" }
    }

    @Test
    fun navigatesBetweenTabsAndCatalog() {
        launch()
        rule.onNodeWithText("No programs yet").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add program").performClick()
        rule.onNodeWithText("Add a program").assertIsDisplayed()
        rule.onNode(hasText("Rewards") and hasClickAction()).assertDoesNotExist()
        pressBack()
        rule.onNodeWithText("No programs yet").assertIsDisplayed()
        barItem("Settings").performClick()
        rule.onNodeWithText("Default sort").assertIsDisplayed()
        rule.onNodeWithText("Version 0.1.0").performScrollTo().assertIsDisplayed()
        barItem("Rewards").performClick()
        rule.onNodeWithText("No programs yet").assertIsDisplayed()
        barItem("Settings").performClick()
        rule.mainClock.advanceTimeBy(2_000)
        val before = stack()
        pressBack()
        rule.waitForIdle()
        check(stack().lastOrNull() == "Dashboard") { "before back: $before, after back: ${stack()}" }
        rule.onNodeWithText("No programs yet").assertIsDisplayed()
    }

    @Test
    fun catalogListsProgramsThatDoNotSyncAndOffersNoManualEntry() {
        launch()
        rule.onNodeWithContentDescription("Add program").performClick()
        listOf("American Express Membership Rewards", "Chase Ultimate Rewards", "Capital One Miles", "Delta SkyMiles", "United MileagePlus", "Marriott Bonvoy")
            .forEach { rule.onNodeWithTag("catalogList").performScrollToNode(hasText(it)); rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Add more").assertDoesNotExist()
        rule.onNodeWithTag("catalogList").performScrollToNode(hasTestTag("catalogSearch"))
        rule.onNodeWithTag("catalogSearch").performTextInput("cit")
        rule.waitUntil(2_000) { rule.onAllNodes(hasText("Chase Ultimate Rewards")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Citi ThankYou Points").assertIsDisplayed()
        rule.onNodeWithText("Citi ThankYou Points").performClick()
        rule.waitUntil(2_000) { rule.onAllNodes(hasText("Citi ThankYou Points can't sign in from MileStone yet")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("catalogSearch").assertIsDisplayed()
        rule.onNodeWithTag("catalogSearch").performTextClearance()
        rule.onNodeWithTag("catalogSearch").performTextInput("hil")
        rule.waitUntil(2_000) { rule.onAllNodes(hasText("World of Hyatt")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Hilton Honors").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enter balance manually").assertDoesNotExist()
    }

    @Test
    fun tileMenuHasNoManualEntry() {
        runBlocking { accounts.addAccount("hilton", AccountStatus.CONNECTED, 0) }
        launch()
        rule.onNode(hasContentDescription("More options for Hilton Honors")).performClick()
        rule.onNodeWithText("Remove").assertIsDisplayed()
        rule.onNodeWithText("Enter balance").assertDoesNotExist()
    }

    @Test
    fun noMatchStateShowsQuery() {
        launch()
        rule.onNodeWithContentDescription("Add program").performClick()
        rule.onNodeWithTag("catalogSearch").performTextInput("%%")
        rule.waitUntil(2_000) { rule.onAllNodes(hasText("No programs match \"%%\"")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun customSortShowsHandlesAndDragReorders() {
        runBlocking { listOf("amex", "chase", "delta").forEach { accounts.addAccount(it, AccountStatus.UNSUPPORTED, 0) } }
        launch()
        rule.onAllNodesWithContentDescription("Reorder", substring = true).assertCountEquals(0)
        rule.onNode(hasContentDescription("Sort programs")).performClick()
        rule.onNodeWithText("Custom").performClick()
        rule.waitUntil(2_000) { rule.onAllNodesWithContentDescription("Reorder", substring = true).fetchSemanticsNodes().size == 3 }
        assertEquals(SortMode.CUSTOM, runBlocking { settings.sortMode.first() })

        rule.onNodeWithText("Delta SkyMiles").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            repeat(60) {
                moveBy(Offset(0f, -20f))
                advanceEventTime(50)
            }
            advanceEventTime(300)
            up()
        }
        rule.waitUntil(3_000) { accounts.reorderCalls.isNotEmpty() }
        val saved = accounts.reorderCalls.single()
        assertTrue(saved.indexOf("delta") < saved.indexOf("chase"))
        assertEquals(saved, accounts.accounts.value.sortedBy { it.sortOrder }.map { it.programId })
    }

    @Test
    fun byTypeSortGroupsCashBackUnderItsOwnHeading() {
        runBlocking { listOf("capitalone", "amex", "delta").forEach { accounts.addAccount(it, AccountStatus.UNSUPPORTED, 0) } }
        launch()
        rule.onNode(hasContentDescription("Sort programs")).performClick()
        rule.onNodeWithText("By type").performClick()
        rule.waitUntil(2_000) { rule.onAllNodes(hasText("Cash back")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(SortMode.BY_TYPE, runBlocking { settings.sortMode.first() })
        rule.onNodeWithText("Points and miles").assertIsDisplayed()
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        // Types in alphabetical order: Cash back, then Points and miles.
        assertTrue(top("Cash back") < top("Capital One Miles"))
        assertTrue(top("Capital One Miles") < top("Points and miles"))
        rule.onNodeWithTag("dashboardList").performScrollToNode(hasText("Delta SkyMiles"))
        assertTrue(top("Points and miles") < top("Delta SkyMiles"))
        rule.onAllNodesWithContentDescription("Reorder", substring = true).assertCountEquals(0)
    }

    @Test
    fun byCategorySortGroupsTilesUnderTheAddProgramCategories() {
        runBlocking { listOf("delta", "amex").forEach { accounts.addAccount(it, AccountStatus.UNSUPPORTED, 0) } }
        launch()
        rule.onNode(hasContentDescription("Sort programs")).performClick()
        rule.onNodeWithText("By category").performClick()
        rule.waitUntil(2_000) { rule.onAllNodes(hasText("Airlines")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(SortMode.BY_CATEGORY, runBlocking { settings.sortMode.first() })
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        // Categories in alphabetical order: Airlines, then Credit cards.
        assertTrue(top("Airlines") < top("Delta SkyMiles"))
        assertTrue(top("Delta SkyMiles") < top("Credit cards"))
        assertTrue(top("Credit cards") < top("American Express Membership Rewards"))
    }

    @Test
    fun moveUpAccessibilityActionReorders() {
        runBlocking { listOf("amex", "chase").forEach { accounts.addAccount(it, AccountStatus.UNSUPPORTED, 0) } }
        runBlocking { settings.setSortMode(SortMode.CUSTOM) }
        launch()
        rule.waitUntil(2_000) { rule.onAllNodesWithContentDescription("Reorder", substring = true).fetchSemanticsNodes().size == 2 }
        val hasMoveUp = SemanticsMatcher("has Move up action") { n ->
            n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Move up" } == true
        }
        rule.onNode(hasMoveUp).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Move up" }.action()
        rule.waitUntil(2_000) { accounts.reorderCalls.isNotEmpty() }
        assertEquals(listOf("chase", "amex"), accounts.reorderCalls.last())
    }

    @Test
    fun tappingARewardsProgramWithBenefitsOpensItsCardsAndBackReturnsToRewards() {
        val realCatalog = com.bendourthe.milestone.data.catalog.StaticCatalogRepository()
        val cards = FakeCards()
        runBlocking {
            accounts.addAccount("amex", AccountStatus.CONNECTED, 1)
            cards.applyCardList("amex", com.bendourthe.milestone.domain.CardList.from(
                listOf(com.bendourthe.milestone.domain.CardDraft("Platinum Card", "1234")), true), 1)
        }
        val sync = MutableStateFlow<Map<String, ProgramSyncState>>(emptyMap())
        val dashboard = DashboardViewModel(realCatalog, ObserveDashboard(accounts, realCatalog), settings, accounts, sync)
        val benefits = BenefitsViewModel(cards, realCatalog, accounts)
        rule.setContent {
            nav = rememberNavController()
            MileStoneTheme { MileStoneApp(dashboard, CatalogViewModel(realCatalog, accounts, debounceMs = 0),
                SettingsViewModel(settings), realCatalog, "0.3.1", nav, benefitsViewModel = benefits) }
        }
        rule.onNodeWithText("American Express").performClick()
        rule.onNodeWithTag("benefitsList").assertIsDisplayed()
        rule.onNodeWithText("Platinum Card", useUnmergedTree = true).assertExists()
        pressBack()
        rule.onNodeWithTag("dashboardList").assertIsDisplayed()
    }

}
