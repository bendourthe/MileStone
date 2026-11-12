package com.bendourthe.milestone.ui.benefits

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
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
        rule.onNodeWithText(" …71008", useUnmergedTree = true).assertExists()
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
        listOf("Spend progress", "Credits", "Completed", "Other", "All benefits").forEach { list.performScrollToNode(hasText(it)) }
        list.performScrollToNode(hasText("\$4,770 earned · \$70,230 left"))
        list.performScrollToNode(hasText("No reminder for this credit"))
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

    @Test
    fun tappingATileOpensThatCard() {
        seed()
        var opened: CardKey? = null
        rule.setContent { MileStoneTheme { BenefitsScreen(viewModel, onOpenCard = { opened = it }) } }
        rule.onNode(hasContentDescription("Hotel Card, card ending 42005", substring = true), useUnmergedTree = true).performClick()
        assertEquals(hotel, opened)
    }
}
