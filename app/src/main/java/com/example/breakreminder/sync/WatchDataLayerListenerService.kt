package com.example.breakreminder.sync

import android.util.Log
import com.example.commonlibrary.HueColorMode
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WatchDataLayerListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsManager: SettingsManager

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(applicationContext)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (event.dataItem.uri.path != "/settings") return@forEach

            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            scope.launch {
                mergeRemoteSettings(dataMap)
            }
        }
    }

    private suspend fun mergeRemoteSettings(dataMap: DataMap) {
        try {
            val current = settingsManager.loadInitialSettings()
            val hueUpdateFlag = dataMap.getBoolean("hue_update", false)
            val scheduleUpdateFlag = dataMap.getBoolean("schedule_update", false)
            val activityDurationUpdateFlag = dataMap.getBoolean("activity_duration_update", false)

            val newHue = if (hueUpdateFlag) {
                current.hueAutomation.copy(
                    lightIds = dataMap.getStringArrayList("hue_lightIds")?.toList() ?: current.hueAutomation.lightIds,
                    groupIds = dataMap.getStringArrayList("hue_groupIds")?.toList() ?: current.hueAutomation.groupIds,
                    brightness = dataMap.getInt("hue_brightness", current.hueAutomation.brightness),
                    colorArgb = dataMap.getInt("hue_colorArgb", current.hueAutomation.colorArgb),
                    colorTemperature = dataMap.getInt("hue_colorTemperature", current.hueAutomation.colorTemperature),
                    sceneId = dataMap.getString("hue_sceneId") ?: current.hueAutomation.sceneId,
                    colorMode = try {
                        HueColorMode.valueOf(
                            dataMap.getString("hue_colorMode") ?: current.hueAutomation.colorMode.name
                        )
                    } catch (_: Exception) {
                        current.hueAutomation.colorMode
                    },
                    scenePreviewArgb = dataMap.getInt(
                        "hue_scenePreviewArgb",
                        current.hueAutomation.scenePreviewArgb
                    )
                )
            } else {
                current.hueAutomation
            }

            val merged = current.copy(
                isDarkMode = dataMap.getBoolean("isDarkMode", current.isDarkMode),
                buttonColor = dataMap.getInt("buttonColor", current.buttonColor),
                buttonTextColor = dataMap.getInt("buttonTextColor", current.buttonTextColor),
                screenSelection = dataMap.getString("screenSelection") ?: current.screenSelection,
                feedbackPromptEnabled = dataMap.getBoolean("feedbackPromptEnabled", current.feedbackPromptEnabled),
                personalizationEnabled = dataMap.getBoolean("personalizationEnabled", current.personalizationEnabled),
                scheduleBreakIntervals = if (scheduleUpdateFlag && dataMap.containsKey("scheduleBreakIntervals")) {
                    dataMap.getBoolean("scheduleBreakIntervals")
                } else {
                    current.scheduleBreakIntervals
                },
                breakIntervalHours = if (scheduleUpdateFlag && dataMap.containsKey("breakIntervalHours")) {
                    dataMap.getInt("breakIntervalHours")
                } else {
                    current.breakIntervalHours
                },
                breakIntervalMinutes = if (scheduleUpdateFlag && dataMap.containsKey("breakIntervalMinutes")) {
                    dataMap.getInt("breakIntervalMinutes")
                } else {
                    current.breakIntervalMinutes
                },
                walkBreakDurationMinutes = if (activityDurationUpdateFlag && dataMap.containsKey("walkBreakDurationMinutes")) {
                    dataMap.getInt("walkBreakDurationMinutes")
                } else {
                    current.walkBreakDurationMinutes
                },
                napBreakDurationMinutes = if (activityDurationUpdateFlag && dataMap.containsKey("napBreakDurationMinutes")) {
                    dataMap.getInt("napBreakDurationMinutes")
                } else {
                    current.napBreakDurationMinutes
                },
                windowBreakDurationMinutes = if (activityDurationUpdateFlag && dataMap.containsKey("windowBreakDurationMinutes")) {
                    dataMap.getInt("windowBreakDurationMinutes")
                } else {
                    current.windowBreakDurationMinutes
                },
                hueAutomation = newHue
            )
            settingsManager.applySettings(merged)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to merge /settings in background listener: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WatchDataLayerListener"
    }
}
