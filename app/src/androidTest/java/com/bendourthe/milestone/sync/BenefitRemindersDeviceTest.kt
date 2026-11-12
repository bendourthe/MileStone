package com.bendourthe.milestone.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitPeriods
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.ReminderSettings
import com.bendourthe.milestone.domain.assignBenefitKeys
import com.bendourthe.milestone.sync.background.AndroidReminderNotifier
import com.bendourthe.milestone.sync.background.BenefitReminders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** The daily reminder check posts one private notification for a credit about to expire, on a real device. */
@RunWith(AndroidJUnit4::class)
class BenefitRemindersDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val db = Room.inMemoryDatabaseBuilder(app, MileStoneDatabase::class.java).build()
    private val manager = app.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        manager.cancelAll()
        db.close()
    }

    @Test
    fun aDueCreditPostsOnePrivateReminder() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        AndroidReminderNotifier.createChannel(app)
        manager.cancelAll()

        val accounts = RoomAccountRepository(db)
        val cards = RoomCardRepository(db)
        val gold = CardKey("amex", "71008")
        val now = System.currentTimeMillis()
        val today = LocalDate.now(ZoneId.systemDefault())
        accounts.addAccount("amex", AccountStatus.CONNECTED, now)
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Example Gold Card", "71008")), true), now)
        // A single-month credit for the current month: within 7 days of its end only near month end, so the label is
        // chosen from today's date to always be due.
        val label = if (today.lengthOfMonth() - today.dayOfMonth <= 7) today.month.name.take(3) else "Annual"
        val period = BenefitPeriods.parse(label, today)!!
        val due = if (label == "Annual") period.copy(end = today.plusDays(10)) else period
        val draft = BenefitDraft(BenefitSection.CREDIT_EARNED, "\$10 Example Credit", 0, 1_000, 1_000, label, completed = false, periodEnd = due.end, periodLength = due.length)
        cards.applyCardBenefits(gold, assignBenefitKeys(gold, listOf(draft)), now)

        val settings = object : ReminderSettings {
            override val enabled = MutableStateFlow(true)
            override suspend fun setEnabled(enabled: Boolean) {}
        }
        val reminders = BenefitReminders(cards, settings, AndroidReminderNotifier(app))
        assertEquals(1, reminders.check())
        assertEquals(0, reminders.check())

        val posted = manager.activeNotifications.filter { it.notification.channelId == AndroidReminderNotifier.CHANNEL }
        assertEquals(1, posted.size)
        val notification = posted.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("A card benefit expires soon", notification.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertEquals("Example Gold Card: \$10 Example Credit", title)
        assertFalse(title.contains("71008"))
    }
}
