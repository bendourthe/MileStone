package com.bendourthe.milestone.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.CardSegment
import com.bendourthe.milestone.domain.Connection
import com.bendourthe.milestone.domain.ConnectionInventory
import com.bendourthe.milestone.domain.InventoryOutcome
import com.bendourthe.milestone.domain.SourceCard
import com.bendourthe.milestone.domain.assignBenefitKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ADR-0009 identity and coverage rules, on synthetic connections and cards only. */
@RunWith(AndroidJUnit4::class)
class ConnectionCoverageTest {
    private lateinit var db: MileStoneDatabase
    private lateinit var cards: RoomCardRepository

    private val personal = Connection("program_chase", "chase", CardSegment.PERSONAL, 0, 1)
    private val business = Connection("chase_2", "chase", CardSegment.BUSINESS, 1, 2)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MileStoneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val accounts = RoomAccountRepository(db)
        cards = RoomCardRepository(db)
        accounts.addAccount("chase", AccountStatus.CONNECTED, 1)
        accounts.addAccount("amex", AccountStatus.CONNECTED, 2)
        assertTrue(cards.addConnection(personal))
        assertTrue(cards.addConnection(business))
    }

    @After
    fun tearDown() = db.close()

    private fun hash(n: Int) = "%064x".format(n)

    private fun source(n: Int, name: String = "Synthetic Card $n", digits: String? = "4321") = SourceCard(hash(n), name, digits)

    private suspend fun apply(connection: Connection, at: Long, complete: Boolean, vararg sources: SourceCard) =
        cards.applyConnectionInventory(ConnectionInventory("chase", connection.connectionId, sources.toList(), complete), at)

    private suspend fun chase(): List<Card> = cards.observeCards("chase").first()

    private suspend fun cardFor(binding: Int): Card {
        val id = db.cardDao().coverageByBinding("chase", hash(binding)).map { it.cardId }.distinct().single()
        return chase().single { it.cardId == id }
    }

    @Test
    fun personalInventoryCreatesOpaqueDistinctCardsEvenWithEqualEndingsAndNames() = runTest {
        assertEquals(InventoryOutcome.APPLIED, apply(personal, 10, true, source(1, "Same Name"), source(2, "Same Name")))

        val stored = chase()
        assertEquals(2, stored.size)
        assertNotEquals(stored[0].cardId, stored[1].cardId)
        assertTrue(stored.all { Regex("^c_[0-9a-f]{16}$").matches(it.cardId) && it.lastDigits == "4321" })
        assertTrue(stored.all { it.segment == CardSegment.PERSONAL })
        assertTrue(stored.all { it.key.displayDigits == null })
    }

    @Test
    fun aBusinessProductListedByAPersonalSignInIsShownUnderBusinessUnlessTheOwnerMovesIt() = runTest {
        apply(personal, 10, true, source(1, "Synthetic Sapphire"), source(2, "Ink Synthetic Preferred"), source(3, "Synthetic Business Platinum"))

        assertEquals(CardSegment.PERSONAL, cardFor(1).segment)
        assertEquals(CardSegment.BUSINESS, cardFor(2).segment)
        assertEquals(CardSegment.BUSINESS, cardFor(3).segment)

        cards.setCardSegmentOverride(cardFor(2).key, CardSegment.PERSONAL)
        apply(personal, 20, true, source(1, "Synthetic Sapphire"), source(2, "Ink Synthetic Preferred"), source(3, "Synthetic Business Platinum"))
        assertEquals(CardSegment.PERSONAL, cardFor(2).segment)
    }

    @Test
    fun theIssuerHomePageGroupDecidesTheSectionOfAnUnknownProduct() = runTest {
        apply(personal, 10, true, source(1, "Synthetic Unknown A"), SourceCard(hash(2), "Synthetic Unknown B", "4321", CardSegment.BUSINESS))

        assertEquals(CardSegment.PERSONAL, cardFor(1).segment)
        assertEquals(CardSegment.BUSINESS, cardFor(2).segment)
    }

    @Test
    fun productNamesSayBusinessOnlyThroughWholeWords() {
        assertEquals(CardSegment.BUSINESS, CardSegment.fromProductName("Ink Business Cash"))
        assertEquals(CardSegment.BUSINESS, CardSegment.fromProductName("United Business Card"))
        assertEquals(null, CardSegment.fromProductName("Sapphire Preferred"))
        assertEquals(null, CardSegment.fromProductName("Pink Rewards"))
        assertEquals(null, CardSegment.fromProductName("Businessless Synthetic"))
    }

    @Test
    fun linkedBusinessAccessSharesBoundCardsAndAddsOnlyTheBusinessCard() = runTest {
        apply(personal, 10, true, source(1), source(2))
        val before = chase().map { it.cardId }.toSet()

        assertEquals(InventoryOutcome.APPLIED, apply(business, 20, true, source(3), source(1), source(2)))

        val after = chase()
        assertEquals(3, after.size)
        assertTrue(after.map { it.cardId }.containsAll(before))
        assertEquals(CardSegment.PERSONAL, cardFor(1).segment)
        assertEquals(CardSegment.PERSONAL, cardFor(2).segment)
        assertEquals(CardSegment.BUSINESS, cardFor(3).segment)
        assertEquals(2, db.cardDao().coverageOfCard("chase", cardFor(1).cardId).size)
    }

    @Test
    fun personalOnlyRefreshNeverErasesBusinessCardsOrTheirBenefits() = runTest {
        apply(personal, 10, true, source(1), source(2))
        apply(business, 20, true, source(1), source(2), source(3))
        val businessCard = cardFor(3).key
        cards.applyCardBenefits(businessCard, credits(businessCard), 21)

        assertEquals(InventoryOutcome.APPLIED, apply(personal, 30, true, source(1), source(2)))

        assertEquals(3, chase().size)
        assertEquals(1, cards.observeBenefits(businessCard).first().size)
    }

    @Test
    fun completeRefreshDropsOnlyThisConnectionsCoverageAndDeletesOnlyUnreachableCards() = runTest {
        apply(personal, 10, true, source(1), source(2), source(5))
        apply(business, 20, true, source(1), source(3))

        // Personal keeps card 5 and no longer lists card 1 (still reached by Business) and card 2 (reached by nobody else).
        apply(personal, 30, true, source(5), source(4))

        val remaining = chase()
        assertEquals(setOf(hash(1), hash(3), hash(4), hash(5)), remaining.flatMap { db.cardDao().coverageOfCard("chase", it.cardId) }.map { it.bindingHash }.toSet())
        assertEquals(4, remaining.size)
        assertEquals(CardSegment.BUSINESS, cardFor(1).segment)
    }

    @Test
    fun aCompleteListSharingNoCardWithTheConnectionsCoverageChangesNothing() = runTest {
        apply(personal, 10, true, source(1), source(2))
        val before = chase()

        assertEquals(InventoryOutcome.UNMATCHED, apply(personal, 20, true, source(7), source(8)))
        assertEquals(before, chase())
        // One shared card is enough to trust the list: the others are a real change.
        assertEquals(InventoryOutcome.APPLIED, apply(personal, 30, true, source(1), source(8)))
        assertEquals(setOf(cardFor(1).cardId, cardFor(8).cardId), chase().map { it.cardId }.toSet())
    }

    @Test
    fun partialEmptyStaleDuplicateAndUnknownInventoriesChangeNothing() = runTest {
        apply(personal, 10, true, source(1), source(2))
        val snapshot = chase()

        assertEquals(InventoryOutcome.APPLIED, apply(personal, 20, false, source(1)))
        assertEquals(snapshot.map { it.cardId }.toSet(), chase().map { it.cardId }.toSet())
        assertEquals(InventoryOutcome.APPLIED, apply(personal, 30, true))
        assertEquals(2, chase().size)
        assertEquals(InventoryOutcome.STALE, apply(personal, 30, true, source(9)))
        assertEquals(InventoryOutcome.STALE, apply(personal, 5, true, source(9)))
        assertEquals(InventoryOutcome.REJECTED_DUPLICATE, apply(personal, 40, true, source(1), source(1, "Other")))
        assertEquals(
            InventoryOutcome.UNKNOWN_CONNECTION,
            cards.applyConnectionInventory(ConnectionInventory("chase", "chase_9", listOf(source(9)), true), 50),
        )
        assertEquals(
            InventoryOutcome.UNKNOWN_CONNECTION,
            cards.applyConnectionInventory(ConnectionInventory("amex", personal.connectionId, listOf(source(9)), true), 60),
        )
        assertEquals(2, chase().size)
        assertTrue(chase().none { it.cardId == cardForOrNull(9) })
    }

    private suspend fun cardForOrNull(binding: Int) = db.cardDao().coverageByBinding("chase", hash(binding)).firstOrNull()?.cardId

    @Test
    fun removingAConnectionKeepsSharedCardsAndRemovesOnlyItsOwnWithTheirBenefits() = runTest {
        apply(personal, 10, true, source(1))
        apply(business, 20, true, source(1), source(3))
        val shared = cardFor(1).key
        val own = cardFor(3).key
        cards.applyCardBenefits(own, credits(own), 21)

        cards.removeConnection(business.connectionId)

        assertEquals(listOf(shared.cardId), chase().map { it.cardId })
        assertEquals(CardSegment.PERSONAL, chase().single().segment)
        assertTrue(cards.observeBenefits(own).first().isEmpty())
        assertEquals(listOf(personal.connectionId), cards.connections("chase").map { it.connectionId })
    }

    @Test
    fun ownerOverrideWinsAndSurvivesRefreshesAndClearing() = runTest {
        apply(personal, 10, true, source(1))
        val key = cardFor(1).key

        cards.setCardSegmentOverride(key, CardSegment.BUSINESS)
        apply(personal, 20, true, source(1))
        assertEquals(CardSegment.BUSINESS, cardFor(1).segment)

        cards.setCardSegmentOverride(key, null)
        assertEquals(CardSegment.PERSONAL, cardFor(1).segment)
    }

    @Test
    fun changingAConnectionsDeclarationRederivesItsCards() = runTest {
        apply(business, 10, true, source(3))
        assertEquals(CardSegment.BUSINESS, cardFor(3).segment)

        cards.setConnectionSegment(business.connectionId, CardSegment.PERSONAL)

        assertEquals(CardSegment.PERSONAL, cardFor(3).segment)
        assertEquals(CardSegment.PERSONAL, cards.connections("chase").single { it.connectionId == business.connectionId }.segment)
    }

    @Test
    fun benefitsAndNameUpdatesStayWithTheBoundCardAcrossRefreshes() = runTest {
        apply(personal, 10, true, source(1, "Old Name"))
        val key = cardFor(1).key
        cards.applyCardBenefits(key, credits(key), 11)

        apply(personal, 20, true, source(1, "New Name", digits = null))

        val card = cardFor(1)
        assertEquals(key, card.key)
        assertEquals("New Name", card.name)
        assertEquals("4321", card.lastDigits)
        assertEquals(1, cards.observeBenefits(key).first().size)
    }

    @Test
    fun legacyLastDigitsListKeepsKeysAndNeverPrunesConnectionCards() = runTest {
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Synthetic Gold", "1008")), true), 10)
        val amex = cards.observeCards("amex").first().single()
        assertEquals(CardKey("amex", "1008"), amex.key)
        assertEquals("1008", amex.key.displayDigits)

        // A connection card in the same program survives a complete legacy list that does not name it.
        db.cardDao().insertConnection(ConnectionEntity("amex_2", "amex", "PERSONAL", 0, 1, null, false))
        cards.applyConnectionInventory(ConnectionInventory("amex", "amex_2", listOf(source(7)), true), 20)
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Synthetic Gold", "1008")), true), 30)
        assertEquals(2, cards.observeCards("amex").first().size)

        // The legacy path still prunes legacy cards it no longer lists.
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Synthetic Green", "2002")), true), 40)
        assertFalse(cards.observeCards("amex").first().any { it.cardId == "1008" })
        assertEquals(2, cards.observeCards("amex").first().size)
    }

    @Test
    fun connectionsRequireATrackedProgramAndAUniqueId() = runTest {
        assertFalse(cards.addConnection(personal))
        assertFalse(cards.addConnection(Connection("delta_1", "delta", CardSegment.PERSONAL, 0, 1)))
        assertNull(db.cardDao().connection("delta_1"))
    }

    private fun credits(card: CardKey) = assignBenefitKeys(
        card,
        listOf(BenefitDraft(BenefitSection.CREDIT_EARNED, "Synthetic Credit", 1000, 500, 1500, "Jan - Dec", completed = false)),
    )
}
