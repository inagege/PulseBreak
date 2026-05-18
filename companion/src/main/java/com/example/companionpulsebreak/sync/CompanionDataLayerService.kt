package com.example.companionpulsebreak.sync

import android.app.Application
import android.util.Log
import com.example.commonlibrary.HueColorMode
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CompanionDataLayerService : WearableListenerService() {

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
            val path = event.dataItem.uri.path ?: return@forEach
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

            when (path) {
                "/settings" -> scope.launch { mergeRemoteSettings(dataMap) }
                "/session" -> scope.launch {
                    val inSession = dataMap.getBoolean("isInBreakSession", false)
                    handleSessionState(inSession)
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/hue/test" -> scope.launch { handleSessionState(true) }
            "/hue/restore" -> scope.launch { handleSessionState(false) }
            "/session" -> scope.launch {
                val inSession = (event.data?.firstOrNull()?.toInt() == 1)
                handleSessionState(inSession)
            }
            else -> {
                // no-op
            }
        }
    }

    private suspend fun mergeRemoteSettings(dataMap: DataMap) {
        try {
            val current = settingsManager.loadInitialSettings()
            val hueUpdateFlag = dataMap.getBoolean("hue_update", false)
            val scheduleUpdateFlag = dataMap.getBoolean("schedule_update", false)
            val activityDurationUpdateFlag = dataMap.getBoolean("activity_duration_update", false)

            val hasHuePayload = (dataMap.containsKey("hue_lightIds") && (dataMap.getStringArrayList("hue_lightIds")?.isNotEmpty() == true))
                || (dataMap.containsKey("hue_groupIds") && (dataMap.getStringArrayList("hue_groupIds")?.isNotEmpty() == true))
                || dataMap.containsKey("hue_sceneId")

            val newHue = if (hueUpdateFlag && hasHuePayload) {
                current.hueAutomation.copy(
                    lightIds = dataMap.getStringArrayList("hue_lightIds")?.toList() ?: current.hueAutomation.lightIds,
                    groupIds = dataMap.getStringArrayList("hue_groupIds")?.toList() ?: current.hueAutomation.groupIds,
                    brightness = dataMap.getInt("hue_brightness", current.hueAutomation.brightness),
                    colorArgb = dataMap.getInt("hue_colorArgb", current.hueAutomation.colorArgb),
                    colorTemperature = dataMap.getInt("hue_colorTemperature", current.hueAutomation.colorTemperature),
                    sceneId = dataMap.getString("hue_sceneId") ?: current.hueAutomation.sceneId,
                    colorMode = try {
                        HueColorMode.valueOf(dataMap.getString("hue_colorMode") ?: current.hueAutomation.colorMode.name)
                    } catch (_: Exception) {
                        current.hueAutomation.colorMode
                    },
                    scenePreviewArgb = dataMap.getInt("hue_scenePreviewArgb", current.hueAutomation.scenePreviewArgb)
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

    private suspend fun handleSessionState(inSession: Boolean) {
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
                if (!shouldApply) return

                var appliedSession: PreviewSession? = null
                var appliedVm: HueViewModel? = null
                try {
                    val settings = settingsManager.loadInitialSettings()
                    if (settings.hueAutomation.lightIds.isEmpty() && settings.hueAutomation.groupIds.isEmpty()) {
                        return
                    }

                    val hueVm = HueViewModel(application as Application)
                    try {
                        hueVm.refreshHueStateAndWait()
                    } catch (_: Exception) {
                    }
                    val session = applyHuePreview(settings.hueAutomation, hueVm)
                    if (session != null) {
                        appliedSession = session
                        appliedVm = hueVm
                    }
                } finally {
                    synchronized(previewLock) {
                        previewApplyInFlight = false
                    }
                }

                val session = appliedSession ?: return
                val vm = appliedVm ?: return
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
                    try {
                        restoreHuePreview(session, vm)
                    } catch (e: Exception) {
                        Log.w(TAG, "Deferred restore after apply failed: ${e.message}")
                    }
                } else {
                    sendPreviewAppliedAck()
                }
            } else {
                val shouldWaitForApply = synchronized(previewLock) {
                    previewRequestedInSession = false
                    previewApplyInFlight
                }
                if (shouldWaitForApply) return

                val toRestore: Pair<PreviewSession, HueViewModel>? = synchronized(previewLock) {
                    val session = activePreviewSession
                    val vm = activeHueVm
                    if (session != null && vm != null) {
                        activePreviewSession = null
                        activeHueVm = null
                        session to vm
                    } else {
                        null
                    }
                }
                toRestore?.let { (session, vm) ->
                    try {
                        restoreHuePreview(session, vm)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed restoring hue preview: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleSessionState failed: ${e.message}")
        }
    }

    private fun sendPreviewAppliedAck() {
        try {
            val nodeClient = Wearable.getNodeClient(applicationContext)
            val msgClient = Wearable.getMessageClient(applicationContext)
            nodeClient.connectedNodes
                .addOnSuccessListener { nodes ->
                    val payload = byteArrayOf(1)
                    for (n in nodes) {
                        msgClient.sendMessage(n.id, "/hue/applied", payload)
                            .addOnFailureListener { e ->
                                Log.w(TAG, "Ack send failed to ${n.id}: ${e.message}")
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Node query failed for preview ack: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "sendPreviewAppliedAck failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CompanionDataLayerSvc"

        private val previewLock = Any()
        private var activePreviewSession: PreviewSession? = null
        private var activeHueVm: HueViewModel? = null
        private var previewApplyInFlight: Boolean = false
        private var previewRequestedInSession: Boolean = false
    }
}
