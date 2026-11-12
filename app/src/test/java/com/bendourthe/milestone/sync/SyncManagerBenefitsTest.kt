package com.bendourthe.milestone.sync

import android.webkit.WebView
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FakeCatalog
import com.bendourthe.milestone.domain.ReminderRecord
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.domain.testProgram
import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeCards
import com.bendourthe.milestone.ui.FakeSessions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** The separate benefits read after an Amex balance (plan Phase 3, ADR-0008), with fake sources. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncManagerBenefitsTest {
    private val spec = ExtractionSpec.Selector(SelectorStrategy.CSS, ".points", Regex("/account"), Regex("/login"))
    private val amex = testProgram("amex", capability = SyncCapability.Automatic(spec)).copy(benefits = StaticCatalogRepository.AMEX_BENEFITS)
    private val plain = testProgram("hotel", capability = SyncCapability.Automatic(spec))
    private val catalog = FakeCatalog(listOf(amex, plain))
    private val accounts = FakeAccounts()
    private val sessions = FakeSessions()
    private val cards = FakeCards()
    private val gold = CardKey("amex", "71008")
    private val hotel = CardKey("amex", "42005")

    private val events = mutableListOf<String>()
    private var concurrentReads = 0
    private var maxConcurrentReads = 0
    private var benefits: suspend (String) -> BenefitsRead = { read(complete = true, "71008", "42005") }

    private object Host : WebViewHost {
        override suspend fun <T> withAttachedWebView(block: suspend (WebView) -> T): T = error("unused by the fake sources")
    }

    private fun TestScope.manager() = SyncManager(
        accounts = accounts,
        sessions = sessions,
        catalog = catalog,
        source = { program, _, _, _ ->
            events += "balance:${program.programId}"
            SyncResult.Success(5_000)
        },
        scope = backgroundScope,
        host = { Host },
        clock = { 1_000L + testScheduler.currentTime },
        benefitsSource = { program, _, _ ->
            // The balance must already be saved when the benefits read starts.
            val saved = accounts.accounts.value.filter { it.programId == program.programId }.map { it.balance }
            events += "benefits:${program.programId}:balance=${saved.joinToString()}"
            concurrentReads++
            maxConcurrentReads = maxOf(maxConcurrentReads, concurrentReads)
            try {
                benefits(program.programId)
            } finally {
                concurrentReads--
            }
        },
        cards = cards,
    )

    private fun read(complete: Boolean, vararg digits: String, failed: Set<String> = emptySet(), stop: BenefitsStop? = null) = BenefitsRead(
        cards = CardList.from(digits.map { CardDraft("Card $it", it) }, complete),
        benefits = digits.filter { it !in failed }.associateWith { listOf(credit("Credit $it")) },
        failed = failed,
        stop = stop,
    )

    private fun credit(title: String) = BenefitDraft(BenefitSection.CREDIT_EARNED, title, 0, 5_000, 5_000, "Oct - Dec", completed = false)

    private suspend fun connected(id: String) {
        accounts.addAccount(id, AccountStatus.CONNECTED, 1)
        sessions.markConnected(id, 1)
    }

    private suspend fun titles(key: CardKey) = cards.observeBenefits(key).first().map { it.title }

    @Test
    fun benefitsReadRunsAfterTheSavedBalanceAndStoresEveryCard() = runTest {
        connected("amex")
        manager().refreshAll(RefreshReason.ON_START).join()
        advanceUntilIdle()

        assertEquals(listOf("balance:amex", "benefits:amex:balance=5000"), events)
        assertEquals(listOf("71008", "42005"), cards.observeCards("amex").first().map { it.lastDigits })
        assertEquals(listOf("Credit 71008"), titles(gold))
        assertEquals(listOf("Credit 42005"), titles(hotel))
    }

    @Test
    fun keepAliveAndProgramsWithoutABenefitsPageNeverReadBenefits() = runTest {
        connected("amex")
        connected("hotel")
        manager().refreshAll(RefreshReason.KEEP_ALIVE).join()
        advanceUntilIdle()

        assertEquals(setOf("balance:amex", "balance:hotel"), events.toSet())
        assertTrue(cards.cards.value.isEmpty())
    }

    @Test
    fun connectAndTheVisibleSignInAlsoStartABenefitsRead() = runTest {
        val manager = manager()
        manager.connect("amex")
        // The read runs on the manager's background scope, which advanceUntilIdle does not wait for.
        runCurrent()
        assertTrue("events: $events", events.any { it.startsWith("benefits:amex") })

        events.clear()
        manager.applyInteractive("amex", SyncResult.Success(6_000))
        runCurrent()
        assertEquals(listOf("benefits:amex:balance=6000"), events)
    }

    @Test
    fun twoBenefitsReadsForOneProgramNeverRunAtOnce() = runTest {
        connected("amex")
        benefits = { delay(10_000); read(complete = true, "71008") }
        val manager = manager()
        manager.refresh("amex", RefreshReason.MANUAL).join()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, concurrentReads)
        // A second saved balance while the first read still runs does not start another read.
        manager.refresh("amex", RefreshReason.SCHEDULED).join()
        advanceUntilIdle()

        assertEquals(1, maxConcurrentReads)
        assertEquals(1, events.count { it.startsWith("benefits:") })
    }

    @Test
    fun aReadPastItsBudgetWritesNothingAndKeepsStoredBenefits() = runTest {
        connected("amex")
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Gold", "71008")), true), 1)
        cards.applyCardBenefits(gold, com.bendourthe.milestone.domain.assignBenefitKeys(gold, listOf(credit("Old"))), 1)
        benefits = { delay(SyncManager.BENEFITS_TIMEOUT_MS * 2); read(complete = true, "42005") }
        manager().refreshAll(RefreshReason.ON_START).join()
        advanceUntilIdle()

        assertEquals(listOf("71008"), cards.observeCards("amex").first().map { it.lastDigits })
        assertEquals(listOf("Old"), titles(gold))
    }

    @Test
    fun aSignedOutBenefitsPageKeepsStoredBenefits() = runTest {
        connected("amex")
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Gold", "71008")), true), 1)
        cards.applyCardBenefits(gold, com.bendourthe.milestone.domain.assignBenefitKeys(gold, listOf(credit("Old"))), 1)
        benefits = { BenefitsRead(null, emptyMap(), stop = BenefitsStop.SIGNED_OUT) }
        manager().refreshAll(RefreshReason.ON_START).join()
        advanceUntilIdle()

        assertEquals(listOf("Old"), titles(gold))
    }

    @Test
    fun cancellingTheProgramCancelsItsBenefitsReadAndWritesNothing() = runTest {
        connected("amex")
        benefits = { delay(30_000); read(complete = true, "71008") }
        val manager = manager()
        manager.refreshAll(RefreshReason.ON_START).join()
        runCurrent()
        assertEquals(1, concurrentReads)
        manager.stop("amex")
        advanceUntilIdle()

        assertTrue(cards.cards.value.isEmpty())
        assertEquals(0, concurrentReads)
    }

    @Test
    fun aCardMissingFromALaterCompleteListIsRemovedWithItsBenefitsAndRecords() = runTest {
        connected("amex")
        val manager = manager()
        manager.applyBenefits("amex", read(complete = true, "71008", "42005"))
        cards.recordReminder(ReminderRecord(hotel, "k", LocalDate.of(2026, 12, 31), 1))

        manager.applyBenefits("amex", read(complete = true, "71008"))

        assertEquals(listOf("71008"), cards.observeCards("amex").first().map { it.lastDigits })
        assertTrue(titles(hotel).isEmpty())
        assertFalse(cards.reminderSent("k", LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun incompleteEmptyAndDuplicateListsRemoveNothing() = runTest {
        connected("amex")
        val manager = manager()
        manager.applyBenefits("amex", read(complete = true, "71008", "42005"))

        manager.applyBenefits("amex", read(complete = false, "71008"))
        manager.applyBenefits("amex", read(complete = true))
        manager.applyBenefits("amex", read(complete = true, "71008", "71008"))
        manager.applyBenefits("amex", BenefitsRead(null, emptyMap(), stop = BenefitsStop.BUDGET))

        assertEquals(listOf("71008", "42005"), cards.observeCards("amex").first().map { it.lastDigits }.sortedDescending())
    }

    @Test
    fun aFailedCardKeepsItsDataWhileOthersUpdate() = runTest {
        connected("amex")
        val manager = manager()
        manager.applyBenefits("amex", read(complete = true, "71008", "42005"))
        val before = cards.observeCards("amex").first().single { it.lastDigits == "42005" }.benefitsReadEpochMs

        advanceTimeBy(60_000)
        manager.applyBenefits(
            "amex",
            BenefitsRead(
                cards = CardList.from(listOf(CardDraft("Gold", "71008"), CardDraft("Hotel", "42005")), true),
                benefits = mapOf("71008" to listOf(credit("New gold credit"))),
                failed = setOf("42005"),
            ),
        )

        assertEquals(listOf("New gold credit"), titles(gold))
        assertEquals(listOf("Credit 42005"), titles(hotel))
        assertEquals(before, cards.observeCards("amex").first().single { it.lastDigits == "42005" }.benefitsReadEpochMs)
    }

    @Test
    fun applyingAReadFillsEachCreditsPeriodEndFromItsLabel() = runTest {
        connected("amex")
        manager().applyBenefits("amex", read(complete = true, "71008"))
        val stored = cards.observeBenefits(gold).first().single()
        assertEquals(com.bendourthe.milestone.domain.PeriodLength.QUARTERLY, stored.periodLength)
        assertEquals(12, stored.periodEnd?.monthValue)
        assertEquals(31, stored.periodEnd?.dayOfMonth)
    }

    @Test
    fun clearCardsAtSignOutDeletesCardsButKeepsTheAccount() = runTest {
        connected("amex")
        val manager = manager()
        manager.applyBenefits("amex", read(complete = true, "71008"))
        manager.clearCards("amex")

        assertTrue(cards.cards.value.isEmpty())
        assertEquals(listOf("amex"), accounts.accounts.value.map { it.programId })
    }
}
