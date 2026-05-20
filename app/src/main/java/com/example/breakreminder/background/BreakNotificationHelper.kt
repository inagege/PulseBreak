package com.example.breakreminder.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.breakreminder.MainActivity
import com.example.breakreminder.R

enum class BreakCause(val title: String, val body: String) {
    STRESS(
        title = "Pause started",
        body = "Stress detection triggered a recovery pause."
    ),
    INTERVAL(
        title = "Pause started",
        body = "Scheduled interval reached. Time for a break."
    )
}

object BreakNotificationHelper {
    const val MONITORING_CHANNEL_ID = "pulsebreak_monitoring"
    const val BREAK_ALERT_CHANNEL_ID = "pulsebreak_break_alert"
    const val FEEDBACK_CHANNEL_ID = "pulsebreak_feedback"
    const val ACTION_OPEN_BREAK_START = "com.example.breakreminder.action.OPEN_BREAK_START"
    const val EXTRA_OPEN_BREAK_START = "extra_open_break_start"
    const val ACTION_FEEDBACK_YES = "com.example.breakreminder.action.STRESS_FEEDBACK_YES"
    const val ACTION_FEEDBACK_NO = "com.example.breakreminder.action.STRESS_FEEDBACK_NO"
    const val EXTRA_FEEDBACK_SCORE = "extra_feedback_score"
    const val EXTRA_PERSONALIZATION_ENABLED = "extra_personalization_enabled"

    private const val MONITORING_NOTIFICATION_ID = 1101
    private const val BREAK_ALERT_NOTIFICATION_ID = 1102
    private const val FEEDBACK_NOTIFICATION_ID = 1103

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(MONITORING_CHANNEL_ID) == null) {
            val monitoring = NotificationChannel(
                MONITORING_CHANNEL_ID,
                "Background Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps stress and interval monitoring active."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(monitoring)
        }

        if (manager.getNotificationChannel(BREAK_ALERT_CHANNEL_ID) == null) {
            val alert = NotificationChannel(
                BREAK_ALERT_CHANNEL_ID,
                "Break Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alert shown when a stress or interval break starts."
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(alert)
        }

        if (manager.getNotificationChannel(FEEDBACK_CHANNEL_ID) == null) {
            val feedback = NotificationChannel(
                FEEDBACK_CHANNEL_ID,
                "Stress Feedback",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Asks stress feedback to improve future predictions."
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(feedback)
        }
    }

    fun buildMonitoringNotification(context: Context): Notification {
        ensureChannels(context)
        val tapIntent = openAppPendingIntent(context, openBreakStart = false)

        return Notification.Builder(context, MONITORING_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PulseBreak monitoring active")
            .setContentText("Stress and interval checks run in the background.")
            .setContentIntent(tapIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun showBreakStartNotification(context: Context, cause: BreakCause) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val tapIntent = openAppPendingIntent(context, openBreakStart = true)
        val notification = Notification.Builder(context, BREAK_ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(cause.title)
            .setContentText(cause.body)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        manager.notify(BREAK_ALERT_NOTIFICATION_ID, notification)
    }

    fun showStressFeedbackPrompt(
        context: Context,
        adjustedScore: Float,
        personalizationEnabled: Boolean,
        usedSignals: Set<String> = emptySet()
    ) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val tapIntent = openAppPendingIntent(context, openBreakStart = true)

        val signalSummary = if (usedSignals.isEmpty()) "" else " Signals: ${usedSignals.joinToString()}."
        val body = if (signalSummary.isEmpty()) {
            "How stressed do you feel? Tap to rate from 1 to 4."
        } else {
            "How stressed do you feel? Tap to rate from 1 to 4.$signalSummary"
        }

        val notification = Notification.Builder(context, FEEDBACK_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Stress Check")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        manager.notify(FEEDBACK_NOTIFICATION_ID, notification)
    }

    fun dismissStressFeedbackPrompt(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(FEEDBACK_NOTIFICATION_ID)
    }

    fun monitoringNotificationId(): Int = MONITORING_NOTIFICATION_ID

    private fun openAppPendingIntent(context: Context, openBreakStart: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (openBreakStart) {
                action = ACTION_OPEN_BREAK_START
                putExtra(EXTRA_OPEN_BREAK_START, true)
            }
        }
        return PendingIntent.getActivity(
            context,
            if (openBreakStart) BREAK_ALERT_NOTIFICATION_ID else MONITORING_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
