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
import kotlinx.coroutines.Dispatchers
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

    private val settingsManager = SettingsManager(getApplication())
    private val dataClient = Wearable.getDataClient(getApplication())
    private val messageClient = Wearable.getMessageClient(getApplication())

    // Active preview session data (if a Test preview was applied and needs explicit restore)
    private var activePreviewSession: PreviewSession? = null
    private var activeHueVm: HueViewModel? = null
    private val previewLock = Any()
    private var previewApplyInFlight: Boolean = false
    private var previewRequestedInSession: Boolean = false
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
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            // inside a lambda use return@forEach instead of 'continue'
            val path = event.dataItem.uri.path ?: return@forEach

            if (path == "/settings") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                try {
                    val mapDesc = dataMap.toString()
                    val updatedAt = if (dataMap.containsKey("updatedAt")) dataMap.getLong("updatedAt") else -1L
                    Log.d("ViewModel", "Incoming /settings DataMap: $mapDesc updatedAt=$updatedAt")
                } catch (t: Throwable) {
                    Log.d("ViewModel", "Incoming /settings DataMap logging failed: ${t.message}")
                }

                // Process and merge incoming settings off the main thread
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val current = settingsManager.loadInitialSettings()
                        try { Log.d("ViewModel", "Current stored hueAutomation: lights=${current.hueAutomation.lightIds} groups=${current.hueAutomation.groupIds} brightness=${current.hueAutomation.brightness}") } catch (_: Exception) {}

                        val hueUpdateFlag = dataMap.getBoolean("hue_update", false)
                        val scheduleUpdateFlag = dataMap.getBoolean("schedule_update", false)
                        val activityDurationUpdateFlag = dataMap.getBoolean("activity_duration_update", false)

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
                            feedbackPromptEnabled = dataMap.getBoolean("feedbackPromptEnabled", current.feedbackPromptEnabled),
                            personalizationEnabled = dataMap.getBoolean("personalizationEnabled", current.personalizationEnabled),
                            scheduleBreakIntervals = if (scheduleUpdateFlag && dataMap.containsKey("scheduleBreakIntervals")) dataMap.getBoolean("scheduleBreakIntervals") else current.scheduleBreakIntervals,
                            breakIntervalHours = if (scheduleUpdateFlag && dataMap.containsKey("breakIntervalHours")) dataMap.getInt("breakIntervalHours") else current.breakIntervalHours,
                            breakIntervalMinutes = if (scheduleUpdateFlag && dataMap.containsKey("breakIntervalMinutes")) dataMap.getInt("breakIntervalMinutes") else current.breakIntervalMinutes,
                            walkBreakDurationMinutes = if (activityDurationUpdateFlag && dataMap.containsKey("walkBreakDurationMinutes")) dataMap.getInt("walkBreakDurationMinutes") else current.walkBreakDurationMinutes,
                            napBreakDurationMinutes = if (activityDurationUpdateFlag && dataMap.containsKey("napBreakDurationMinutes")) dataMap.getInt("napBreakDurationMinutes") else current.napBreakDurationMinutes,
                            windowBreakDurationMinutes = if (activityDurationUpdateFlag && dataMap.containsKey("windowBreakDurationMinutes")) dataMap.getInt("windowBreakDurationMinutes") else current.windowBreakDurationMinutes,
                            hueAutomation = newHue
                        )

                        Log.d("ViewModel", "Applying merged settings: schedule=${merged.scheduleBreakIntervals} ${merged.breakIntervalHours}h${merged.breakIntervalMinutes}m hueLights=${merged.hueAutomation.lightIds}")

                        // Persist merged settings (applySettings performs IO internally)
                        try {
                            settingsManager.applySettings(merged)
                            Log.d("ViewModel", "Received remote settings and merged: $merged")
                        } catch (e: Exception) {
                            Log.w("ViewModel", "Failed to persist merged settings: ${e.message}")
                        }
                    } catch (e: Exception) {
                        Log.w("ViewModel", "Failed to merge remote settings: ${e.message}")
                    }
                }
            } else if (path == "/session") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val inSession = dataMap.getBoolean("isInBreakSession", false)
                Log.d("ViewModel", "Received session state change: $inSession")
                // delegate to helper; handleSessionState will perform IO as needed
                handleSessionState(inSession)
            }
        }
    }

    // Message listener for incoming commands (e.g., from watch) — used to trigger Hue test
    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        try {
            val path = event.path
            when (path) {
                "/hue/test" -> {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            // The message may arrive before the /settings DataMap is processed on the companion.
                            // Retry briefly to give the incoming DataMap time to be persisted so the test uses
                            // the freshest hueAutomation settings sent by the watch.
                            var sd = settingsManager.loadInitialSettings()
                            var attempts = 0
                            val maxAttempts = 20
                            val delayMs: Long = 250
                            while ((sd.hueAutomation.lightIds.isEmpty() && sd.hueAutomation.groupIds.isEmpty()) && attempts < maxAttempts) {
                                attempts++
                                try { kotlinx.coroutines.delay(delayMs) } catch (_: Exception) {}
                                sd = settingsManager.loadInitialSettings()
                            }

                            if (sd.hueAutomation.lightIds.isEmpty() && sd.hueAutomation.groupIds.isEmpty()) {
                                Log.w("ViewModel", "Test trigger received but no hue targets after ${attempts} attempts; aborting test preview")
                                return@launch
                            }
                            Log.d("ViewModel", "Found persisted hue targets after ${attempts} attempts: lights=${sd.hueAutomation.lightIds} groups=${sd.hueAutomation.groupIds}")

                            val hueSettings = sd.hueAutomation
                            val hueVm = HueViewModel(getApplication())
                            try { Log.d("ViewModel", "Test trigger: hueAutomation=${hueSettings}") } catch (_: Exception) {}
                            try { hueVm.refreshHueStateAndWait() } catch (_: Exception) {}
                            try { Log.d("ViewModel", "Hue VM state (test): bridge=${hueVm.bridgeIp.value} user=${hueVm.hueUsername.value} lights=${hueVm.lights.value.size} groups=${hueVm.groups.value.size}") } catch (_: Exception) {}

                            val session = applyHuePreview(hueSettings, hueVm)
                            if (session != null) {
                                activePreviewSession = session
                                activeHueVm = hueVm
                                try { sendPreviewAppliedAck() } catch (_: Exception) {}
                                // schedule a fallback restore after previewFallbackMs
                                viewModelScope.launch(Dispatchers.IO) {
                                    try {
                                        kotlinx.coroutines.delay(previewFallbackMs)
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
                    viewModelScope.launch(Dispatchers.IO) {
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

                "/session" -> {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val inSession = (event.data?.firstOrNull()?.toInt() == 1)
                            Log.d("ViewModel", "Received session state change (message): $inSession")
                            handleSessionState(inSession)
                        } catch (e: Exception) {
                            Log.w("ViewModel", "Session state change handling failed: ${e.message}")
                        }
                    }
                }

                else -> {
                    // ignore other paths
                }
            }
        } catch (t: Throwable) {
            Log.w("ViewModel", "messageListener exception: ${t.message}")
        }
    }

    init {
        if (!USE_BACKGROUND_LISTENER_SERVICE) {
            // Start listening for remote changes
            dataClient.addListener(dataChangedListener)
            // Listen for messages
            messageClient.addListener(messageListener)
            Log.d("ViewModel", "Data & Message listeners added.")
        } else {
            Log.d("ViewModel", "Data/Message listeners handled by CompanionDataLayerService.")
        }
    }

    // Update settings locally and send to the other device
    fun updateSettings(newSettings: SettingsData) {
        // Run on IO to avoid any potential main-thread suspension
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsManager.applySettings(newSettings)
            } catch (e: Exception) {
                Log.w("ViewModel", "applySettings failed in updateSettings: ${e.message}")
            }
            try {
                WearSyncHelper.sendSettings(
                    getApplication(),
                    newSettings,
                    includeSchedule = true,
                    includeHue = true,
                    includeActivityDurations = true
                )
            } catch (e: Exception) {
                Log.w("ViewModel", "sendSettings failed in updateSettings: ${e.message}")
            }
        }
    }

    // Safer partial update API: applies a modifier to the current settings so callers
    // don't accidentally overwrite unrelated fields.
    fun updateSettingsPartial(modifier: (SettingsData) -> SettingsData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = settingsManager.loadInitialSettings()
                val updated = try { modifier(current) } catch (_: Exception) { current }
                settingsManager.applySettings(updated)
                try {
                    WearSyncHelper.sendSettings(
                        getApplication(),
                        updated,
                        includeSchedule = true,
                        includeHue = true,
                        includeActivityDurations = true
                    )
                } catch (e: Exception) {
                    Log.w("ViewModel", "sendSettings failed in updateSettingsPartial: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w("ViewModel", "updateSettingsPartial failed: ${e.message}")
            }
        }
    }

    // Helper: centralize session handling so messages and DataMap events can both use it
    private fun handleSessionState(inSession: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (inSession) {
                    val shouldApply = synchronized(previewLock) {
                        previewRequestedInSession = true
                        if (activePreviewSession != null || previewApplyInFlight) {
                            false
                        } else {
                            previewApplyInFlight = true
                            true
                        }
                    }
                    if (!shouldApply) return@launch

                    // Only apply preview if user enabled hueAutomation
                    var appliedSession: PreviewSession? = null
                    var appliedVm: HueViewModel? = null
                    try {
                        val sd = settingsManager.loadInitialSettings()
                        if (sd.hueAutomation.lightIds.isNotEmpty() || sd.hueAutomation.groupIds.isNotEmpty()) {
                            val hueVm = HueViewModel(getApplication())
                            try { Log.d("ViewModel", "Session trigger: hueAutomation=${sd.hueAutomation}") } catch (_: Exception) {}
                            try { hueVm.refreshHueStateAndWait() } catch (_: Exception) {}
                            try { Log.d("ViewModel", "Hue VM state: bridge=${hueVm.bridgeIp.value} user=${hueVm.hueUsername.value} lights=${hueVm.lights.value.size} groups=${hueVm.groups.value.size}") } catch (_: Exception) {}
                            val session = applyHuePreview(sd.hueAutomation, hueVm)
                            if (session != null) {
                                appliedSession = session
                                appliedVm = hueVm
                            }
                        }
                    } finally {
                        synchronized(previewLock) {
                            previewApplyInFlight = false
                        }
                    }

                    val session = appliedSession
                    val vm = appliedVm
                    if (session != null && vm != null) {
                        val shouldRestoreImmediately = synchronized(previewLock) {
                            if (previewRequestedInSession) {
                                activePreviewSession = session
                                activeHueVm = vm
                                false
                            } else {
                                true
                            }
                        }
                        if (shouldRestoreImmediately) {
                            try { restoreHuePreview(session, vm) } catch (e: Exception) { Log.w("ViewModel", "Deferred restore failed: ${e.message}") }
                        } else {
                            try { sendPreviewAppliedAck() } catch (_: Exception) {}
                        }
                    }
                } else {
                    val shouldWaitForApply = synchronized(previewLock) {
                        previewRequestedInSession = false
                        previewApplyInFlight
                    }
                    if (shouldWaitForApply) return@launch

                    // restore if active
                    val toRestore = synchronized(previewLock) {
                        val s = activePreviewSession
                        val vm = activeHueVm
                        if (s != null && vm != null) {
                            activePreviewSession = null
                            activeHueVm = null
                            s to vm
                        } else {
                            null
                        }
                    }
                    toRestore?.let { (s, vm) ->
                        try { restoreHuePreview(s, vm) } catch (e: Exception) { Log.w("ViewModel", "restore failed: ${e.message}") }
                    }
                }
            } catch (e: Exception) {
                Log.w("ViewModel", "Session handler failed: ${e.message}")
            }
        }
    }

    // Helper: send a confirmation message to connected nodes when a preview was applied
    private fun sendPreviewAppliedAck() {
        try {
            val nodeClient = Wearable.getNodeClient(getApplication())
            val msgClient = Wearable.getMessageClient(getApplication())
            nodeClient.connectedNodes
                .addOnSuccessListener { nodes ->
                    val payload = byteArrayOf(1)
                    for (n in nodes) {
                        try {
                            msgClient.sendMessage(n.id, "/hue/applied", payload)
                                .addOnSuccessListener { Log.d("ViewModel", "Sent preview-applied ack to ${n.id}") }
                                .addOnFailureListener { e -> Log.w("ViewModel", "Failed to send preview-applied ack to ${n.id}: ${e.message}") }
                        } catch (e: Exception) {
                            Log.w("ViewModel", "preview-applied ack send failed: ${e.message}")
                        }
                    }
                }
                .addOnFailureListener { e -> Log.w("ViewModel", "Failed to query connected nodes for preview-applied ack: ${e.message}") }
        } catch (e: Exception) {
            Log.w("ViewModel", "sendPreviewAppliedAck failed: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (!USE_BACKGROUND_LISTENER_SERVICE) {
            // Clean up the listener when the ViewModel is destroyed
            try { dataClient.removeListener(dataChangedListener) } catch (_: Exception) {}
            try { messageClient.removeListener(messageListener) } catch (_: Exception) {}
            Log.d("ViewModel", "Data & Message listeners removed.")
        }
    }

    companion object {
        private const val USE_BACKGROUND_LISTENER_SERVICE = true
    }
}
