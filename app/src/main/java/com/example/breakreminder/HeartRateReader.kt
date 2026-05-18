package com.example.breakreminder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.widget.Toast
import com.example.breakreminder.background.BreakCause
import com.example.breakreminder.background.BreakNotificationHelper
import com.example.breakreminder.background.BreakSessionStateStore
import com.example.commonlibrary.WearSyncHelper
import com.example.commonlibrary.SettingsData
import com.example.breakreminder.stress.StressFeedbackConfig
import com.example.breakreminder.stress.StressFeedbackStore
import com.example.breakreminder.stress.StressInferenceEngine
import com.example.breakreminder.stress.StressModelRunner
import com.example.breakreminder.stress.StressPrediction
import com.example.breakreminder.stress.StressSample
import kotlin.math.abs
import kotlin.math.sqrt

class HeartRateReader(
    private val context: Context,
    private val shouldTriggerNavigation: () -> Boolean,
    private val feedbackConfigProvider: () -> StressFeedbackConfig,
    private val onStressFeedbackPromptRequested: ((StressPrediction) -> Unit)? = null,
    private val autoNavigateOnFeedbackPrompt: Boolean = false,
    private val settingsProvider: (() -> SettingsData)? = null,
    private val onNavigateToHome: () -> Unit
) : SensorEventListener {

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val heartRateSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val accelerometerSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val ambientTemperatureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)

    private val inferenceEngine = StressInferenceEngine(StressModelRunner(context))
    private val feedbackStore = StressFeedbackStore(context)
    private val samples = mutableListOf<StressSample>()

    private var latestMotionMagnitude: Float? = null
    private var latestAmbientTemperature: Float? = null
    private var lastEvaluationMillis: Long = 0L
    private var pendingPrediction: StressPrediction? = null

    fun startReading() {
        try {
            if (heartRateSensor != null) {
                sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
                accelerometerSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                ambientTemperatureSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            } else {
                Toast.makeText(context, "Heart rate sensor not available", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Log.w("HeartRateReader", "Sensor permission missing: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                handleHeartRateSample(event.values.firstOrNull() ?: return)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                updateMotionMagnitude(event.values)
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                latestAmbientTemperature = event.values.firstOrNull()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle sensor accuracy changes if needed
    }

    fun stopReading() {
        sensorManager.unregisterListener(this)
    }

    fun submitStressFeedback(userFeelsStressed: Boolean) {
        val prediction = pendingPrediction ?: return
        val cfg = feedbackConfigProvider()
        feedbackStore.recordFeedback(
            adjustedScore = prediction.adjustedScore,
            userFeelsStressed = userFeelsStressed,
            personalizationEnabled = cfg.personalizationEnabled
        )
        pendingPrediction = null

        if (userFeelsStressed && shouldTriggerNavigation()) {
            navigateToRecoverySession()
        }
    }

    private fun handleHeartRateSample(heartRate: Float) {
        if (heartRate <= 0f) return

        val now = System.currentTimeMillis()
        samples.add(
            StressSample(
                timestampMillis = now,
                heartRateBpm = heartRate,
                motionMagnitude = latestMotionMagnitude,
                ambientTemperatureC = latestAmbientTemperature
            )
        )
        pruneOldSamples(now)

        if (pendingPrediction != null) return
        if (now - lastEvaluationMillis < EVALUATION_INTERVAL_MILLIS) return
        lastEvaluationMillis = now

        val cfg = feedbackConfigProvider()
        val prediction = inferenceEngine.predict(
            samples = samples,
            personalizationBias = feedbackStore.getPersonalizationBias(),
            personalizationEnabled = cfg.personalizationEnabled
        )

        if (!prediction.isStress || !shouldTriggerNavigation()) return

        val promptCallback = onStressFeedbackPromptRequested
        if (cfg.feedbackPromptEnabled && promptCallback != null) {
            pendingPrediction = prediction
            try {
                promptCallback(prediction)
            } catch (e: Exception) {
                Log.w("HeartRateReader", "Stress feedback prompt callback failed: ${e.message}")
            }
            if (autoNavigateOnFeedbackPrompt) {
                pendingPrediction = null
                navigateToRecoverySession(showStressBreakNotification = false)
            }
        } else {
            navigateToRecoverySession()
        }
    }

    private fun pruneOldSamples(nowMillis: Long) {
        val threshold = nowMillis - SAMPLE_WINDOW_MILLIS
        while (samples.isNotEmpty() && samples.first().timestampMillis < threshold) {
            samples.removeAt(0)
        }
    }

    private fun updateMotionMagnitude(values: FloatArray) {
        if (values.size < 3) return
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val total = sqrt((x * x) + (y * y) + (z * z))
        latestMotionMagnitude = abs(total - SensorManager.GRAVITY_EARTH) / SensorManager.GRAVITY_EARTH
    }

    private fun navigateToRecoverySession(showStressBreakNotification: Boolean = true) {
        try {
            settingsProvider?.invoke()?.let { settings ->
                WearSyncHelper.sendSettings(
                    context = context,
                    settings = settings,
                    includeSchedule = false,
                    includeHue = true
                )
            }
        } catch (t: Throwable) {
            Log.w("HeartRateReader", "sendSettings before session failed: ${t.message}")
        }

        if (showStressBreakNotification) {
            try {
                BreakNotificationHelper.showBreakStartNotification(context, BreakCause.STRESS)
            } catch (t: Throwable) {
                Log.w("HeartRateReader", "break notification failed: ${t.message}")
            }
        }

        try {
            BreakSessionStateStore.markSessionStarted(context)
        } catch (_: Exception) {
        }

        try {
            WearSyncHelper.sendSessionState(context, true)
        } catch (t: Throwable) {
            Log.w("HeartRateReader", "sendSessionState failed: ${t.message}")
        }
        onNavigateToHome()
    }

    companion object {
        private const val SAMPLE_WINDOW_MILLIS = 45_000L
        private const val EVALUATION_INTERVAL_MILLIS = 8_000L

        // helper to send restore from other parts of the app (e.g., when DefaultScreen appears)
        fun sendHueRestoreMessage(context: Context) {
            try {
                BreakSessionStateStore.markSessionEnded(context)
                WearSyncHelper.sendSessionState(context, false)
                Log.d("HeartRateReader", "Sent session restore via Data Layer")
            } catch (e: Exception) {
                Log.w("HeartRateReader", "sendHueRestoreMessage exception: ${e.message}")
            }
        }
    }
}
