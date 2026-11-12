package com.bendourthe.milestone.sync.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.bendourthe.milestone.R
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.ui.MainActivity

/** The quiet "refreshing" notification and the per-program "needs you" notification. Neither shows a balance. */
object Notifications {
    const val REFRESHING_ID = 1
    private const val REFRESH_CHANNEL = "background_refresh"
    private const val NEEDS_YOU_CHANNEL = "needs_you"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(REFRESH_CHANNEL, "Background refresh", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown briefly while MileStone keeps your programs connected"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(NEEDS_YOU_CHANNEL, "Sign-in needed", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A program needs you to finish signing in"
            },
        )
    }

    fun refreshing(context: Context): Notification =
        Notification.Builder(context, REFRESH_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Keeping programs connected")
            .setOngoing(true)
            .build()

    fun needsYou(context: Context, program: Program) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, program.programId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SIGN_IN)
                .putExtra(MainActivity.EXTRA_PROGRAM_ID, program.programId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, NEEDS_YOU_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${program.displayName} needs you")
            .setContentText("Tap to finish signing in. MileStone keeps it connected afterward.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(needsYouId(program.programId), notification)
    }

    fun clearNeedsYou(context: Context, programId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(needsYouId(programId))
    }

    const val ACTION_SIGN_IN = "com.bendourthe.milestone.action.SIGN_IN"

    private fun needsYouId(programId: String) = 1000 + (programId.hashCode() and 0xFFFF)
}
