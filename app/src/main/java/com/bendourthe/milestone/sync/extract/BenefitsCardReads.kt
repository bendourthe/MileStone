package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.sync.BenefitsRead

/**
 * The per-card state of a benefits read (ADR-0008, 2026-11-12 amendment): which drafts each card's pages gave and which
 * sections they covered. A card without an activity page posts its Rewards trackers with `next=viewAll`, then its
 * view-all list joins them. Pure, so every transition is testable without a WebView; not safe for concurrent use.
 */
class BenefitsCardReads {
    /** What the source does after a message. */
    sealed interface Step {
        /** The card is finished; [outcome] is read, listUnread, or mismatch (for the log line). */
        data class Done(val lastDigits: String, val outcome: String, val listCapped: Boolean = false) : Step

        /** Trackers are held; the card's view-all page comes next. */
        data object AwaitingViewAll : Step

        /** The message was dropped; [why] is late or unexpected (for the log line). */
        data class Dropped(val why: String) : Step
    }

    private val drafts = linkedMapOf<String, List<BenefitDraft>>()
    private val sections = linkedMapOf<String, Set<BenefitSection>>()
    private var droppedCount = 0

    /** The card whose view-all page is awaited, or null. */
    var awaitingViewAll: String? = null
        private set

    val benefits: Map<String, List<BenefitDraft>> get() = drafts.toMap()
    val sectionsRead: Map<String, Set<BenefitSection>> get() = sections.toMap()
    val dropped: Int get() = droppedCount

    fun contains(lastDigits: String): Boolean = lastDigits in drafts

    fun onBenefits(message: BridgeMessage.Benefits): Step {
        val waiting = awaitingViewAll
        if (waiting != null) {
            if (message.page != "viewAll") return drop("late")
            awaitingViewAll = null
            if (message.lastDigits != waiting) return Step.Done(waiting, "mismatch")
            val listed = message.drafts.filter { it.section == BenefitSection.LISTED }
            // An empty list is believed only from a page that settled on it; otherwise the stored list stays.
            if (listed.isEmpty() && message.ready?.reason != "stable") return Step.Done(waiting, "listUnread")
            drafts[waiting] = drafts[waiting].orEmpty() + listed
            sections[waiting] = BenefitsRead.ALL_SECTIONS
            return Step.Done(waiting, "read", listCapped = listed.size >= BenefitsReader.MAX_LISTED)
        }
        if (message.page == "viewAll") return drop("unexpected")
        val digits = message.lastDigits
        if (message.page == "rewards") {
            drafts[digits] = message.drafts
            sections[digits] = BenefitsRead.TRACKER_SECTIONS
            if (message.next == "viewAll") {
                awaitingViewAll = digits
                return Step.AwaitingViewAll
            }
            return Step.Done(digits, "read")
        }
        drafts[digits] = message.drafts
        sections[digits] = BenefitsRead.ALL_SECTIONS
        return Step.Done(digits, "read", listCapped = message.drafts.count { it.section == BenefitSection.LISTED } >= BenefitsReader.MAX_LISTED)
    }

    /**
     * The awaited view-all page failed (unreadable, or no message in time): the card keeps its new trackers and its
     * stored list. Null when no view-all page is awaited.
     */
    fun onViewAllFailed(): Step.Done? {
        val waiting = awaitingViewAll ?: return null
        awaitingViewAll = null
        return Step.Done(waiting, "listUnread")
    }

    private fun drop(why: String): Step {
        droppedCount++
        return Step.Dropped(why)
    }
}
