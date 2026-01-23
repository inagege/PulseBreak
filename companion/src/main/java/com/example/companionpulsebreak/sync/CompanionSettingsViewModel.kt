package com.example.companionpulsebreak.sync

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
import com.google.android.gms.wearable.MessageClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CompanionSettingsViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CompanionSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CompanionSettingsViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class CompanionSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(getApplication()) // Instantiate the new manager using application
    private val dataClient = Wearable.getDataClient(getApplication())
    private val messageClient = Wearable.getMessageClient(getApplication())

    // Active preview session data (if a Test preview was applied and needs explicit restore)
    private var activePreviewSession: PreviewSession? = null
    private var activeHueVm: HueViewModel? = null
    private val previewFallbackMs = 10 * 60 * 1000L // 10 minutes safety fallback

    // Expose settings as a StateFlow from the DataStore flow
    val settings: StateFlow<SettingsData> = settingsManager.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsData() // Provide a default initial value
        )

    // Listener for remote data changes
    private val dataChangedListener = DataClient.OnDataChangedListener { dataEvents ->
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                if (path == "/settings") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

                    // Diagnostic logging: print incoming DataMap keys and values and updatedAt
                    try {
                        // Use DataMap.toString() to avoid generic type inference issues when calling get(k)
                        val mapDesc = dataMap.toString()
                        val updatedAt = if (dataMap.containsKey("updatedAt")) dataMap.getLong("updatedAt") else -1L
                        Log.d("ViewModel", "Incoming /settings DataMap: $mapDesc updatedAt=$updatedAt")
                    } catch (t: Throwable) {
                        Log.d("ViewModel", "Incoming /settings DataMap logging failed: ${t.message}")
                    }

                    // Merge remote partial settings with the existing stored settings to avoid clearing
                    // fields the remote didn't send (notably hueAutomation).
                    viewModelScope.launch {
                        try {
                            val current = settingsManager.loadInitialSettings()
                            // Log current hueAutomation for comparison
                            try { Log.d("ViewModel", "Current stored hueAutomation: lights=${current.hueAutomation.lightIds} groups=${current.hueAutomation.groupIds} brightness=${current.hueAutomation.brightness}") } catch (_: Exception) {}

                            // Build hueAutomation by only overwriting fields present in the incoming DataMap
                            val hueUpdateFlag = dataMap.getBoolean("hue_update", false)
                            val scheduleUpdateFlag = dataMap.getBoolean("schedule_update", false)
                            // Check that hue payload actually contains meaningful fields
                            val hasHuePayload = (dataMap.containsKey("hue_lightIds") && (dataMap.getStringArrayList("hue_lightIds")?.isNotEmpty() == true))
                                    || (dataMap.containsKey("hue_groupIds") && (dataMap.getStringArrayList("hue_groupIds")?.isNotEmpty() == true))
                                    || dataMap.containsKey("hue_sceneId")
                            val newHue = if (hueUpdateFlag && hasHuePayload) current.hueAutomation.copy(
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
                            Log.d("ViewModel", "Applying merged settings: schedule=${merged.scheduleBreakIntervals} ${merged.breakIntervalHours}h${merged.breakIntervalMinutes}m hueLights=${merged.hueAutomation.lightIds}")
                            settingsManager.applySettings(merged)
                            Log.d("ViewModel", "Received remote settings and merged: $merged")
                        } catch (e: Exception) {
                            Log.w("ViewModel", "Failed to merge remote settings: ${e.message}")
                        }
                    }
                } else if (path == "/session") {
                    // Handle session start/stop from the watch (DataMap)
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val inSession = dataMap.getBoolean("isInBreakSession", false)
                    Log.d("ViewModel", "Received session state change: $inSession")
                    // delegate to helper
                    handleSessionState(inSession)
                }
            }
        }
    }

    // Message listener for incoming commands (e.g., from watch) — used to trigger Hue test
    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        try {
            val path = event.path
            when (path) {
                "/hue/test" -> {
                     viewModelScope.launch {
                         try {
                             val sd = settingsManager.loadInitialSettings()
                             val hueSettings = sd.hueAutomation
                             val hueVm = HueViewModel(getApplication())
                             try { Log.d("ViewModel", "Test trigger: hueAutomation=${hueSettings}") } catch (_: Exception) {}
                             try { hueVm.refreshHueStateAndWait() } catch (_: Exception) {}
                             try { Log.d("ViewModel", "Hue VM state (test): bridge=${hueVm.bridgeIp.value} user=${hueVm.hueUsername.value} lights=${hueVm.lights.value.size} groups=${hueVm.groups.value.size}") } catch (_: Exception) {}
                              // Apply preview and keep session to restore later
                              val session = applyHuePreview(hueSettings, hueVm)
                              if (session != null) {
                                  activePreviewSession = session
                                  activeHueVm = hueVm
                                  // schedule a fallback restore after previewFallbackMs
                                  launch {
                                        try {
                                            kotlinx.coroutines.delay(previewFallbackMs)
                                            // if session still active, restore
                                            activePreviewSession?.let { s ->
                                                try { restoreHuePreview(s, activeHueVm!!) } catch (_: Exception) {}
                                                activePreviewSession = null
                                                activeHueVm = null
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("ViewModel", "applyHuePreview failed from message: ${e.message}")
                            }
                        }
                    }

                "/hue/restore" -> {
                    viewModelScope.launch {
                        try {
                            val s = activePreviewSession
                            val vm = activeHueVm
                            if (s != null && vm != null) {
                                restoreHuePreview(s, vm)
                            }
                        } catch (e: Exception) {
                            Log.w("ViewModel", "restoreHuePreview failed from message: ${e.message}")
                        } finally {
                            activePreviewSession = null
                            activeHueVm = null
                        }
                    }
                }
                // Add handler for SESSION_PATH message from watch
                "/session" -> {
                    viewModelScope.launch {
                        try {
                            val inSession = (event.data?.firstOrNull()?.toInt() == 1)
                             Log.d("ViewModel", "Received session state change (message): $inSession")
                             // delegate to helper
                             handleSessionState(inSession)
                         } catch (e: Exception) {
                             Log.w("ViewModel", "Session state change handling failed: ${e.message}")
                         }
                     }
                 }
            }
        } catch (t: Throwable) {
            Log.w("ViewModel", "messageListener exception: ${t.message}")
        }
    }

    init {
        // Start listening for remote changes
        dataClient.addListener(dataChangedListener)
        // Listen for messages
        messageClient.addListener(messageListener)
        Log.d("ViewModel", "Data & Message listeners added.")
    }

    // Update settings locally and send to the other device
    fun updateSettings(newSettings: SettingsData) {
        viewModelScope.launch {
            // Persist locally using the new suspend function
            settingsManager.applySettings(newSettings)
            // Send to companion (watch): include schedule and hue so watch receives full config
            WearSyncHelper.sendSettings(getApplication(), newSettings, includeSchedule = true, includeHue = true)
        }
    }

    // Safer partial update API: applies a modifier to the current settings so callers
    // don't accidentally overwrite unrelated fields.
    fun updateSettingsPartial(modifier: (SettingsData) -> SettingsData) {
        viewModelScope.launch {
            try {
                val current = settingsManager.loadInitialSettings()
                val updated = try { modifier(current) } catch (_: Exception) { current }
                settingsManager.applySettings(updated)
                WearSyncHelper.sendSettings(getApplication(), updated, includeSchedule = true, includeHue = true)
            } catch (e: Exception) {
                Log.w("ViewModel", "updateSettingsPartial failed: ${e.message}")
            }
        }
    }

    // Helper: centralize session handling so messages and DataMap events can both use it
    private fun handleSessionState(inSession: Boolean) {
        viewModelScope.launch {
            try {
                if (inSession) {
                    // Only apply preview if user enabled hueAutomation
                    val sd = settingsManager.loadInitialSettings()
                    if (sd.hueAutomation.lightIds.isNotEmpty() || sd.hueAutomation.groupIds.isNotEmpty()) {
                        val hueVm = HueViewModel(getApplication())
                        try { Log.d("ViewModel", "Session trigger: hueAutomation=${sd.hueAutomation}") } catch (_: Exception) {}
                        try { hueVm.refreshHueStateAndWait() } catch (_: Exception) {}
                        try { Log.d("ViewModel", "Hue VM state: bridge=${hueVm.bridgeIp.value} user=${hueVm.hueUsername.value} lights=${hueVm.lights.value.size} groups=${hueVm.groups.value.size}") } catch (_: Exception) {}
                        val session = applyHuePreview(sd.hueAutomation, hueVm)
                        if (session != null) {
                            activePreviewSession = session
                            activeHueVm = hueVm
                        }
                    }
                } else {
                    // restore if active
                    val s = activePreviewSession
                    val vm = activeHueVm
                    if (s != null && vm != null) {
                        try { restoreHuePreview(s, vm) } catch (e: Exception) { Log.w("ViewModel", "restore failed: ${e.message}") }
                    }
                    activePreviewSession = null
                    activeHueVm = null
                }
            } catch (e: Exception) {
                Log.w("ViewModel", "Session handler failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up the listener when the ViewModel is destroyed
        try { dataClient.removeListener(dataChangedListener) } catch (_: Exception) {}
        try { messageClient.removeListener(messageListener) } catch (_: Exception) {}
        Log.d("ViewModel", "Data & Message listeners removed.")
    }
}
