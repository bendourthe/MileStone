package com.bendourthe.milestone.sync.extract

/**
 * One deadline for a whole benefits read (ADR-0008, 2026-11-12 clarification). [deadlineAt] is on the caller's
 * clock; every method takes the current time on that clock, so the rules are testable without a WebView.
 *
 * Cut order when time runs short: first a card's second page is skipped, and once one is skipped every later card
 * reads its first page only; then no new card starts, so the cards left are reported unread and keep their stored
 * benefits. Not safe for concurrent use; the source calls it from the main thread only.
 */
class BenefitsReadBudget(private val deadlineAt: Long) {
    private var secondPagesCut = false

    fun timeLeftMs(now: Long): Long = deadlineAt - now

    /** What one page may take: [MAX_PAGE_MS] at most, never past the deadline (at least 1 s, so a page can report). */
    fun pageTimeoutMs(now: Long): Long = (timeLeftMs(now) - MARGIN_MS).coerceIn(1_000L, MAX_PAGE_MS)

    /**
     * Whether the card whose Rewards page starts now may open a second page, given [cardsAfter] cards still waiting
     * after it: the time left must cover this card's first and second pages plus one first page per waiting card.
     * Sticky once false.
     */
    fun allowsSecondPage(now: Long, cardsAfter: Int): Boolean {
        val needed = SECOND_PAGE_RESERVE_MS + (cardsAfter + 1) * FIRST_PAGE_RESERVE_MS
        if (!secondPagesCut && timeLeftMs(now) - MARGIN_MS < needed) secondPagesCut = true
        return !secondPagesCut
    }

    /** Whether another card may start; false ends the read with the remaining cards unread. */
    fun allowsNewCard(now: Long): Boolean = timeLeftMs(now) - MARGIN_MS >= CARD_START_MIN_MS

    companion object {
        const val MARGIN_MS = 2_000L
        const val MAX_PAGE_MS = 20_000L
        const val SECOND_PAGE_RESERVE_MS = 20_000L
        const val FIRST_PAGE_RESERVE_MS = 20_000L
        const val CARD_START_MIN_MS = 10_000L
    }
}
