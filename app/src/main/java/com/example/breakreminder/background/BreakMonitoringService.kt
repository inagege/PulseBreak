package com.example.breakreminder.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.breakreminder.HeartRateReader
import com.example.breakreminder.stress.StressFeedbackConfig
import com.example.breakreminder.stress.StressFeedbackStore
import com.example.breakreminder.sync.SettingsManager
import com.example.commonlibrary.SettingsData
import com.example.commonlibrary.WearSyncHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BreakMonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsManager: SettingsManager
    private var heartRateReader: HeartRateReader? = null

    @Volatile
    private var currentSettings: SettingsData = SettingsData()

    @Volatile
    private var intervalStartElapsed: Long = 0L

    @Volatile
    private var lastBreakTriggerElapsed: Long = 0L

    private var settingsJob: Job? = null
    private var intervalJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(applicationContext)
        intervalStartElapsed = SystemClock.elapsedRealtime()

        startForeground(
            BreakNotificationHelper.monitoringNotificationId(),
            BreakNotificationHelper.buildMonitoringNotification(this)
        )

        startSettingsCollection()
        startStressMonitoring()
        startIntervalMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_INTERVAL -> resetIntervalTimer()
            ACTION_START, null -> {
                // no-op; service is already initialized in onCreate
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            heartRateReader?.stopReading()
        } catch (_: Exception) {
        }
        settingsJob?.cancel()
        intervalJob?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSettingsCollection() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsManager.settingsFlow.collect { settings ->
                currentSettings = settings
            }
        }
    }

    private fun startStressMonitoring() {
        val reader = HeartRateReader(
            context = applicationContext,
            shouldTriggerNavigation = {
                !AppForegroundState.isForeground &&
                    !BreakSessionStateStore.isSessionActive(applicationContext)
            },
            feedbackConfigProvider = {
                StressFeedbackConfig(
                    feedbackPromptEnabled = currentSettings.feedbackPromptEnabled,
                    personalizationEnabled = currentSettings.personalizationEnabled
                )
            },
            onStressFeedbackPromptRequested = { prediction ->
                try {
                    StressFeedbackStore(applicationContext).setPendingPrompt(
                        adjustedScore = prediction.adjustedScore,
                        personalizationEnabled = currentSettings.personalizationEnabled
                    )
                } catch (_: Exception) {
                }
                BreakNotificationHelper.showBreakStartNotification(
                    context = applicationContext,
                    cause = BreakCause.STRESS
                )
            },
            autoNavigateOnFeedbackPrompt = true,
            settingsProvider = { currentSettings },
            onNavigateToHome = {
                // Session state + alert are sent by HeartRateReader; here we additionally
                // push latest hue settings to the companion before the break session.
                scope.launch(Dispatchers.IO) {
                    sendLatestHueSettings()
                }
            }
        )
        heartRateReader = reader

        try {
            reader.startReading()
        } catch (e: SecurityException) {
            Log.w(TAG, "Sensor access denied in background monitoring: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start stress monitoring: ${e.message}")
        }
    }

    private fun startIntervalMonitoring() {
        intervalJob?.cancel()
        intervalJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                if (AppForegroundState.isForeground) continue
                if (BreakSessionStateStore.isSessionActive(applicationContext)) continue

                val settings = currentSettings
                if (!settings.scheduleBreakIntervals) continue

                val totalMinutes = settings.breakIntervalHours * 60 + settings.breakIntervalMinutes
                if (totalMinutes <= 0) continue

                val now = SystemClock.elapsedRealtime()
                val thresholdMillis = totalMinutes * 60 * 1000L
                if (now - intervalStartElapsed < thresholdMillis) continue
                if (now - lastBreakTriggerElapsed < BREAK_TRIGGER_DEBOUNCE_MILLIS) continue

                lastBreakTriggerElapsed = now
                intervalStartElapsed = now

                triggerIntervalBreak(settings)
            }
        }
    }

    private suspend fun triggerIntervalBreak(settings: SettingsData) {
        try {
            try {
                StressFeedbackStore(applicationContext).clearPendingPrompt()
            } catch (_: Exception) {
            }
            var ok = false
            try {
                ok = WearSyncHelper.sendSettingsAndAwait(
                    context = applicationContext,
                    settings = settings,
                    includeSchedule = false,
                    includeHue = true,
                    timeoutMs = 3_000L
                )
            } catch (_: Exception) {
            }
            if (!ok) {
                try {
                    WearSyncHelper.sendSettings(
                        context = applicationContext,
                        settings = settings,
                        includeSchedule = false,
                        includeHue = true
                    )
                } catch (_: Exception) {
                }
            }
            try {
                BreakSessionStateStore.markSessionStarted(applicationContext)
                WearSyncHelper.sendSessionState(applicationContext, true)
            } catch (_: Exception) {
            }
            BreakNotificationHelper.showBreakStartNotification(
                applicationContext,
                BreakCause.INTERVAL
            )
        } catch (e: Exception) {
            Log.w(TAG, "Interval break trigger failed: ${e.message}")
        }
    }

    private suspend fun sendLatestHueSettings() {
        try {
            val settings = currentSettings
            WearSyncHelper.sendSettings(
                context = applicationContext,
                settings = settings,
                includeSchedule = false,
                includeHue = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send latest hue settings before stress break: ${e.message}")
        }
    }

    private fun resetIntervalTimer() {
        intervalStartElapsed = SystemClock.elapsedRealtime()
        lastBreakTriggerElapsed = 0L
    }

    companion object {
        const val ACTION_START = "com.example.breakreminder.action.START_MONITORING"
        const val ACTION_RESET_INTERVAL = "com.example.breakreminder.action.RESET_INTERVAL"

        private const val TAG = "BreakMonitoringService"
        private const val BREAK_TRIGGER_DEBOUNCE_MILLIS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, BreakMonitoringService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun resetInterval(context: Context) {
            val intent = Intent(context, BreakMonitoringService::class.java).apply {
                action = ACTION_RESET_INTERVAL
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
