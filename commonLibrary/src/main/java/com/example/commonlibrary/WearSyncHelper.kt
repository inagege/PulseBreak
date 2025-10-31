package com.example.commonlibrary

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object WearSyncHelper {
    private const val SETTINGS_PATH = "/settings"
    private const val TAG = "WearSyncHelper"

    fun sendSettings(context: Context, settings: SettingsData) {
        Log.d(TAG, "Sending settings from ${context.packageName}: $settings")
        val dataClient = Wearable.getDataClient(context)
        val request = PutDataMapRequest.create(SETTINGS_PATH).apply {
            dataMap.putBoolean("isDarkMode", settings.isDarkMode)
            dataMap.putInt("buttonColor", settings.buttonColor)
            dataMap.putInt("buttonTextColor", settings.buttonTextColor)
            dataMap.putString("screenSelection", settings.screenSelection)
            // Ensure change is noticed by adding a timestamp
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        dataClient.putDataItem(request)
            .addOnSuccessListener { Log.d(TAG, "Settings sent successfully: ${it.uri}") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to send settings", e) }
    }
}