package com.bendourthe.milestone.ui.benefits

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.*
import com.bendourthe.milestone.data.settings.DataStoreBenefitsDisplaySettings
import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.sync.*
import com.bendourthe.milestone.ui.FakeSessions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class BenefitsAlertLifecycleTest {
    @get:Rule val files = TemporaryFolder()
    private val key = CardKey("amex", "1234")
    private val other = CardKey("amex", "5678")
    private val now = LocalDate.of(2026, 11, 13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val drafts = listOf(
        BenefitDraft(BenefitSection.CREDIT_EARNED, "Shopping Credit", 0, 5000, 5000, null, false),
        BenefitDraft(BenefitSection.LISTED, "Shopping Credit", null, null, null, null, false, enrollment = Enrollment.ENROLLMENT_REQUIRED),
    )
    private fun read(sections: Set<BenefitSection> = BenefitsRead.ALL_SECTIONS, card: CardKey = key) = BenefitsRead(
        CardList.from(listOf(CardDraft("Platinum Card", key.lastDigits), CardDraft("Second Card", other.lastDigits)), true),
        mapOf(card.lastDigits to drafts.filter { it.section in sections }), sections = mapOf(card.lastDigits to sections),
    )

    @Test fun acknowledgementSurvivesFailuresRestartAndUnrelatedReadsThenRelevantSuccessReturnsIt() = runTest(UnconfinedTestDispatcher()) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MileStoneDatabase::class.java).build()
        try {
            val cards = RoomCardRepository(db)
            val accounts = RoomAccountRepository(db)
            accounts.addAccount("amex", AccountStatus.CONNECTED, now)
            val sessions = FakeSessions().apply { markConnected("amex", now) }
            val file = files.root.resolve("lifecycle.preferences_pb")
            val storeJob = SupervisorJob()
            var settings = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = CoroutineScope(coroutineContext + storeJob)) { file })
            fun manager(observer: suspend (CardKey, Long, Long, Set<BenefitSection>) -> Unit = settings::recordReadOutcome) = SyncManager(
                accounts, sessions, StaticCatalogRepository(), { _, _, _, _ -> SyncResult.Success(0) }, backgroundScope,
                clock = { now }, host = { null }, cards = cards, onCardBenefitsStored = observer,
            )
            suspend fun current() = cards.observeCards("amex").first().first { it.key == key }
            suspend fun active() = BenefitsAlerts.active(current(), cards.observeBenefits(key).first(), settings.observe(key).first(), now, ZoneOffset.UTC, Locale.US)
            manager().applyBenefits("amex", read())
            val enrollment = active().single()
            cards.recordReminder(ReminderRecord(key, "sample-reminder", LocalDate.of(2026, 12, 31), now))
            settings.acknowledge(key, enrollment.id, enrollment.generation)
            assertTrue(active().isEmpty())
            manager().applyBenefits("amex", read(BenefitsRead.TRACKER_SECTIONS).copy(benefits = mapOf(key.lastDigits to listOf(drafts.first().copy(periodLabel = "December")))))
            assertTrue(active().none { it.id.startsWith("enrollment:") })
            manager().applyBenefits("amex", BenefitsRead(null, mapOf(key.lastDigits to emptyList()), sections = mapOf(key.lastDigits to BenefitsRead.TRACKER_SECTIONS)))
            assertTrue(active().none { it.id.startsWith("enrollment:") })
            manager().applyBenefits("amex", BenefitsRead(null, emptyMap(), failed = setOf(key.lastDigits), stop = BenefitsStop.BUDGET))
            assertTrue(active().none { it.id.startsWith("enrollment:") })
            manager().applyBenefits("amex", read(card = other))
            assertTrue(active().none { it.id.startsWith("enrollment:") })
            manager().applyBenefits("amex", read(BenefitsRead.TRACKER_SECTIONS))
            val listFailure = active().single()
            assertEquals("listed-refresh", listFailure.id)
            settings.acknowledge(key, listFailure.id, listFailure.generation)
            manager().applyBenefits("amex", read(BenefitsRead.TRACKER_SECTIONS))
            assertTrue(active().isEmpty())
            assertEquals(2, cards.observeBenefits(key).first().size)
            storeJob.cancelAndJoin()
            settings = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
            assertTrue(active().isEmpty())
            manager().applyBenefits("amex", read(BenefitsRead.LIST_SECTIONS))
            assertEquals(enrollment.id, active().single().id)
            assertNotEquals(enrollment.generation, active().single().generation)
            val returned = active().single()
            val barrier = CompletableDeferred<Unit>()
            val acknowledgement = launch(Dispatchers.Default) { barrier.await(); settings.acknowledge(key, returned.id, returned.generation) }
            val refresh = launch(Dispatchers.Default) { barrier.await(); manager().applyBenefits("amex", read(BenefitsRead.LIST_SECTIONS)) }
            val sorting = launch(Dispatchers.Default) { barrier.await(); settings.setCompletedLast(key, true) }
            barrier.complete(Unit)
            joinAll(acknowledgement, refresh, sorting)
            assertEquals(returned.id, active().single().id)
            assertNotEquals(returned.generation, active().single().generation)
            assertTrue(settings.observe(key).first().completedLast)
            assertTrue(cards.reminderSent("sample-reminder", LocalDate.of(2026, 12, 31)))
            assertNull(BenefitsAlerts.listFreshness(current(), cards.observeBenefits(key).first(), settings.observe(key).first()))
        } finally { db.close() }
    }

    @Test fun missingOutcomeCannotClaimFreshnessEvenAtTheSameTimestampAndDoesNotResetAcknowledgement() = runTest(UnconfinedTestDispatcher()) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MileStoneDatabase::class.java).build()
        try {
            val accounts = RoomAccountRepository(db); accounts.addAccount("amex", AccountStatus.CONNECTED, now)
            val cards = RoomCardRepository(db)
            val sessions = FakeSessions().apply { markConnected("amex", now) }
            val settings = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = backgroundScope) { files.root.resolve("failed.preferences_pb") })
            fun manager(observer: suspend (CardKey, Long, Long, Set<BenefitSection>) -> Unit) = SyncManager(
                accounts, sessions, StaticCatalogRepository(), { _, _, _, _ -> SyncResult.Success(0) }, backgroundScope,
                clock = { now }, host = { null }, cards = cards, onCardBenefitsStored = observer,
            )
            manager(settings::recordReadOutcome).applyBenefits("amex", read())
            val before = cards.observeCards("amex").first().first { it.key == key }
            val alert = BenefitsAlerts.active(before, cards.observeBenefits(key).first(), settings.observe(key).first(), now, ZoneOffset.UTC, Locale.US).single()
            settings.acknowledge(key, alert.id, alert.generation)
            manager { _, _, _, _ -> throw IOException("Synthetic unavailable store") }.applyBenefits("amex", read(BenefitsRead.TRACKER_SECTIONS))
            val after = cards.observeCards("amex").first().first { it.key == key }
            assertEquals(before.benefitsReadEpochMs, after.benefitsReadEpochMs)
            assertEquals(before.benefitsReadGeneration + 1, after.benefitsReadGeneration)
            assertTrue(BenefitsAlerts.listFreshness(after, cards.observeBenefits(key).first(), settings.observe(key).first())!!.contains("unavailable"))
            assertTrue(BenefitsAlerts.active(after, cards.observeBenefits(key).first(), settings.observe(key).first(), now, ZoneOffset.UTC, Locale.US).isEmpty())
            try {
                manager { _, _, _, _ -> throw CancellationException("Synthetic cancellation") }.applyBenefits("amex", read(BenefitsRead.TRACKER_SECTIONS))
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertTrue(settings.observe(key).first().isAcknowledged(alert.id, alert.generation))
            assertEquals(2, cards.observeBenefits(key).first().size)
        } finally { db.close() }
    }
}
