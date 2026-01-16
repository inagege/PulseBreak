// app/src/main/java/com/example/breakreminder/sync/SettingsManager.kt

package com.example.companionpulsebreak.sync

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

    val settingsFlow: Flow<SettingsData> = context.settingsDataStore.data.map { prefs ->
        // Map hue automation if present
        val hueProto = prefs.hueAutomation
        val hue = if (hueProto != null) {
            // map color_mode string to HueColorMode safely
            val mode = try {
                HueColorMode.valueOf(hueProto.colorMode)
            } catch (_: Exception) {
                HueColorMode.SCENE
            }

            // scenePreviewArgb may not exist in generated classes until proto is rebuilt; use reflection to be safe
            val scenePreviewArgb = try {
                val m = hueProto::class.java.getMethod("getScenePreviewArgb")
                (m.invoke(hueProto) as? Int) ?: 0xFFFFFFFF.toInt()
            } catch (_: Throwable) {
                0xFFFFFFFF.toInt()
            }

            HueAutomationData(
                lightIds = hueProto.lightIdsList,
                groupIds = hueProto.groupIdsList,
                brightness = hueProto.brightness,
                colorArgb = hueProto.colorArgb,
                colorTemperature = hueProto.colorTemperature,
                sceneId = hueProto.sceneId.takeIf { it.isNotEmpty() },
                colorMode = mode,
                scenePreviewArgb = scenePreviewArgb
            )
        } else HueAutomationData()

        SettingsData(
            isDarkMode = prefs.isDarkMode,
            buttonColor = prefs.buttonColor.takeIf { it != 0 } ?: 0xFF90EE90.toInt(),
            buttonTextColor = prefs.buttonTextColor.takeIf { it != 0 } ?: 0xFF2F4F4F.toInt(),
            screenSelection = prefs.screenSelection.ifEmpty { "Grid" },
            hueAutomation = hue
        )
        .also {
            try { android.util.Log.d("SettingsMgr", "Loaded hueAutomation: lights=${it.hueAutomation.lightIds} groups=${it.hueAutomation.groupIds} brightness=${it.hueAutomation.brightness} colorMode=${it.hueAutomation.colorMode}") } catch (_: Exception) {}
        }
    }

    // applySettings ist jetzt eine suspend-Funktion
    suspend fun applySettings(settings: SettingsData) {
        try {
            context.settingsDataStore.updateData { currentPreferences ->
                val builder = currentPreferences.toBuilder()
                    .setIsDarkMode(settings.isDarkMode)
                    .setButtonColor(settings.buttonColor)
                    .setButtonTextColor(settings.buttonTextColor)
                    .setScreenSelection(settings.screenSelection)

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

                // build the message and set it explicitly to avoid overload ambiguity
                val hueMessage = hueBuilder.build()
                // Debug: log what hueAutomation will be persisted
                try { android.util.Log.d("SettingsMgr", "Persisting hueAutomation: lights=${hue.lightIds} groups=${hue.groupIds} brightness=${hue.brightness} colorMode=${hue.colorMode}") } catch (_: Exception) {}
                builder.setHueAutomation(hueMessage)

                builder.build()
            }
        } catch (e: Exception) {
            try { android.util.Log.w("SettingsMgr", "applySettings failed: ${e.message}", e) } catch (_: Exception) {}
            throw e
        }
    }

    // loadSettings kann für den initialen, blockierenden Ladevorgang verwendet werden
    suspend fun loadInitialSettings(): SettingsData {
        return settingsFlow.first()
    }
}
