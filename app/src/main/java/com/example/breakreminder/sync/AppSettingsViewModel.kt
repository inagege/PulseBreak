package com.example.breakreminder.sync

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.commonlibrary.SettingsData
import com.example.commonlibrary.WearSyncHelper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppSettingsViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppSettingsViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(getApplication()) // use application to avoid storing context
    private val dataClient = Wearable.getDataClient(getApplication())

    // Expose settings as a StateFlow from the DataStore flow
    val settings: StateFlow<SettingsData> = settingsManager.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsData() // Provide a default initial value
        )

    // Listener for remote data changes. Merge incoming partial settings into current stored settings
    private val dataChangedListener = DataClient.OnDataChangedListener { dataEvents ->
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/settings") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

                viewModelScope.launch {
                    try {
                        val current = settingsManager.loadInitialSettings()

                        // Only update hueAutomation subfields if the DataMap actually contains them.
                        val hueUpdateFlag = dataMap.getBoolean("hue_update", false)
                        val scheduleUpdateFlag = dataMap.getBoolean("schedule_update", false)
                        val newHue = if (hueUpdateFlag) current.hueAutomation.copy(
                            lightIds = dataMap.getStringArrayList("hue_lightIds")?.toList() ?: current.hueAutomation.lightIds,
                            groupIds = dataMap.getStringArrayList("hue_groupIds")?.toList() ?: current.hueAutomation.groupIds,
                            brightness = dataMap.getInt("hue_brightness", current.hueAutomation.brightness),
                            colorArgb = dataMap.getInt("hue_colorArgb", current.hueAutomation.colorArgb),
                            colorTemperature = dataMap.getInt("hue_colorTemperature", current.hueAutomation.colorTemperature),
                            sceneId = dataMap.getString("hue_sceneId") ?: current.hueAutomation.sceneId,
                            colorMode = try { com.example.commonlibrary.HueColorMode.valueOf(dataMap.getString("hue_colorMode") ?: current.hueAutomation.colorMode.name) } catch (_: Exception) { current.hueAutomation.colorMode },
                            scenePreviewArgb = dataMap.getInt("hue_scenePreviewArgb", current.hueAutomation.scenePreviewArgb)
                        ) else current.hueAutomation

                        val merged = current.copy(
                            isDarkMode = dataMap.getBoolean("isDarkMode", current.isDarkMode),
                            buttonColor = dataMap.getInt("buttonColor", current.buttonColor),
                            buttonTextColor = dataMap.getInt("buttonTextColor", current.buttonTextColor),
                            screenSelection = dataMap.getString("screenSelection") ?: current.screenSelection,
                            scheduleBreakIntervals = if (scheduleUpdateFlag && dataMap.containsKey("scheduleBreakIntervals")) dataMap.getBoolean("scheduleBreakIntervals") else current.scheduleBreakIntervals,
                            breakIntervalHours = if (scheduleUpdateFlag && dataMap.containsKey("breakIntervalHours")) dataMap.getInt("breakIntervalHours") else current.breakIntervalHours,
                            breakIntervalMinutes = if (scheduleUpdateFlag && dataMap.containsKey("breakIntervalMinutes")) dataMap.getInt("breakIntervalMinutes") else current.breakIntervalMinutes,
                            hueAutomation = newHue
                        )
                        settingsManager.applySettings(merged)
                        Log.d("ViewModel", "Received remote settings and merged: $merged")
                    } catch (e: Exception) {
                        Log.w("ViewModel", "Failed to merge remote settings: ${e.message}")
                    }
                }
            }
        }
    }

    init {
        // Start listening for remote changes
        dataClient.addListener(dataChangedListener)
        Log.d("ViewModel", "Data listener added.")
    }

    // Update settings locally and send to the other device
    fun updateSettings(newSettings: SettingsData) {
        viewModelScope.launch {
            try {
                // Load current stored settings
                val current = settingsManager.loadInitialSettings()

                // Determine whether the caller intends to update schedule or hue fields
                val scheduleChanged = (newSettings.scheduleBreakIntervals != current.scheduleBreakIntervals)
                        || (newSettings.breakIntervalHours != current.breakIntervalHours)
                        || (newSettings.breakIntervalMinutes != current.breakIntervalMinutes)

                val hueChanged = (newSettings.hueAutomation.lightIds.isNotEmpty()
                        || newSettings.hueAutomation.groupIds.isNotEmpty()
                        || newSettings.hueAutomation.sceneId != null
                        || newSettings.hueAutomation.brightness != current.hueAutomation.brightness
                        || newSettings.hueAutomation.colorArgb != current.hueAutomation.colorArgb)

                // Build merged local settings: always copy UI fields; copy schedule/hue only if caller intended to change them
                val mergedLocal = current.copy(
                    isDarkMode = newSettings.isDarkMode,
                    buttonColor = newSettings.buttonColor,
                    buttonTextColor = newSettings.buttonTextColor,
                    screenSelection = newSettings.screenSelection,
                    scheduleBreakIntervals = if (scheduleChanged) newSettings.scheduleBreakIntervals else current.scheduleBreakIntervals,
                    breakIntervalHours = if (scheduleChanged) newSettings.breakIntervalHours else current.breakIntervalHours,
                    breakIntervalMinutes = if (scheduleChanged) newSettings.breakIntervalMinutes else current.breakIntervalMinutes,
                    hueAutomation = if (hueChanged) newSettings.hueAutomation else current.hueAutomation
                )

                // Persist merged settings locally
                settingsManager.applySettings(mergedLocal)

                // Decide which fields to send to companion: send schedule/hue only if they were changed
                val includeSchedule = scheduleChanged
                val includeHue = hueChanged

                WearSyncHelper.sendSettings(getApplication(), mergedLocal, includeSchedule = includeSchedule, includeHue = includeHue)
            } catch (e: Exception) {
                Log.w("ViewModel", "updateSettings failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up the listener when the ViewModel is destroyed
        dataClient.removeListener(dataChangedListener)
        Log.d("ViewModel", "Data listener removed.")
    }
}
