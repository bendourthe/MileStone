package com.bendourthe.milestone.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.ReminderRecord
import com.bendourthe.milestone.domain.assignBenefitKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomCardRepositoryTest {
    private lateinit var db: MileStoneDatabase
    private lateinit var accounts: RoomAccountRepository
    private lateinit var cards: RoomCardRepository

    private val gold = CardKey("amex", "1008")
    private val platinum = CardKey("amex", "21005")
    private val periodEnd = LocalDate.of(2026, 12, 31)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MileStoneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accounts = RoomAccountRepository(db)
        cards = RoomCardRepository(db)
        accounts.addAccount("amex", AccountStatus.CONNECTED, 1)
        cards.applyCardList("amex", list(complete = true, "Gold Card" to "1008", "Platinum Card" to "21005"), 10)
        cards.applyCardBenefits(gold, benefits(gold, "Dining Credit", "Uber Cash"), 11)
        cards.recordReminder(ReminderRecord(gold, benefitKey(gold, "Dining Credit"), periodEnd, 12))
    }

    @After
    fun tearDown() = db.close()

    private fun list(complete: Boolean, vararg entries: Pair<String, String>) =
        CardList.from(entries.map { CardDraft(it.first, it.second) }, readComplete = complete)

    private fun benefits(card: CardKey, vararg titles: String) = assignBenefitKeys(
        card,
        titles.map { BenefitDraft(BenefitSection.CREDIT_EARNED, it, 1000, 500, 1500, "Jul - Dec", completed = false) },
    )

    private fun benefitKey(card: CardKey, title: String) = benefits(card, title).single().stableKey

    private suspend fun lastDigits() = cards.observeCards("amex").first().map { it.lastDigits }

    @Test
    fun cardListStoresCardsInPickerOrder() = runTest {
        val stored = cards.observeCards("amex").first()
        assertEquals(listOf("1008", "21005"), stored.map { it.lastDigits })
        assertEquals(listOf("Gold Card", "Platinum Card"), stored.map { it.name })
        assertEquals(listOf(10L, 10L), stored.map { it.cardListReadEpochMs })
        assertEquals(listOf(11L, null), stored.map { it.benefitsReadEpochMs })
    }

    @Test
    fun renamedCardKeepsItsBenefitsAndReminderRecords() = runTest {
        cards.applyCardList("amex", list(complete = true, "Gold Card® Rose" to "1008", "Platinum Card" to "21005"), 20)

        val renamed = cards.observeCards("amex").first().first()
        assertEquals("Gold Card Rose", renamed.name)
        assertEquals(11L, renamed.benefitsReadEpochMs)
        assertEquals(listOf("Dining Credit", "Uber Cash"), cards.observeBenefits(gold).first().map { it.title })
        assertTrue(cards.reminderSent(benefitKey(gold, "Dining Credit"), periodEnd))
    }

    @Test
    fun completeListRemovesMissingCardWithBenefitsAndReminderRecords() = runTest {
        cards.applyCardList("amex", list(complete = true, "Platinum Card" to "21005"), 20)

        assertEquals(listOf("21005"), lastDigits())
        assertEquals(emptyList<Any>(), cards.observeBenefits(gold).first())
        assertFalse(cards.reminderSent(benefitKey(gold, "Dining Credit"), periodEnd))
    }

    @Test
    fun incompleteEmptyOrDuplicateListsRemoveNothing() = runTest {
        cards.applyCardList("amex", list(complete = false, "Platinum Card" to "21005"), 20)
        cards.applyCardList("amex", list(complete = true), 21)
        cards.applyCardList("amex", list(complete = true, "Platinum Card" to "21005", "Gold A" to "7777", "Gold B" to "7777"), 22)
        cards.applyCardList("amex", list(complete = true, "Platinum Card" to "21005", "Bad" to "12"), 23)

        assertEquals(listOf("1008", "21005"), lastDigits().sorted())
        assertEquals(2, cards.observeBenefits(gold).first().size)
    }

    @Test
    fun removingTheAccountCascadesToCardsBenefitsAndReminderRecords() = runTest {
        accounts.removeAccount("amex")

        assertEquals(emptyList<String>(), lastDigits())
        assertEquals(emptyList<Any>(), cards.observeBenefits(gold).first())
        assertFalse(cards.reminderSent(benefitKey(gold, "Dining Credit"), periodEnd))
        assertEquals(emptyMap<Any, Any>(), cards.allCardsWithBenefits())
    }

    @Test
    fun deleteProgramCardsKeepsTheAccount() = runTest {
        cards.deleteProgramCards("amex")

        assertEquals(emptyList<String>(), lastDigits())
        assertFalse(cards.reminderSent(benefitKey(gold, "Dining Credit"), periodEnd))
        assertEquals(listOf("amex"), accounts.observeAccounts().first().map { it.programId })
    }

    @Test
    fun cardBenefitsReplaceOnlyThatCard() = runTest {
        cards.applyCardBenefits(platinum, benefits(platinum, "Airline Fee Credit"), 30)
        cards.applyCardBenefits(gold, benefits(gold, "Resy Credit"), 31)

        assertEquals(listOf("Resy Credit"), cards.observeBenefits(gold).first().map { it.title })
        assertEquals(listOf("Airline Fee Credit"), cards.observeBenefits(platinum).first().map { it.title })
        assertTrue("records are keyed by card, not by the replaced rows", cards.reminderSent(benefitKey(gold, "Dining Credit"), periodEnd))
        assertEquals(listOf(31L, 30L), cards.observeCards("amex").first().map { it.benefitsReadEpochMs })
    }

    @Test
    fun benefitsAreOrderedBySectionThenPage() = runTest {
        val drafts = listOf(
            BenefitDraft(BenefitSection.COMPLETED, "Done", null, null, null, null, completed = true),
            BenefitDraft(BenefitSection.OTHER, "Misc", null, null, null, null, completed = false),
            BenefitDraft(BenefitSection.CREDIT_EARNED, "Credit B", 0, 100, 100, "Nov", completed = false),
            BenefitDraft(BenefitSection.SPEND_PROGRESS, "Lounge", 100, 900, 1000, null, completed = false),
            BenefitDraft(BenefitSection.CREDIT_EARNED, "Credit A", 0, 100, 100, "Nov", completed = false),
            BenefitDraft(
                BenefitSection.LISTED, "Purchase Protection", null, null, null, null, completed = false,
                description = "Covers eligible purchases", enrollment = Enrollment.ENROLLED, issuerKey = "purchase-protection",
            ),
        )
        cards.applyCardBenefits(gold, assignBenefitKeys(gold, drafts.reversed()), 40)

        val stored = cards.observeBenefits(gold).first()
        assertEquals(listOf("Lounge", "Credit A", "Credit B", "Done", "Misc", "Purchase Protection"), stored.map { it.title })
        assertEquals("Covers eligible purchases" to Enrollment.ENROLLED, stored.last().let { it.description to it.enrollment })
    }

    @Test
    fun benefitsForAnUnknownCardOrAccountAreIgnored() = runTest {
        val unknown = CardKey("amex", "4444")
        cards.applyCardBenefits(unknown, benefits(unknown, "Ghost"), 50)
        cards.applyCardList("chase", list(complete = true, "Sapphire" to "9999"), 51)

        assertEquals(emptyList<Any>(), cards.observeBenefits(unknown).first())
        assertEquals(emptyList<Any>(), cards.observeCards("chase").first())
    }

    @Test
    fun reminderRecordsAreOncePerPeriodAndPrunedAfterTheirPeriod() = runTest {
        val key = benefitKey(gold, "Dining Credit")
        assertFalse(cards.recordReminder(ReminderRecord(gold, key, periodEnd, 60)))
        assertTrue(cards.recordReminder(ReminderRecord(gold, key, periodEnd.plusMonths(6), 61)))

        cards.pruneReminders(periodEnd.plusDays(1))

        assertFalse(cards.reminderSent(key, periodEnd))
        assertTrue(cards.reminderSent(key, periodEnd.plusMonths(6)))
    }
}
