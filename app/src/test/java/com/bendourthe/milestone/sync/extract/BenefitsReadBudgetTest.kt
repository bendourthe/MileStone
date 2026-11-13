package com.bendourthe.milestone.sync.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [BenefitsReadBudget] through a simulated read of 4 cards that each offer a second page, on a fake clock,
 * the way `HeadlessWebViewBenefitsSource` calls it (ADR-0008, 2026-11-12 clarification).
 */
class BenefitsReadBudgetTest {
    private data class Outcome(val endAt: Long, val secondPages: List<Boolean>, val unread: Int)

    /**
     * [firstPageMs] and [secondPageMs] are how long each page would take if nothing cut it; a page never runs past
     * its kind's maximum (20 s Rewards including late links, 20 s activity) or the timeout the budget gives it.
     */
    private fun simulate(budgetMs: Long, startAt: Long, firstPageMs: Long, secondPageMs: Long, cards: Int = 4): Outcome {
        val budget = BenefitsReadBudget(deadlineAt = budgetMs - SCRIPT_MARGIN_MS)
        var now = startAt
        val secondPages = mutableListOf<Boolean>()
        for (index in 0 until cards) {
            if (index > 0 && !budget.allowsNewCard(now)) return Outcome(now, secondPages, cards - index)
            val second = budget.allowsSecondPage(now, cardsAfter = cards - index - 1)
            now += minOf(firstPageMs, REWARDS_CAP_MS, budget.pageTimeoutMs(now))
            if (second) now += minOf(secondPageMs, ACTIVITY_CAP_MS, budget.pageTimeoutMs(now))
            secondPages += second
        }
        return Outcome(now, secondPages, 0)
    }

    @Test
    fun theMeasuredWorstCaseReadsEveryCardsSecondPageWithinTheBudget() {
        // Live diagnostics: about 6 s for the card list, at most 12.4 s per page.
        val outcome = simulate(BUDGET_MS, startAt = 6_000, firstPageMs = 6_000, secondPageMs = 12_400)
        assertEquals(listOf(true, true, true, true), outcome.secondPages)
        assertEquals(0, outcome.unread)
        assertTrue("ended at ${outcome.endAt}", outcome.endAt <= BUDGET_MS - SCRIPT_MARGIN_MS)
    }

    @Test
    fun slowPagesCutLaterSecondPagesFirstAndStillEndBeforeTheDeadline() {
        val outcome = simulate(BUDGET_MS, startAt = 6_000, firstPageMs = 60_000, secondPageMs = 60_000)
        assertTrue("ended at ${outcome.endAt}", outcome.endAt <= BUDGET_MS - SCRIPT_MARGIN_MS)
        assertEquals("every card is still read", 0, outcome.unread)
        assertTrue("some second page was cut: ${outcome.secondPages}", false in outcome.secondPages)
        assertOnceCutStaysCut(outcome.secondPages)
        assertTrue("the first card keeps its second page", outcome.secondPages.first())
    }

    @Test
    fun aLateStartReservesTheFullRewardsWaitBeforeOpeningAnySecondPage() {
        val outcome = simulate(BUDGET_MS, startAt = 30_000, firstPageMs = 20_000, secondPageMs = 20_000)
        assertEquals("second pages go before a card is left unread", listOf(false, false, false, false), outcome.secondPages)
        assertEquals(0, outcome.unread)
        assertEquals(110_000L, outcome.endAt)
    }

    @Test
    fun withTooLittleTimeSecondPagesGoBeforeAnyCardIsLeftUnread() {
        val outcome = simulate(budgetMs = 40_000, startAt = 6_000, firstPageMs = 60_000, secondPageMs = 60_000)
        assertTrue("ended at ${outcome.endAt}", outcome.endAt <= 40_000 - SCRIPT_MARGIN_MS)
        assertTrue("some card is unread", outcome.unread > 0)
        assertTrue("no card opened a second page: ${outcome.secondPages}", outcome.secondPages.none { it })
    }

    @Test
    fun aPageTimeoutNeverPassesTheDeadlineOrTheMaximum() {
        val budget = BenefitsReadBudget(deadlineAt = 100_000)
        assertEquals(BenefitsReadBudget.MAX_PAGE_MS, budget.pageTimeoutMs(now = 0))
        assertEquals(8_000L, budget.pageTimeoutMs(now = 90_000))
        assertEquals(1_000L, budget.pageTimeoutMs(now = 99_500))
    }

    private fun assertOnceCutStaysCut(secondPages: List<Boolean>) {
        val firstCut = secondPages.indexOf(false)
        assertTrue("a later card opened a second page after a cut: $secondPages", secondPages.drop(firstCut).none { it })
    }

    private companion object {
        const val BUDGET_MS = 120_000L
        const val SCRIPT_MARGIN_MS = 2_000L
        const val REWARDS_CAP_MS = 20_000L
        const val ACTIVITY_CAP_MS = 20_000L
    }
}
