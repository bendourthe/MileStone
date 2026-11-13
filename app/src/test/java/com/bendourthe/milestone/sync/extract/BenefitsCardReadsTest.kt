package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.sync.BenefitsRead
import com.bendourthe.milestone.sync.extract.BenefitsCardReads.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-card state table of ADR-0008's 2026-11-12 amendment, one transition per test. */
class BenefitsCardReadsTest {
    private val reads = BenefitsCardReads()

    private fun tracker(title: String) = BenefitDraft(BenefitSection.CREDIT_EARNED, title, 0, 5_000, 5_000, null, completed = false)
    private fun listed(key: String) = BenefitDraft(BenefitSection.LISTED, key, null, null, null, null, completed = false, issuerKey = key)
    private fun ready(kind: String, reason: String = "stable") = PageDiagnostics(kind, 0, 0, 0, true, false, true, false, reason)

    private fun rewards(digits: String, next: String? = null) =
        BridgeMessage.Benefits(digits, "rewards", listOf(tracker("Hotel Credit")), ready("rewards"), next)

    private fun viewAll(digits: String, vararg keys: String, reason: String = "stable") =
        BridgeMessage.Benefits(digits, "viewAll", listOf(tracker("Repeated tracker")) + keys.map(::listed), ready("viewAll", reason))

    @Test
    fun aRewardsReadWithoutNextCompletesTheCardForTheTrackerSectionsOnly() {
        assertEquals(Step.Done("42005", "read"), reads.onBenefits(rewards("42005")))
        assertEquals(BenefitsRead.TRACKER_SECTIONS, reads.sectionsRead["42005"])
    }

    @Test
    fun anActivityReadCoversEverySection() {
        val step = reads.onBenefits(BridgeMessage.Benefits("71008", "activity", listOf(tracker("A"), listed("lounge")), ready("activity")))
        assertEquals(Step.Done("71008", "read"), step)
        assertEquals(BenefitsRead.ALL_SECTIONS, reads.sectionsRead["71008"])
    }

    @Test
    fun theViewAllListJoinsTheHeldTrackers() {
        assertEquals(Step.AwaitingViewAll, reads.onBenefits(rewards("42005", next = "viewAll")))
        assertEquals("42005", reads.awaitingViewAll)
        assertEquals(BenefitsRead.TRACKER_SECTIONS, reads.sectionsRead["42005"])

        assertEquals(Step.Done("42005", "read"), reads.onBenefits(viewAll("42005", "lounge", "status")))
        // The view-all page's own trackers are ignored: the Rewards page supplied them.
        assertEquals(listOf("Hotel Credit", "lounge", "status"), reads.benefits.getValue("42005").map { it.title })
        assertEquals(BenefitsRead.ALL_SECTIONS, reads.sectionsRead["42005"])
        assertNull(reads.awaitingViewAll)
    }

    @Test
    fun aViewAllPageForAnotherCardIsDroppedAndTheListStaysUnread() {
        reads.onBenefits(rewards("42005", next = "viewAll"))
        assertEquals(Step.Done("42005", "mismatch"), reads.onBenefits(viewAll("33006", "lounge")))
        assertEquals(BenefitsRead.TRACKER_SECTIONS, reads.sectionsRead["42005"])
        assertFalse(reads.contains("33006"))
    }

    @Test
    fun aLateRewardsMessageDuringTheViewAllStepIsDroppedAndCounted() {
        reads.onBenefits(rewards("42005", next = "viewAll"))
        assertEquals(Step.Dropped("late"), reads.onBenefits(rewards("42005")))
        assertEquals("42005", reads.awaitingViewAll)
        assertEquals(1, reads.dropped)
        assertEquals(Step.Done("42005", "read"), reads.onBenefits(viewAll("42005", "lounge")))
    }

    @Test
    fun aViewAllMessageOutsideTheViewAllStepIsDropped() {
        assertEquals(Step.Dropped("unexpected"), reads.onBenefits(viewAll("42005", "lounge")))
        assertFalse(reads.contains("42005"))
    }

    @Test
    fun anEmptyListReplacesTheStoredOneOnlyFromASettledPage() {
        reads.onBenefits(rewards("42005", next = "viewAll"))
        assertEquals(Step.Done("42005", "listUnread"), reads.onBenefits(viewAll("42005", reason = "cap")))
        assertEquals(BenefitsRead.TRACKER_SECTIONS, reads.sectionsRead["42005"])

        reads.onBenefits(rewards("33006", next = "viewAll"))
        assertEquals(Step.Done("33006", "read"), reads.onBenefits(viewAll("33006", reason = "stable")))
        assertEquals(BenefitsRead.ALL_SECTIONS, reads.sectionsRead["33006"])
    }

    @Test
    fun aFailedViewAllPageLeavesTheListUnreadButTheCardRead() {
        assertNull(reads.onViewAllFailed())
        reads.onBenefits(rewards("42005", next = "viewAll"))
        assertEquals(Step.Done("42005", "listUnread"), reads.onViewAllFailed())
        assertTrue(reads.contains("42005"))
        assertEquals(BenefitsRead.TRACKER_SECTIONS, reads.sectionsRead["42005"])
    }

    @Test
    fun aFullListIsReportedAsACapHit() {
        reads.onBenefits(rewards("42005", next = "viewAll"))
        val keys = (1..BenefitsReader.MAX_LISTED).map { "k$it" }.toTypedArray()
        assertEquals(Step.Done("42005", "read", listCapped = true), reads.onBenefits(viewAll("42005", *keys)))
    }
}
