package com.bendourthe.milestone.sync.background

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.bendourthe.milestone.DiagnosticLog
import com.bendourthe.milestone.BuildConfig
import com.bendourthe.milestone.MileStoneApplication
import com.bendourthe.milestone.domain.RefreshInterval
import com.bendourthe.milestone.sync.RefreshReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wakes MileStone every [INTERVAL_MS], even while the phone is idle, so each program's session is used before the
 * site's idle limit ends it. Android lets an idle-time alarm fire about every 9 minutes at most per app.
 */
object KeepAliveScheduler {
    const val INTERVAL_MS = 15 * 60_000L
    private const val REQUEST_CODE = 1

    /** The capture build shares this code but only saves pages for selector work; it never refreshes in the background. */
    val enabled = BuildConfig.BUILD_TYPE != "capture"

    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!enabled) {
            alarms.cancel(pendingIntent(context))
            return
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pendingIntent(context))
    }

    /** Keeps an alarm that is already set, so opening the app does not push the next refresh back. */
    fun ensureScheduled(context: Context) {
        val existing = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, KeepAliveReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing == null || !enabled) schedule(context)
    }

    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, KeepAliveReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Wakes MileStone at the user's scheduled refresh time (local midnight, see [RefreshInterval]) for a refresh that may
 * sign in. An idle phone can deliver it some minutes late.
 */
object ScheduledRefreshScheduler {
    const val ACTION = "com.bendourthe.milestone.action.SCHEDULED_REFRESH"
    private const val REQUEST_CODE = 2

    fun schedule(context: Context, interval: RefreshInterval, nowMs: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!KeepAliveScheduler.enabled) {
            alarms.cancel(pendingIntent(context))
            return
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, interval.nextAt(nowMs), pendingIntent(context))
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, KeepAliveReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Runs on each alarm, after a restart, and after an update; schedules the next alarm, then starts a refresh. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as MileStoneApplication).container
        if (intent.action == BenefitReminderScheduler.ACTION) {
            BenefitReminderScheduler.schedule(context)
            val pending = goAsync()
            container.scope.launch {
                try {
                    container.benefitReminders.check()
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BenefitReminderScheduler.schedule(context)
        }
        val scheduled = intent.action == ScheduledRefreshScheduler.ACTION
        if (scheduled) {
            val pending = goAsync()
            container.scope.launch {
                try {
                    ScheduledRefreshScheduler.schedule(context, container.settings.refreshInterval.first())
                } finally {
                    pending.finish()
                }
            }
        } else {
            KeepAliveScheduler.schedule(context)
        }
        if (!KeepAliveScheduler.enabled) return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (container.webViewHost != null) {
            if (scheduled) container.syncManager.refreshAll(RefreshReason.SCHEDULED)
            return
        }
        try {
            context.startForegroundService(Intent(context, KeepAliveService::class.java).putExtra(EXTRA_SCHEDULED, scheduled))
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                DiagnosticLog.i(TAG, "Background refresh skipped; MileStone is not exempt from battery optimization")
            } else {
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "MileStoneSync"
        const val EXTRA_SCHEDULED = "scheduled"
    }
}

/** Shows a quiet notification while one round of background refreshes runs, then stops. */
class KeepAliveService : Service() {
    private var job: Job? = null

    /** A scheduled refresh asked for while a keep-alive round runs follows it in the same service. */
    @Volatile
    private var scheduledPending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifications.refreshing(this)
        startForeground(Notifications.REFRESHING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (intent?.getBooleanExtra(KeepAliveReceiver.EXTRA_SCHEDULED, false) == true) scheduledPending = true
        if (job?.isActive == true) return START_NOT_STICKY
        val container = (application as MileStoneApplication).container
        job = container.scope.launch {
            try {
                container.vaultReady()
                container.backgroundHost = VirtualDisplayWebViewHost(this@KeepAliveService)
                do {
                    val scheduled = scheduledPending.also { scheduledPending = false }
                    container.scheduledRun = scheduled
                    try {
                        container.syncManager.refreshAll(if (scheduled) RefreshReason.SCHEDULED else RefreshReason.KEEP_ALIVE).join()
                    } finally {
                        container.scheduledRun = false
                    }
                } while (scheduledPending)
            } finally {
                container.backgroundHost = null
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
