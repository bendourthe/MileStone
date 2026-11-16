package com.bendourthe.milestone.ui.search

import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.ui.benefits.*
import org.junit.Assert.*
import org.junit.Test

class SearchRevealIndexTest {
    private val card = CardKey("amex", "1008")
    private val revision = SearchRevision()
    private fun item(key: String) = BenefitUi(key, key, null, null, null, false, false, null, null)
    private val page = CardPageUi("Fixture", "", null, listOf(
        SectionUi(BenefitsCategory.WELCOME_BONUS, "Welcome bonus", emptyList()),
        SectionUi(BenefitsCategory.CREDITS, "Credits", listOf(item("credit"))),
        SectionUi(BenefitsCategory.SPEND_TRACKERS, "Spend trackers", listOf(item("spend"))),
        SectionUi(BenefitsCategory.OTHER, "Other", listOf(item("other")))
    ), preferences = BenefitsDisplayPreferences(collapsed = setOf(BenefitsCategory.CREDITS, BenefitsCategory.OTHER)))
    private val target = SearchTarget.BenefitTarget(card, "credit", BenefitsCategory.CREDITS, revision)

    @Test fun onlyTheTargetCollapsedCategoryContributesItsRows() {
        val slots = benefitRevealSlots(page, target.category)
        assertEquals(listOf("card", "category:WELCOME_BONUS", "welcomeSetup", "category:CREDITS", "credit",
            "category:SPEND_TRACKERS", "spend", "category:OTHER"), slots)
        assertEquals(4, benefitRevealIndex(page, target))
        assertFalse(slots.contains("other"))
        assertEquals(setOf(BenefitsCategory.CREDITS, BenefitsCategory.OTHER), page.preferences.collapsed)
    }

    @Test fun optionalPanelsAndSharedFreshnessAlertsHaveExactSlots() {
        val panels = page.copy(settingsError = "Cannot save", alerts = listOf(BenefitAlertUi("alert", 1, "An alert")), listFreshnessNote = "Old listed items")
        assertEquals(7, benefitRevealIndex(panels, target))
        val combined = panels.copy(alerts = listOf(BenefitAlertUi("alert", 1, "Old listed items")))
        assertEquals(6, benefitRevealIndex(combined, target))
        assertFalse(benefitRevealSlots(combined, target.category).contains("listFreshness"))
    }

    @Test fun missingIdentityOrWrongCategoryNeverFallsBackToAnotherRow() {
        assertNull(benefitRevealIndex(page, target.copy(benefitKey = "missing")))
        assertNull(benefitRevealIndex(page, target.copy(category = BenefitsCategory.OTHER)))
        val welcome = page.copy(sections = listOf(SectionUi(BenefitsCategory.WELCOME_BONUS, "Welcome bonus", listOf(item("local:welcome")))),
            preferences = page.preferences.copy(collapsed = BenefitsCategory.entries.toSet()))
        val local = target.copy(benefitKey = "local:welcome", category = BenefitsCategory.WELCOME_BONUS)
        assertEquals(listOf("card", "category:WELCOME_BONUS", "welcomeSetup", "local:welcome"), benefitRevealSlots(welcome, local.category))
        assertEquals(3, benefitRevealIndex(welcome, local))
    }
}
