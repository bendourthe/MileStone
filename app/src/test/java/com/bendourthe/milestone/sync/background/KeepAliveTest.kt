package com.bendourthe.milestone.sync.background

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.MileStoneApplication
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class KeepAliveTest {
    private val app = ApplicationProvider.getApplicationContext<MileStoneApplication>()
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val delta = StaticCatalogRepository().program("delta")!!

    @Before
    fun clearAlarms() {
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let(alarms::cancel) }
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun closeDatabase() = app.container.database.close()

    @Test
    fun scheduleSetsOneIdleTimeWakeUpAfterTheInterval() {
        val before = SystemClock.elapsedRealtime()

        KeepAliveScheduler.schedule(app)

        // The midnight refresh alarm is a wall-clock alarm, so only elapsed-time alarms belong to the keep-alive.
        val keepAlive = shadowOf(alarms).scheduledAlarms.filter { it.type == AlarmManager.ELAPSED_REALTIME_WAKEUP }
        assertEquals(1, keepAlive.size)
        val alarm = keepAlive.single()
        assertNotNull(alarm)
        assertTrue(alarm.allowWhileIdle)
        assertTrue(alarm.triggerAtTime - before >= KeepAliveScheduler.INTERVAL_MS)
    }

    @Test
    fun openingTheAppKeepsTheAlarmAlreadySet() {
        fun keepAlive() = shadowOf(alarms).scheduledAlarms.filter { it.type == AlarmManager.ELAPSED_REALTIME_WAKEUP }
        KeepAliveScheduler.schedule(app, delayMs = 60_000L)
        val first = keepAlive().single().triggerAtTime

        KeepAliveScheduler.ensureScheduled(app)

        assertEquals(listOf(first), keepAlive().map { it.triggerAtTime })
    }

    @Test
    fun aRestartOrUpdateOnlySchedulesTheNextAlarm() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            KeepAliveReceiver().onReceive(app, Intent(action))

            assertNotNull(shadowOf(alarms).peekNextScheduledAlarm())
            assertNull("$action started a refresh", shadowOf(app).nextStartedService)
        }
    }

    @Test
    fun anAlarmSchedulesTheNextOneAndStartsTheBackgroundRefresh() {
        KeepAliveReceiver().onReceive(app, Intent(app, KeepAliveReceiver::class.java))

        assertNotNull(shadowOf(alarms).peekNextScheduledAlarm())
        val started = shadowOf(app).nextStartedService
        assertEquals(KeepAliveService::class.java.name, started?.component?.className)
    }

    @Test
    fun theNeedsYouNotificationOpensTheProgramSignInAndShowsNoBalance() {
        Notifications.createChannels(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        Notifications.needsYou(app, delta)

        val posted = shadowOf(notifications).allNotifications.single()
        val title = posted.extras.getCharSequence("android.title").toString()
        val text = posted.extras.getCharSequence("android.text").toString()
        assertEquals("Delta SkyMiles needs you", title)
        assertFalse(text.any(Char::isDigit))
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(Notifications.ACTION_SIGN_IN, intent.action)
        assertEquals("delta", intent.getStringExtra(MainActivity.EXTRA_PROGRAM_ID))

        Notifications.clearNeedsYou(app, "delta")

        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test
    fun noNotificationIsPostedWithoutPermission() {
        Notifications.createChannels(app)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        Notifications.needsYou(app, delta)

        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test
    fun theRefreshingNotificationIsQuietAndShowsNoBalance() {
        Notifications.createChannels(app)

        val notification = Notifications.refreshing(app)

        assertEquals(NotificationManager.IMPORTANCE_MIN, notifications.getNotificationChannel(notification.channelId).importance)
        assertEquals("Keeping programs connected", notification.extras.getCharSequence("android.title").toString())
    }
}
