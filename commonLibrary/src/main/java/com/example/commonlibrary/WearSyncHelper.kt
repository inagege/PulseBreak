package com.example.commonlibrary

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object WearSyncHelper {
    private const val SETTINGS_PATH = "/settings"
    private const val SESSION_PATH = "/session"
    private const val TAG = "WearSyncHelper"

    /**
     * Send settings to the paired device. By default schedule and hue fields are omitted
     * to avoid accidental overwrites; callers can opt-in to include them.
     */
    fun sendSettings(context: Context, settings: SettingsData, includeSchedule: Boolean = false, includeHue: Boolean = false) {
        Log.d(TAG, "Sending settings from ${context.packageName}: $settings includeSchedule=$includeSchedule includeHue=$includeHue")
        val dataClient = Wearable.getDataClient(context)

        // Build the PutDataMapRequest first so we can inspect its DataMap for logging
        val putMap = PutDataMapRequest.create(SETTINGS_PATH)
        putMap.apply {
            dataMap.putBoolean("isDarkMode", settings.isDarkMode)
            dataMap.putInt("buttonColor", settings.buttonColor)
            dataMap.putInt("buttonTextColor", settings.buttonTextColor)
            dataMap.putString("screenSelection", settings.screenSelection)

            if (includeSchedule) {
                dataMap.putBoolean("scheduleBreakIntervals", settings.scheduleBreakIntervals)
                dataMap.putInt("breakIntervalHours", settings.breakIntervalHours)
                dataMap.putInt("breakIntervalMinutes", settings.breakIntervalMinutes)
                // mark explicit schedule update
                dataMap.putBoolean("schedule_update", true)
            }

            if (includeHue) {
                val hue = settings.hueAutomation
                dataMap.putStringArrayList("hue_lightIds", ArrayList(hue.lightIds))
                dataMap.putStringArrayList("hue_groupIds", ArrayList(hue.groupIds))
                dataMap.putInt("hue_brightness", hue.brightness)
                dataMap.putInt("hue_colorArgb", hue.colorArgb)
                dataMap.putInt("hue_colorTemperature", hue.colorTemperature)
                if (hue.sceneId != null) dataMap.putString("hue_sceneId", hue.sceneId)
                dataMap.putString("hue_colorMode", hue.colorMode.name)
                dataMap.putInt("hue_scenePreviewArgb", hue.scenePreviewArgb)
                dataMap.putBoolean("hue_update", true)
            }

            // Ensure change is noticed by adding a timestamp
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }

        // Convert to PutDataRequest and mark urgent
        val request = putMap.asPutDataRequest().setUrgent()

        // Diagnostic: log the keys we just added to the PutDataMapRequest's dataMap
        try {
            val dm = putMap.dataMap
            Log.d(TAG, "Prepared DataMap for $SETTINGS_PATH -> ${dm.toString()}")
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to log DataMap debug: ${t.message}")
        }

        dataClient.putDataItem(request)
            .addOnSuccessListener { Log.d(TAG, "Settings sent successfully: ${it.uri}") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to send settings", e) }
    }

    /**
     * Send the current session state (whether the watch entered a break/stress session).
     * The companion should react to changes on this path by applying / restoring previews.
     */
    fun sendSessionState(context: Context, isInBreakSession: Boolean) {
        try {
            Log.d(TAG, "Sending session state $isInBreakSession from ${context.packageName}")
            val dataClient = Wearable.getDataClient(context)
            val request = PutDataMapRequest.create(SESSION_PATH).apply {
                dataMap.putBoolean("isInBreakSession", isInBreakSession)
                dataMap.putLong("updatedAt", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(request)
                .addOnSuccessListener { Log.d(TAG, "Session state sent: ${it.uri}") }
                .addOnFailureListener { e -> Log.w(TAG, "Failed to send session state", e) }

            // Also send a Message as a fast fallback (some devices deliver messages faster)
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val messageClient = Wearable.getMessageClient(context)
                nodeClient.connectedNodes
                    .addOnSuccessListener { nodes ->
                        val payload = if (isInBreakSession) byteArrayOf(1) else byteArrayOf(0)
                        for (n in nodes) {
                            messageClient.sendMessage(n.id, SESSION_PATH, payload)
                                .addOnSuccessListener { Log.d(TAG, "Session message sent to ${n.id}") }
                                .addOnFailureListener { e -> Log.w(TAG, "Session message send failed to ${n.id}: ${e.message}") }

                            // Also trigger companion test/restore paths directly (mirrors Test button behavior)
                            val altPath = if (isInBreakSession) "/hue/test" else "/hue/restore"
                            try {
                                messageClient.sendMessage(n.id, altPath, payload)
                                    .addOnSuccessListener { Log.d(TAG, "Alt session message $altPath sent to ${n.id}") }
                                    .addOnFailureListener { e -> Log.w(TAG, "Alt session message $altPath send failed to ${n.id}: ${e.message}") }
                            } catch (e: Exception) {
                                Log.w(TAG, "Alt session message send error: ${e.message}")
                            }
                        }
                    }
                    .addOnFailureListener { e -> Log.w(TAG, "Failed to query connected nodes for session message: ${e.message}") }
            } catch (e: Exception) {
                Log.w(TAG, "sendSessionState message fallback failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendSessionState exception: ${e.message}")
        }
    }
}
