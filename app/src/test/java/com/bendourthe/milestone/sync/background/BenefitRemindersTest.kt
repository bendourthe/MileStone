package com.bendourthe.milestone.sync.background

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.domain.Benefit
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitPeriods
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.ReminderSettings
import com.bendourthe.milestone.domain.assignBenefitKeys
import com.bendourthe.milestone.ui.FakeCards
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class BenefitRemindersTest {
    private val zone = ZoneOffset.UTC
    private val day = 24 * 60 * 60 * 1000L
    private val cards = FakeCards()
    private val gold = CardKey("amex", "71008")
    private val settings = object : ReminderSettings {
        val state = MutableStateFlow(true)
        override val enabled = state
        override suspend fun setEnabled(enabled: Boolean) { state.value = enabled }
    }
    private val posted = mutableListOf<String>()
    private var permitted = true
    private val notifier = object : ReminderNotifier {
        override fun canNotify() = permitted
        override fun post(card: Card, benefit: Benefit, readDate: LocalDate) { posted += benefit.title }
    }
    private var now = at(2026, 11, 23)

    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun reminders() = BenefitReminders(cards, settings, notifier, { now }, zone)

    /** Stores credits the way a benefits read does, period ends filled from the labels, read at [readMs]. */
    private suspend fun read(readMs: Long, vararg drafts: BenefitDraft) {
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Gold Card", "71008")), true), readMs)
        val readDate = java.time.Instant.ofEpochMilli(readMs).atZone(zone).toLocalDate()
        val dated = drafts.map { d -> BenefitPeriods.parse(d.periodLabel, readDate)?.let { d.copy(periodEnd = it.end, periodLength = it.length) } ?: d }
        cards.applyCardBenefits(gold, assignBenefitKeys(gold, dated), readMs)
    }

    private fun credit(title: String, label: String?, left: Long = 1_000, done: Boolean = false, section: BenefitSection = BenefitSection.CREDIT_EARNED) =
        BenefitDraft(section, title, 0, left, left, label, completed = done)

    @Test
    fun aMonthlyCreditRemindsSevenDaysBeforeItsEndAndOnlyOnce() = runTest {
        now = at(2026, 11, 22)
        read(now, credit("Monthly", "Nov"))
        assertEquals(0, reminders().check())
        now = at(2026, 11, 23)
        read(now, credit("Monthly", "Nov"))
        assertEquals(1, reminders().check())
        assertEquals(0, reminders().check())
        assertEquals(listOf("Monthly"), posted)
    }

    @Test
    fun aHalfYearCreditRemindsThirtyDaysBefore() = runTest {
        now = at(2026, 11, 30)
        read(now, credit("Half", "Jul - Dec"))
        assertEquals(0, reminders().check())
        now = at(2026, 12, 1)
        read(now, credit("Half", "Jul - Dec"))
        assertEquals(1, reminders().check())
    }

    @Test
    fun usedCompletedSpendAndUnknownPeriodsNeverRemind() = runTest {
        read(
            now,
            credit("Used", "Nov", left = 0),
            credit("Done", "Nov", done = true),
            credit("Spend", "Nov", section = BenefitSection.SPEND_PROGRESS),
            credit("Unknown", "Ongoing"),
            credit("Listed", "Nov", section = BenefitSection.LISTED),
        )
        assertEquals(0, reminders().check())
    }

    @Test
    fun reorderedCreditsDoNotRemindAgain() = runTest {
        read(now, credit("A", "Nov"), credit("B", "Nov"))
        assertEquals(2, reminders().check())
        read(now + 1, credit("B", "Nov"), credit("A", "Nov"))
        assertEquals(0, reminders().check())
    }

    @Test
    fun staleBenefitsGetNoReminderUntilReadAgain() = runTest {
        read(now - 9 * day, credit("Monthly", "Nov"))
        assertEquals(0, reminders().check())
        read(now, credit("Monthly", "Nov"))
        assertEquals(1, reminders().check())
    }

    @Test
    fun theSettingAndAMissingPermissionStopRemindersWithoutUsingThePeriod() = runTest {
        read(now, credit("Monthly", "Nov"))
        settings.state.value = false
        assertEquals(0, reminders().check())
        settings.state.value = true
        permitted = false
        assertEquals(0, reminders().check())
        assertTrue(cards.reminders.isEmpty())
        permitted = true
        assertEquals(1, reminders().check())
    }

    @Test
    fun aMissedDayCatchesUpOnceAndOldRecordsArePruned() = runTest {
        read(now, credit("Monthly", "Nov"))
        now = at(2026, 11, 27)
        assertEquals(1, reminders().check())
        assertEquals(0, reminders().check())
        now = at(2026, 12, 1)
        reminders().check()
        assertTrue("records for ended periods are pruned", cards.reminders.isEmpty())
    }

    @Test
    fun theNextCheckIsAtNineLocalTime() {
        val beforeNine = LocalDate.of(2026, 11, 12).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val afterNine = LocalDate.of(2026, 11, 12).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 12).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), BenefitReminderScheduler.nextAt(beforeNine, zone))
        assertEquals(LocalDate.of(2026, 11, 13).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), BenefitReminderScheduler.nextAt(afterNine, zone))
    }

    @Test
    fun theLockScreenVersionNamesNoCardOrAmount() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AndroidReminderNotifier.createChannel(app)
        val benefit = assignBenefitKeys(gold, listOf(credit("\$50 Shopping Credit", "Oct - Dec", left = 5_000).copy(periodEnd = LocalDate.of(2026, 12, 31)))).single()
        AndroidReminderNotifier(app).post(Card("amex", "71008", "Gold Card", 0, now, now), benefit, LocalDate.of(2026, 11, 23))

        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(AndroidReminderNotifier.CHANNEL, notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val full = notification.extras.getString(Notification.EXTRA_TITLE) + " " + notification.extras.getString(Notification.EXTRA_TEXT)
        assertTrue(full, full.contains("Gold Card") && full.contains("\$50.00 left") && !full.contains("71008"))
        val public = notification.publicVersion!!.extras
        assertEquals("A card benefit expires soon", public.getString(Notification.EXTRA_TITLE))
        assertFalse(public.keySet().any { key -> public.get(key)?.toString()?.let { it.contains("Gold") || it.contains("$") } == true })
        assertEquals("Benefit reminders", manager.getNotificationChannel(AndroidReminderNotifier.CHANNEL).name)
    }
}
