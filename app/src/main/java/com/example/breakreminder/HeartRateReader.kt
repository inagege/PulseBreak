package com.example.breakreminder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast

class HeartRateReader(private val context: Context, private val shouldTriggerNavigation: () -> Boolean, private val onNavigateToHome: () -> Unit) : SensorEventListener {

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val heartRateSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val heartRateList = mutableListOf<Float>()


    fun startReading() {
        if (heartRateSensor != null) {
            sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            Toast.makeText(context, "Heart rate sensor not available", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            val heartRate = event.values[0]
            // onHeartRateUpdate(heartRate) // Add this line
            heartRateList.add(heartRate)

            if (heartRateList.size == 2) {
                val hrv = computeHRV(heartRateList)
                if (hrv > 0 && shouldTriggerNavigation()) {
                    onNavigateToHome()
                }
                heartRateList.clear()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle sensor accuracy changes if needed
    }

    fun stopReading() {
        sensorManager.unregisterListener(this)
    }

    private fun computeHRV(heartRates: List<Float>): Float {
        val maxHeartRate = heartRates.maxOrNull() ?: return 0f
        val minHeartRate = heartRates.minOrNull() ?: return 0f
        val change = maxHeartRate - minHeartRate
        return if (change > 5) change else 0f
    }
}