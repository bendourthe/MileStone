package com.bendourthe.milestone.ui.benefits

import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.assignBenefitKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class BenefitsViewModelTest {
    private val zone = ZoneOffset.UTC
    private val locale = Locale.US
    private val now = LocalDate.of(2026, 11, 12).atStartOfDay(zone).toInstant().toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L
    private val key = CardKey("amex", "71008")
    private val today = LocalDate.of(2026, 11, 12)

    private fun card(benefitsRead: Long?, listRead: Long? = benefitsRead) = Card("amex", "71008", "Platinum Card", 0, listRead, benefitsRead)

    private fun credit(title: String, left: Long?, end: LocalDate? = null, done: Boolean = false, label: String? = "Oct - Dec") =
        assignBenefitKeys(key, listOf(BenefitDraft(BenefitSection.CREDIT_EARNED, title, 0, left, left, label, completed = done, periodEnd = end))).single()

    @Test
    fun nextCreditIsTheOpenCreditEndingFirst() {
        val benefits = listOf(
            credit("Late", 5_000, LocalDate.of(2026, 12, 31)),
            credit("Soon", 1_500, LocalDate.of(2026, 11, 30)),
            credit("Used", 0, LocalDate.of(2026, 11, 15)),
            credit("Done", 2_000, LocalDate.of(2026, 11, 14), done = true),
        )
        assertEquals("Soon: \$15 left, ends Nov 30", BenefitsViewModel.nextCredit(benefits, today, locale))
    }

    @Test
    fun nextCreditFallsBackToThePeriodLabelOrSaysNone() {
        assertEquals("Shopping: \$50 left (Oct - Dec)", BenefitsViewModel.nextCredit(listOf(credit("Shopping", 5_000)), today, locale))
        assertEquals("No open credits", BenefitsViewModel.nextCredit(listOf(credit("Used", 0)), today, locale))
        assertEquals("No open credits", BenefitsViewModel.nextCredit(emptyList(), today, locale))
    }

    @Test
    fun aCreditWhosePeriodEndedIsNotTheNextCredit() {
        val benefits = listOf(credit("Ended", 1_000, LocalDate.of(2026, 10, 31)), credit("Current", 2_000, LocalDate.of(2026, 12, 31)))
        assertEquals("Current: \$20 left, ends Dec 31", BenefitsViewModel.nextCredit(benefits, today, locale))
        assertEquals("No open credits", BenefitsViewModel.nextCredit(benefits.take(1), today, locale))
    }

    @Test
    fun staleNoteForAFailedReadOrOldBenefitsOnly() {
        assertNull(BenefitsViewModel.staleNote(card(now - day), now, zone, locale))
        assertEquals("Not updated since Nov 2", BenefitsViewModel.staleNote(card(now - 10 * day), now, zone, locale))
        assertEquals("Not updated since Nov 11", BenefitsViewModel.staleNote(card(now - day, listRead = now), now, zone, locale))
        assertEquals("Benefits not read yet", BenefitsViewModel.staleNote(card(null), now, zone, locale))
    }

    @Test
    fun itemsShowProgressAmountsPeriodAndNotes() {
        val spend = assignBenefitKeys(key, listOf(BenefitDraft(BenefitSection.SPEND_PROGRESS, "Lounge", 477_000, 7_023_000, 7_500_000, null, completed = false))).single()
        val item = BenefitsViewModel.item(spend, locale)
        assertEquals(0.0636f, item.progress!!, 0.0001f)
        assertEquals("\$4,770 earned · \$70,230 left", item.amounts)
        assertFalse(item.noReminder)

        val noAmounts = assignBenefitKeys(key, listOf(BenefitDraft(BenefitSection.OTHER, "Mystery", null, null, null, null, completed = false))).single()
        BenefitsViewModel.item(noAmounts, locale).let {
            assertNull(it.progress)
            assertNull(it.amounts)
        }

        assertTrue(BenefitsViewModel.item(credit("Unknown period", 500, end = null), locale).noReminder)
        assertFalse(BenefitsViewModel.item(credit("Known period", 500, end = LocalDate.of(2026, 12, 31)), locale).noReminder)
        assertEquals("\$5.50 left", BenefitsViewModel.item(credit("Cents", 550).copy(earnedCents = null), locale).amounts)
    }

    @Test
    fun pageGroupsDisplayCategoriesWithoutLosingStoredDetails() {
        val drafts = listOf(
            BenefitDraft(BenefitSection.LISTED, "Purchase Protection", null, null, null, null, false, enrollment = Enrollment.ENROLLED, issuerKey = "pp"),
            BenefitDraft(BenefitSection.OTHER, "Other thing", null, null, null, null, false),
            BenefitDraft(BenefitSection.COMPLETED, "Done credit", 2_000, 0, 2_000, "Nov", true),
            BenefitDraft(BenefitSection.CREDIT_EARNED, "Credit", 0, 5_000, 5_000, "Oct - Dec", false),
            BenefitDraft(BenefitSection.SPEND_PROGRESS, "Lounge", 1, 9, 10, null, false),
        )
        val page = BenefitsViewModel.page(card(now), assignBenefitKeys(key, drafts), now, zone, locale)
        assertEquals(listOf("Welcome bonus", "Credits", "Spend trackers", "Other"), page.sections.map { it.title })
        assertEquals("Enrolled", page.sections.last().items.single { it.title == "Purchase Protection" }.enrollment)
        assertEquals(5, page.sections.sumOf { it.items.size })
        assertTrue(page.sections.single { it.title == "Credits" }.items.any { it.title == "Done credit" && it.completed })
        assertEquals("…71008", page.digitsLabel)
    }

    @Test
    fun completedCreditsKeepFullProgressAndTheirKnownTargetWithoutChangingIssuerRows() {
        val original = credit("Example hotel credit", null, done = true).copy(earnedCents = null, targetCents = 20_000)
        val ui = BenefitsViewModel.item(original, locale)
        assertEquals(1f, ui.progress!!, 0f)
        assertEquals("\$200/\$200", ui.amounts)
        assertTrue(ui.inlineProgressAmounts)
        assertNull(original.earnedCents)
        assertNull(original.remainingCents)

        val titleOnly = original.copy(title = "\$200 Example Hotel Statement Credit Benefit", targetCents = null)
        assertEquals("\$200/\$200", BenefitsViewModel.item(titleOnly, locale).amounts)
        val unknown = titleOnly.copy(title = "Example Hotel Credit")
        assertEquals(1f, BenefitsViewModel.item(unknown, locale).progress!!, 0f)
        assertNull(BenefitsViewModel.item(unknown, locale).amounts)
        assertNull(BenefitsViewModel.item(titleOnly.copy(title = "\$200 Credit after \$500 spend"), locale).amounts)
        listOf("\$200.5 Credit", "\$1,00 Credit", "\$200.500 Credit").forEach { title ->
            assertNull(BenefitsViewModel.item(titleOnly.copy(title = title), locale).amounts)
        }
        assertEquals("\$200.50/\$200.50", BenefitsViewModel.item(titleOnly.copy(title = "\$200.50 Credit"), locale).amounts)
        assertNull(BenefitsViewModel.item(titleOnly.copy(section = BenefitSection.OTHER, title = "\$200 Mystery"), locale).amounts)
    }

    @Test
    fun unfinishedCreditsUseEarnedOverTargetAndKeepTheirActualProgress() {
        val credit = credit("Example credit", 14_450).copy(earnedCents = 5_550, targetCents = 20_000)
        val ui = BenefitsViewModel.item(credit, locale)
        assertEquals(0.2775f, ui.progress!!, 0.0001f)
        assertEquals("\$55.50/\$200", ui.amounts)
        assertTrue(ui.inlineProgressAmounts)
        assertFalse(BenefitsViewModel.item(credit.copy(section = BenefitSection.SPEND_PROGRESS), locale).inlineProgressAmounts)
    }

}
