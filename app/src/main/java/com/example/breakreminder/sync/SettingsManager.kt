// app/src/main/java/com/example/breakreminder/sync/SettingsManager.kt

package com.example.breakreminder.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import com.example.commonlibrary.HueAutomationData
import com.example.commonlibrary.HueColorMode
import com.example.commonlibrary.SettingsData
import com.example.commonlibrary.SettingsPreferences
import com.example.commonlibrary.SettingsSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Extension property to create the DataStore instance
private val Context.settingsDataStore: DataStore<SettingsPreferences> by dataStore(
    fileName = "settings.pb",
    serializer = SettingsSerializer
)

class SettingsManager(private val context: Context) {

    // Map DataStore prefs to SettingsData including hueAutomation and scheduling fields
    val settingsFlow: Flow<SettingsData> = context.settingsDataStore.data.map { prefs ->
        // Map hue automation if present
        val hueProto = try {
            val m = prefs::class.java.getMethod("getHueAutomation")
            m.invoke(prefs)
        } catch (_: Throwable) { null }

        val hue = if (hueProto != null) {
            val mode = try {
                val colorMode = hueProto::class.java.getMethod("getColorMode").invoke(hueProto) as? String
                colorMode?.let { HueColorMode.valueOf(it) } ?: HueColorMode.SCENE
            } catch (_: Exception) { HueColorMode.SCENE }

            val scenePreviewArgb = try {
                val m = hueProto::class.java.getMethod("getScenePreviewArgb")
                (m.invoke(hueProto) as? Int) ?: 0xFFFFFFFF.toInt()
            } catch (_: Throwable) {
                0xFFFFFFFF.toInt()
            }

            val lightIds = try { (hueProto::class.java.getMethod("getLightIdsList").invoke(hueProto) as? List<*>)?.mapNotNull { it?.toString() } } catch (_: Throwable) { null }
            val groupIds = try { (hueProto::class.java.getMethod("getGroupIdsList").invoke(hueProto) as? List<*>)?.mapNotNull { it?.toString() } } catch (_: Throwable) { null }
            val brightness = try { hueProto::class.java.getMethod("getBrightness").invoke(hueProto) as? Int } catch (_: Throwable) { null }
            val colorArgb = try { hueProto::class.java.getMethod("getColorArgb").invoke(hueProto) as? Int } catch (_: Throwable) { null }
            val colorTemperature = try { hueProto::class.java.getMethod("getColorTemperature").invoke(hueProto) as? Int } catch (_: Throwable) { null }
            val sceneId = try { hueProto::class.java.getMethod("getSceneId").invoke(hueProto) as? String } catch (_: Throwable) { null }

            HueAutomationData(
                lightIds = lightIds?.toList() ?: emptyList(),
                groupIds = groupIds?.toList() ?: emptyList(),
                brightness = brightness ?: 100,
                colorArgb = colorArgb ?: 0xFFFFFFFF.toInt(),
                colorTemperature = colorTemperature ?: 0,
                sceneId = sceneId?.takeIf { it.isNotEmpty() },
                colorMode = mode,
                scenePreviewArgb = scenePreviewArgb
            )
        } else HueAutomationData()

        // Read new break scheduling fields via reflection in case protobuf generated class hasn't been regenerated yet.
        val scheduleBreakIntervals = try {
            val m = prefs::class.java.getMethod("getScheduleBreakIntervals")
            (m.invoke(prefs) as? Boolean) ?: false
        } catch (_: Throwable) { false }

        val breakIntervalHours = try {
            val m = prefs::class.java.getMethod("getBreakIntervalHours")
            (m.invoke(prefs) as? Int) ?: 0
        } catch (_: Throwable) { 0 }

        val breakIntervalMinutes = try {
            val m = prefs::class.java.getMethod("getBreakIntervalMinutes")
            (m.invoke(prefs) as? Int) ?: 0
        } catch (_: Throwable) { 0 }

        SettingsData(
            isDarkMode = prefs.isDarkMode,
            buttonColor = prefs.buttonColor.takeIf { it != 0 } ?: 0xFF90EE90.toInt(),
            buttonTextColor = prefs.buttonTextColor.takeIf { it != 0 } ?: 0xFF2F4F4F.toInt(),
            screenSelection = prefs.screenSelection.ifEmpty { "Grid" },
            scheduleBreakIntervals = scheduleBreakIntervals,
            breakIntervalHours = breakIntervalHours,
            breakIntervalMinutes = breakIntervalMinutes,
            hueAutomation = hue
        )
    }

    // applySettings now persists break scheduling and hueAutomation, using reflection where necessary
    suspend fun applySettings(settings: SettingsData) {
        context.settingsDataStore.updateData { currentPreferences ->
            val builder = currentPreferences.toBuilder()
                .setIsDarkMode(settings.isDarkMode)
                .setButtonColor(settings.buttonColor)
                .setButtonTextColor(settings.buttonTextColor)
                .setScreenSelection(settings.screenSelection)

            // set break scheduling fields via reflection (builder may not have these methods until proto is regenerated)
            try {
                val m = builder::class.java.getMethod("setScheduleBreakIntervals", Boolean::class.javaPrimitiveType)
                m.invoke(builder, settings.scheduleBreakIntervals)
            } catch (_: Throwable) {}

            try {
                val m = builder::class.java.getMethod("setBreakIntervalHours", Int::class.javaPrimitiveType)
                m.invoke(builder, settings.breakIntervalHours)
            } catch (_: Throwable) {}

            try {
                val m = builder::class.java.getMethod("setBreakIntervalMinutes", Int::class.javaPrimitiveType)
                m.invoke(builder, settings.breakIntervalMinutes)
            } catch (_: Throwable) {}

            // update hue automation
            val hue = settings.hueAutomation
            val hueBuilder = com.example.commonlibrary.HueAutomationSettings.newBuilder()
                .clearLightIds()
                .clearGroupIds()
                .addAllLightIds(hue.lightIds)
                .addAllGroupIds(hue.groupIds)
                .setBrightness(hue.brightness)
                .setColorArgb(hue.colorArgb)
                .setColorTemperature(hue.colorTemperature)
                .setSceneId(hue.sceneId ?: "")
                .setColorMode(hue.colorMode.name)

            // set scenePreviewArgb via reflection if available on the builder
            try {
                val m = hueBuilder::class.java.getMethod("setScenePreviewArgb", Int::class.javaPrimitiveType)
                m.invoke(hueBuilder, hue.scenePreviewArgb)
            } catch (_: Throwable) {
                // ignore if generated builder doesn't have the method yet
            }

            val hueMessage = hueBuilder.build()
            try { builder.setHueAutomation(hueMessage) } catch (_: Throwable) {}

            builder.build()
        }
    }

    // loadSettings can be used for initial blocking load
    suspend fun loadInitialSettings(): SettingsData {
        return settingsFlow.first()
    }
}
