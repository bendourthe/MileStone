package com.bendourthe.milestone.sync.background

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.bendourthe.milestone.DiagnosticLog
import com.bendourthe.milestone.R
import com.bendourthe.milestone.domain.Benefit
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardRepository
import com.bendourthe.milestone.domain.PeriodLength
import com.bendourthe.milestone.domain.ReminderRecord
import com.bendourthe.milestone.domain.ReminderSettings
import com.bendourthe.milestone.ui.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Posts reminders; the Android version is [AndroidReminderNotifier]. */
interface ReminderNotifier {
    fun canNotify(): Boolean
    fun post(card: Card, benefit: Benefit, readDate: LocalDate)
}

/**
 * The daily benefit reminder check (plan 5.2): one private reminder per statement credit and period, when part of
 * the credit is unused and its period ends within the lead time for its length. Never reminds from stale data (a
 * card read more than 8 days ago) or a guessed period end, and never twice for the same credit and period.
 */
class BenefitReminders(
    private val cards: CardRepository,
    private val settings: ReminderSettings,
    private val notifier: ReminderNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Returns how many reminders were posted. */
    suspend fun check(): Int {
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        cards.pruneReminders(today)
        if (!settings.enabled.first() || !notifier.canNotify()) return 0
        var posted = 0
        cards.allCardsWithBenefits().forEach { (card, benefits) ->
            benefits.filter { isDue(card, it, today, now) }.forEach { benefit ->
                val end = benefit.periodEnd!!
                // Recording first makes the once-per-period rule hold even if posting is interrupted.
                if (cards.recordReminder(ReminderRecord(card.key, benefit.stableKey, end, now))) {
                    notifier.post(card, benefit, Instant.ofEpochMilli(card.benefitsReadEpochMs!!).atZone(zone).toLocalDate())
                    posted++
                }
            }
        }
        if (posted > 0) DiagnosticLog.i(TAG, "Posted $posted benefit reminders")
        return posted
    }

    companion object {
        private const val TAG = "MileStoneReminders"
        const val STALE_AFTER_MS = 8L * 24 * 60 * 60 * 1000

        fun leadDays(length: PeriodLength): Long = when (length) {
            PeriodLength.MONTHLY -> 7
            PeriodLength.QUARTERLY -> 14
            PeriodLength.HALF_YEAR, PeriodLength.YEARLY -> 30
        }

        fun isDue(card: Card, benefit: Benefit, today: LocalDate, nowMs: Long): Boolean {
            if (benefit.section != BenefitSection.CREDIT_EARNED || benefit.completed) return false
            if ((benefit.remainingCents ?: 0) <= 0) return false
            val end = benefit.periodEnd ?: return false
            val length = benefit.periodLength ?: return false
            val read = card.benefitsReadEpochMs ?: return false
            if (nowMs - read > STALE_AFTER_MS || today.isAfter(end)) return false
            return ChronoUnit.DAYS.between(today, end) <= leadDays(length)
        }
    }
}

/** The "Benefit reminders" setting in its own private preferences file; on by default. */
class PreferencesReminderSettings(context: Context) : ReminderSettings {
    private val prefs = context.getSharedPreferences("benefit_reminders", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY, true))
    override val enabled: Flow<Boolean> = state.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        state.value = enabled
    }

    private companion object {
        const val KEY = "enabled"
    }
}

/**
 * Private notifications in the "Benefit reminders" channel: the lock screen shows only "A card benefit expires
 * soon"; the full text names the card (never its digits), the credit, the amount left, and the read date.
 */
class AndroidReminderNotifier(private val context: Context, private val locale: Locale = Locale.getDefault()) : ReminderNotifier {
    override fun canNotify(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun post(card: Card, benefit: Benefit, readDate: LocalDate) {
        val day = DateTimeFormatter.ofPattern("MMM d", locale)
        val left = NumberFormat.getCurrencyInstance(Locale.US).format((benefit.remainingCents ?: 0) / 100.0)
        val open = PendingIntent.getActivity(
            context, benefit.stableKey.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_CARD)
                .putExtra(EXTRA_PROGRAM_ID, card.programId)
                .putExtra(EXTRA_LAST_DIGITS, card.lastDigits)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PUBLIC_TEXT)
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${card.name}: ${benefit.title}")
            .setContentText("$left left, period ends ${benefit.periodEnd?.format(day)}, as of ${readDate.format(day)}")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId(benefit), notification)
    }

    companion object {
        const val CHANNEL = "benefit_reminders"
        const val PUBLIC_TEXT = "A card benefit expires soon"
        const val ACTION_OPEN_CARD = "com.bendourthe.milestone.action.OPEN_CARD"
        const val EXTRA_PROGRAM_ID = "programId"
        const val EXTRA_LAST_DIGITS = "lastDigits"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Benefit reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A card credit's period ends soon with money left"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }

        private fun notificationId(benefit: Benefit) = 5000 + (("${benefit.stableKey}|${benefit.periodEnd}").hashCode() and 0xFFFF)
    }
}

/** Wakes MileStone once a day at [HOUR] local time for the reminder check, through the existing alarm receiver. */
object BenefitReminderScheduler {
    const val ACTION = "com.bendourthe.milestone.action.BENEFIT_REMINDERS"
    private const val REQUEST_CODE = 3
    private val HOUR = LocalTime.of(9, 0)

    fun nextAt(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate().atTime(HOUR).atZone(zone)
        return (if (today.isAfter(now)) today else today.plusDays(1)).toInstant().toEpochMilli()
    }

    fun schedule(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!KeepAliveScheduler.enabled) {
            alarms.cancel(pendingIntent(context))
            return
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt(nowMs), pendingIntent(context))
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, KeepAliveReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
