package com.bendourthe.milestone.sync

import android.content.Context
import android.webkit.WebView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardSegment
import com.bendourthe.milestone.domain.Connection
import com.bendourthe.milestone.domain.ConnectionInventory
import com.bendourthe.milestone.domain.SourceCard
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.domain.FakeCatalog
import com.bendourthe.milestone.ui.FakeSessions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A Chase read reaches storage through connection coverage (ADR-0009), with a real Room database. */
@RunWith(RobolectricTestRunner::class)
class ChaseSyncApplyTest {
    private lateinit var db: MileStoneDatabase
    private lateinit var cards: RoomCardRepository
    private val sessions = FakeSessions()
    private val personal = Connection("chase", "chase", CardSegment.PERSONAL, 0, 1)
    private val business = Connection("chase_2", "chase", CardSegment.BUSINESS, 1, 2)

    private object Host : WebViewHost {
        override suspend fun <T> withAttachedWebView(block: suspend (WebView) -> T): T = error("unused")
    }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MileStoneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cards = RoomCardRepository(db)
        RoomAccountRepository(db).addAccount("chase", AccountStatus.CONNECTED, 1)
        cards.addConnection(personal)
        cards.addConnection(business)
        sessions.markConnected("chase", 1)
    }

    @After
    fun tearDown() = db.close()

    private fun kotlinx.coroutines.test.TestScope.manager() = SyncManager(
        accounts = com.bendourthe.milestone.ui.FakeAccounts(),
        sessions = sessions,
        catalog = FakeCatalog(emptyList()),
        source = { _, _, _, _ -> SyncResult.Success(0) },
        scope = backgroundScope,
        host = { Host },
        clock = { 10_000L + testScheduler.currentTime },
        cards = cards,
    )

    private fun hash(n: Int) = "%064x".format(n)

    private fun credit(title: String) = BenefitDraft(BenefitSection.LISTED, title, null, null, 30_000, null, completed = false)

    @Test
    fun inventoriesCreateCardsAndBenefitsAttachThroughTheirBindings() = runTest {
        val read = BenefitsRead(
            cards = null, benefits = emptyMap(),
            inventories = listOf(
                ConnectionInventory("chase", "chase", listOf(SourceCard(hash(1), "Synthetic Reserve", "1111")), complete = true),
                ConnectionInventory("chase", "chase_2", listOf(SourceCard(hash(1), "Synthetic Reserve", "1111"), SourceCard(hash(2), "Synthetic Ink", "3333")), complete = true),
            ),
            bindingBenefits = mapOf(hash(1) to listOf(credit("Synthetic credit")), hash(2) to listOf(credit("Synthetic shipping")), hash(9) to listOf(credit("Unbound"))),
        )

        manager().applyBenefits("chase", read)

        val stored = cards.observeCards("chase").first()
        assertEquals(2, stored.size)
        assertEquals(mapOf("1111" to CardSegment.PERSONAL, "3333" to CardSegment.BUSINESS), stored.associate { it.lastDigits to it.segment })
        val reserve = stored.single { it.lastDigits == "1111" }
        assertEquals(listOf("Synthetic credit"), cards.observeBenefits(reserve.key).first().map { it.title })
        assertEquals(1, reserve.benefitsReadGeneration)
    }

    @Test
    fun aLaterPartialPersonalReadKeepsBusinessCardsAndTheirBenefits() = runTest {
        manager().applyBenefits("chase", BenefitsRead(null, emptyMap(),
            inventories = listOf(ConnectionInventory("chase", "chase_2", listOf(SourceCard(hash(2), "Synthetic Ink", "3333")), complete = true)),
            bindingBenefits = mapOf(hash(2) to listOf(credit("Synthetic shipping")))))
        manager().applyBenefits("chase", BenefitsRead(null, emptyMap(),
            inventories = listOf(ConnectionInventory("chase", "chase", listOf(SourceCard(hash(1), "Synthetic Reserve", "1111")), complete = false))))

        val stored = cards.observeCards("chase").first()
        assertEquals(setOf("1111", "3333"), stored.map { it.lastDigits }.toSet())
        assertTrue(cards.observeBenefits(stored.single { it.lastDigits == "3333" }.key).first().isNotEmpty())
    }
}
