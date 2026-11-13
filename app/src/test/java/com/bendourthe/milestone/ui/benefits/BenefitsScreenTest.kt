package com.bendourthe.milestone.ui.benefits

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.assignBenefitKeys
import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeCards
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class BenefitsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val accounts = FakeAccounts()
    private val cards = FakeCards()
    private val now = LocalDate.of(2026, 11, 12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L
    private val platinum = CardKey("amex", "71008")
    private val hotel = CardKey("amex", "42005")
    private val viewModel by lazy { BenefitsViewModel(cards, StaticCatalogRepository(), accounts, { now }, ZoneOffset.UTC, Locale.US) }

    private fun showTab() = rule.setContent { MileStoneTheme { BenefitsScreen(viewModel, onOpenCard = {}) } }

    private fun seed() = runBlocking {
        accounts.addAccount("amex", AccountStatus.CONNECTED, 1)
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Platinum Card", "71008"), CardDraft("Hotel Card", "42005")), true), now - day)
        cards.applyCardBenefits(
            platinum,
            assignBenefitKeys(
                platinum,
                listOf(
                    BenefitDraft(BenefitSection.SPEND_PROGRESS, "Lounge Guest Access", 477_000, 7_023_000, 7_500_000, null, false),
                    BenefitDraft(BenefitSection.CREDIT_EARNED, "Shopping Credit", 0, 5_000, 5_000, "Oct - Dec", false),
                    BenefitDraft(BenefitSection.COMPLETED, "Streaming Credit", 2_000, 0, 2_000, "Nov", true),
                    BenefitDraft(BenefitSection.OTHER, "Mystery Benefit", null, null, null, null, false),
                    BenefitDraft(BenefitSection.LISTED, "Purchase Protection", null, null, null, null, false, description = "Covers purchases", enrollment = Enrollment.ENROLLMENT_REQUIRED, issuerKey = "pp"),
                ),
            ),
            now - day,
        )
        // The hotel card's last read failed: its card list was read after its benefits.
        cards.applyCardBenefits(hotel, emptyList(), now - 20 * day)
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Platinum Card", "71008"), CardDraft("Hotel Card", "42005")), true), now - day)
    }

    @Test
    fun withoutAmexTheTabSaysHowToAddIt() {
        showTab()
        rule.onNodeWithText("No card benefits yet").assertExists()
        rule.onNodeWithText("American Express", substring = true).assertExists()
    }

    @Test
    fun amexAddedButNotReadSaysWhenBenefitsAppear() {
        runBlocking { accounts.addAccount("amex", AccountStatus.CONNECTED, 1) }
        showTab()
        rule.onNodeWithText("Benefits appear after the next American Express refresh").assertExists()
    }

    @Test
    fun oneTilePerCardWithNextCreditCompletedCountAndStaleNote() {
        seed()
        showTab()
        rule.onNodeWithText("Platinum Card", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("…71008", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Shopping Credit: \$50 left (Oct - Dec)", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1 completed", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Last updated: 1 d ago", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Hotel Card", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Not updated since Oct 23", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("No open credits", useUnmergedTree = true).assertExists()
    }

    @Test
    fun eachTileIsOneTalkBackStopWithItsActionAndA48DpTarget() {
        seed()
        showTab()
        val tile = rule.onNode(hasContentDescription("Platinum Card, card ending 71008", substring = true), useUnmergedTree = true)
        tile.assertHasClickAction()
        tile.assertHeightIsAtLeast(48.dp)
        // The tile's texts are not separate TalkBack stops: the merged tree has only the tile's description.
        assertEquals(0, rule.onAllNodesWithText("Platinum Card").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodes(hasContentDescription("Platinum Card, card ending 71008", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun theListUpdatesInPlaceWhenACardIsRemoved() {
        seed()
        showTab()
        rule.onNodeWithText("Hotel Card", useUnmergedTree = true).assertExists()
        runBlocking { cards.applyCardList("amex", CardList.from(listOf(CardDraft("Platinum Card", "71008")), true), now) }
        rule.waitUntil(2_000) { rule.onAllNodesWithText("Hotel Card", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun cardPageShowsSectionsInOrderWithNotesAndTheList() {
        seed()
        rule.setContent { MileStoneTheme { CardBenefitsScreen(viewModel, platinum, onBack = {}, onGone = {}) } }
        val list = rule.onNodeWithTag("cardBenefitsList")
        listOf("Welcome bonus", "Credits", "Spend trackers", "Other").forEach { list.performScrollToNode(hasText(it)) }
        list.performScrollToNode(hasText("\$4,770 earned · \$70,230 left"))
        list.performScrollToNode(hasText("No reminder for this credit"))
        list.performScrollToNode(hasTestTag("category_OTHER"))
        rule.onNodeWithTag("category_OTHER").performClick()
        list.performScrollToNode(hasText("Mystery Benefit"))
        list.performScrollToNode(hasText("Enrollment required"))
        list.performScrollToNode(hasText("Covers purchases"))
        list.performScrollToNode(hasText("Period: Oct - Dec"))
    }

    @Test
    fun aRemovedCardLeavesItsPage() {
        seed()
        var gone = 0
        rule.setContent { MileStoneTheme { CardBenefitsScreen(viewModel, hotel, onBack = {}, onGone = { gone++ }) } }
        rule.waitForIdle()
        assertEquals(0, gone)
        runBlocking { cards.applyCardList("amex", CardList.from(listOf(CardDraft("Platinum Card", "71008")), true), now) }
        rule.waitUntil(2_000) { gone == 1 }
    }

    private val reminderSetting = object : com.bendourthe.milestone.domain.ReminderSettings {
        val state = kotlinx.coroutines.flow.MutableStateFlow(true)
        override val enabled = state
        override suspend fun setEnabled(enabled: Boolean) { state.value = enabled }
    }

    @Test
    fun withRemindersOnAndNoNotificationPermissionTheTabShowsABanner() {
        seed()
        val vm = BenefitsViewModel(cards, StaticCatalogRepository(), accounts, { now }, ZoneOffset.UTC, Locale.US, reminderSetting)
        rule.setContent { MileStoneTheme { BenefitsScreen(vm, onOpenCard = {}) } }
        rule.onNodeWithTag("reminderPermissionBanner").assertExists()
        reminderSetting.state.value = false
        rule.waitUntil(2_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("reminderPermissionBanner")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theSettingsSwitchTurnsRemindersOff() {
        val vm = BenefitsViewModel(cards, StaticCatalogRepository(), accounts, { now }, ZoneOffset.UTC, Locale.US, reminderSetting)
        val settingsVm = com.bendourthe.milestone.ui.settings.SettingsViewModel(com.bendourthe.milestone.ui.FakeSettings())
        rule.setContent {
            MileStoneTheme {
                val on by vm.remindersEnabled.collectAsStateWithLifecycle()
                com.bendourthe.milestone.ui.settings.SettingsScreen(settingsVm, "0.2.0", benefitReminders = on, onBenefitReminders = vm::setRemindersEnabled)
            }
        }
        rule.onNodeWithText("Benefit reminders").performScrollTo().performClick()
        rule.waitUntil(2_000) { !reminderSetting.state.value }
    }

    @Test
    fun tappingATileOpensThatCard() {
        seed()
        var opened: CardKey? = null
        rule.setContent { MileStoneTheme { BenefitsScreen(viewModel, onOpenCard = { opened = it }) } }
        rule.onNode(hasContentDescription("Hotel Card, card ending 42005", substring = true), useUnmergedTree = true).performClick()
        assertEquals(hotel, opened)
    }

    @Test
    fun categoryCollapseIsIndependentAndTheSortControlChangesThePageState() {
        seed()
        rule.setContent { MileStoneTheme { CardBenefitsScreen(viewModel, platinum, onBack = {}, onGone = {}) } }
        val list = rule.onNodeWithTag("cardBenefitsList")
        list.performScrollToNode(hasTestTag("benefitsCompletedLast"))
        rule.onNodeWithTag("benefitsCompletedLast").performClick().assertIsSelected()
        list.performScrollToNode(hasTestTag("category_OTHER"))
        rule.onNodeWithTag("category_OTHER").performClick()
        list.performScrollToNode(hasText("Mystery Benefit"))
        rule.onNodeWithText("Mystery Benefit").assertExists()
        list.performScrollToNode(hasTestTag("category_CREDITS"))
        rule.onNodeWithTag("category_CREDITS").performClick()
        rule.waitForIdle()
        val page = runBlocking { viewModel.card(platinum).first()!! }
        assertTrue(page.preferences.completedLast)
        assertTrue(com.bendourthe.milestone.domain.BenefitsCategory.CREDITS in page.preferences.collapsed)
        assertFalse(com.bendourthe.milestone.domain.BenefitsCategory.OTHER in page.preferences.collapsed)
        val other = runBlocking { viewModel.card(hotel).first()!! }
        assertFalse(other.preferences.completedLast)
        assertEquals(setOf(com.bendourthe.milestone.domain.BenefitsCategory.OTHER), other.preferences.collapsed)
    }

    @Test
    fun welcomeSetupValidatesInputAndDoesNotChangeIssuerRows() {
        seed()
        rule.setContent { MileStoneTheme { CardBenefitsScreen(viewModel, platinum, onBack = {}, onGone = {}) } }
        rule.onNodeWithTag("cardBenefitsList").performScrollToNode(hasTestTag("welcomeSetup"))
        rule.onNodeWithTag("welcomeSetup").performClick()
        rule.onNodeWithTag("welcomeSpent").performTextInput("100")
        rule.onNodeWithTag("welcomeTarget").performTextInput("0")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter a target greater than zero.").assertExists()
        rule.onNodeWithTag("welcomeTarget").performTextReplacement("500")
        rule.onNodeWithTag("welcomeDeadline").performTextInput("2027-01-01")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        val setup = runBlocking { viewModel.card(platinum).first()!! }.preferences.welcomeBonus!!
        assertEquals(10_000L, setup.spentCents)
        assertEquals(50_000L, setup.targetCents)
        assertEquals(5, runBlocking { cards.observeBenefits(platinum).first() }.size)
    }

    @Test
    fun topAlertSurvivesCollapsedCategoryAndAcknowledgingItDoesNotClearAnotherCard() {
        seed()
        rule.setContent { MileStoneTheme { CardBenefitsScreen(viewModel, platinum, onBack = {}, onGone = {}) } }
        rule.onNodeWithTag("benefitsAlerts").assertExists()
        rule.onNodeWithText("Enrollment required: Purchase Protection").assertExists()
        rule.onNodeWithText("Acknowledge").performClick()
        rule.onNodeWithTag("benefitsAlerts").assertDoesNotExist()
        val first = runBlocking { viewModel.card(platinum).first()!! }
        val second = runBlocking { viewModel.card(hotel).first()!! }
        assertTrue(first.alerts.isEmpty())
        assertTrue(second.alerts.isNotEmpty())
        assertEquals(setOf(com.bendourthe.milestone.domain.BenefitsCategory.OTHER), first.preferences.collapsed)
        assertEquals(5, runBlocking { cards.observeBenefits(platinum).first() }.size)
    }

    @Test
    fun failedAcknowledgementKeepsTheAlertVisibleAndReportsTheSaveFailure() {
        seed()
        val settings = object : com.bendourthe.milestone.domain.BenefitsDisplaySettings {
            override fun observe(card: CardKey) = kotlinx.coroutines.flow.flowOf(com.bendourthe.milestone.domain.BenefitsDisplayPreferences())
            override suspend fun acknowledge(card: CardKey, alertId: String, readGeneration: Long) { throw java.io.IOException("Synthetic unavailable store") }
            override suspend fun setCompletedLast(card: CardKey, completedLast: Boolean) = Unit
            override suspend fun setCollapsed(card: CardKey, category: com.bendourthe.milestone.domain.BenefitsCategory, collapsed: Boolean) = Unit
            override suspend fun setWelcomeBonus(card: CardKey, setup: com.bendourthe.milestone.domain.WelcomeBonusSetup?) = Unit
            override suspend fun recordReadOutcome(card: CardKey, readEpochMs: Long, cardGeneration: Long, sections: Set<BenefitSection>) = Unit
        }
        val failing = BenefitsViewModel(cards, StaticCatalogRepository(), accounts, { now }, ZoneOffset.UTC, Locale.US, displaySettings = settings)
        rule.setContent { MileStoneTheme { CardBenefitsScreen(failing, platinum, onBack = {}, onGone = {}) } }
        rule.onNodeWithText("Acknowledge").performClick()
        rule.onNodeWithTag("benefitsAlerts").assertExists()
        rule.onNodeWithText("Could not save this change. Please try again.").assertIsDisplayed()
        assertEquals(5, runBlocking { cards.observeBenefits(platinum).first() }.size)
    }

}
