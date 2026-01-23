package com.example.companionpulsebreak.sync

import android.util.Log
import com.example.commonlibrary.HueAutomationData
import com.example.companionpulsebreak.screens.computeTestAffectedLights
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

// Lightweight holder for preview session data so the preview can be restored later
data class PreviewSession(
    val affectedLights: List<HueLight>,
    val rawStates: Map<String, org.json.JSONObject?>,
    val originalStates: Map<String, Pair<Boolean, Int>>,
    val settings: HueAutomationData
)

suspend fun applyHuePreview(settings: HueAutomationData, hueViewModel: HueViewModel): PreviewSession? {
    try {
        // Sanity: ensure bridge info present
        val ip = hueViewModel.bridgeIp.value
        val user = hueViewModel.hueUsername.value
        if (ip.isNullOrEmpty() || user.isNullOrEmpty()) {
            Log.w("HueAutomation", "Hue not configured: cannot apply preview")
            return null
        }

        val allLights = hueViewModel.lights.value
        // Determine affected lights from explicit light IDs or from selected groups
        val affected: List<HueLight> = if (settings.lightIds.isNotEmpty()) {
            computeTestAffectedLights(allLights, settings, requireOn = false)
        } else if (settings.groupIds.isNotEmpty()) {
            // resolve group members from the current groups state
            val groups = hueViewModel.groups.value
            val memberIds = groups.filter { g -> settings.groupIds.contains(g.id) }
                .flatMap { it.lightIds }
                .toSet()
            allLights.filter { memberIds.contains(it.id) }
        } else {
            emptyList()
        }
         if (affected.isEmpty()) {
             Log.d("HueAutomation", "applyHuePreview aborted: no affected lights")
             return null
         }

         val originalStates = allLights.associate { it.id to (it.on to it.brightness) }

         val rawStates = try {
             val allRaw = try { hueViewModel.fetchAllLightsRawStates() } catch (e: Exception) {
                 Log.w("HueAutomation", "fetchAllLightsRawStates failed: ${e.message}")
                 emptyMap<String, org.json.JSONObject?>()
             }
             allRaw.filterKeys { k -> affected.any { it.id == k } }
         } catch (e: Exception) {
             Log.w("HueAutomation", "failed to prepare raw light states: ${e.message}")
             emptyMap<String, org.json.JSONObject?>()
         }

         val targetBriPercent = settings.brightness.coerceIn(0, 100)

         // Apply preview (reuse logic from previous implementation)
         try {
             val affectedIds = affected.map { it.id }.toMutableSet()
             val groupsAll = hueViewModel.groups.value

             val candidateGroups = groupsAll.sortedByDescending { it.lightIds.size }
                 .filter { g -> g.lightIds.all { affectedIds.contains(it) } }

             val deferreds = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

             coroutineScope {
                 for (g in candidateGroups) {
                     val members = g.lightIds.filter { affectedIds.contains(it) }
                     if (members.isEmpty()) continue

                     val d = async {
                         try {
                             when (settings.colorMode) {
                                 com.example.commonlibrary.HueColorMode.CUSTOM_COLOR -> {
                                     hueViewModel.setGroupColorAndBrightnessSuspend(g.id, settings.colorArgb, targetBriPercent, immediate = true)
                                 }
                                 com.example.commonlibrary.HueColorMode.CUSTOM_WHITE -> {
                                     hueViewModel.setGroupCtAndBrightnessForGroupSuspend(g.id, settings.colorTemperature, targetBriPercent, immediate = true)
                                 }
                                 com.example.commonlibrary.HueColorMode.SCENE -> {
                                     val previewArgb = settings.scenePreviewArgb
                                     if (previewArgb != 0) {
                                         hueViewModel.setGroupColorAndBrightnessSuspend(g.id, previewArgb, targetBriPercent, immediate = true)
                                     } else {
                                         hueViewModel.setGroupBrightnessForGroupSuspend(g.id, targetBriPercent, immediate = true)
                                     }
                                 }
                             }
                         } catch (e: Exception) {
                             Log.w("HueAutomation", "apply preview group ${g.id} failed: ${e.message}")
                         }
                         Unit
                     }
                     deferreds.add(d)
                     members.forEach { affectedIds.remove(it) }
                 }

                 val remaining = affected.filter { affectedIds.contains(it.id) }
                 for (l in remaining) {
                     val d = async {
                         try {
                             when (settings.colorMode) {
                                 com.example.commonlibrary.HueColorMode.CUSTOM_COLOR -> {
                                     if (l.supportsColor) {
                                         hueViewModel.setColorAndBrightnessForLightSuspend(l.id, settings.colorArgb, targetBriPercent, immediate = true)
                                     } else {
                                         hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent, immediate = true)
                                     }
                                 }
                                 com.example.commonlibrary.HueColorMode.CUSTOM_WHITE -> {
                                     if (l.supportsCt) {
                                         hueViewModel.setCtAndBrightnessForLightSuspend(l.id, settings.colorTemperature, targetBriPercent, immediate = true)
                                     } else {
                                         hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent, immediate = true)
                                     }
                                 }
                                 com.example.commonlibrary.HueColorMode.SCENE -> {
                                     val previewArgb = settings.scenePreviewArgb
                                     if (settings.lightIds.isNotEmpty() && previewArgb != 0 && l.supportsColor) {
                                         hueViewModel.setColorAndBrightnessForLightSuspend(l.id, previewArgb, targetBriPercent, immediate = true)
                                     } else {
                                         hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent, immediate = true)
                                     }
                                 }
                             }
                         } catch (e: Exception) {
                             Log.w("HueAutomation", "apply preview ${l.id} failed: ${e.message}")
                         }
                         Unit
                     }
                     deferreds.add(d)
                 }

                 try { deferreds.awaitAll() } catch (e: Exception) { Log.w("HueAutomation", "apply preview awaitAll failed: ${e.message}", e) }
             }
         } catch (e: Exception) {
             Log.w("HueAutomation", "apply preview grouped failed: ${e.message}", e)
         }

         return PreviewSession(affected, rawStates, originalStates, settings)
     } catch (e: Exception) {
         Log.w("HueAutomation", "applyHuePreview exception: ${e.message}")
         return null
     }
 }

 suspend fun restoreHuePreview(session: PreviewSession, hueViewModel: HueViewModel) {
     try {
         coroutineScope {
             session.affectedLights.map { l -> async {
                 val id = l.id
                 val raw = session.rawStates[id]
                 try {
                     if (raw != null) {
                         hueViewModel.restoreLightStateFromRaw(id, raw)
                     } else {
                         val pair = session.originalStates[id]
                         if (pair != null) {
                             val (wasOn, bri) = pair
                             if (wasOn) {
                                 hueViewModel.setLightBrightnessSuspend(id, bri)
                                 delay(10)
                                 hueViewModel.setLightOnSuspend(id, true)
                             } else {
                                 hueViewModel.setLightOnSuspend(id, false)
                             }
                         }
                     }
                 } catch (e: Exception) {
                     Log.w("HueAutomation", "restore state $id failed: ${e.message}")
                 }
             } }.awaitAll()
         }
     } catch (e: Exception) {
         Log.w("HueAutomation", "restoreHuePreview failed: ${e.message}")
     }
 }

 suspend fun performHueTest(settings: HueAutomationData, hueViewModel: HueViewModel) {
     val session = applyHuePreview(settings, hueViewModel) ?: return

     // Show for a short duration then restore (UI Test button behavior)
     val showDurationMs = 3000L
     try { delay(showDurationMs) } catch (_: Exception) {}

     try {
         restoreHuePreview(session, hueViewModel)
     } catch (e: Exception) {
         Log.w("HueAutomation", "performHueTest restore failed: ${e.message}")
     }
 }
