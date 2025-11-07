// app/src/main/java/com/example/breakreminder/sync/SettingsManager.kt

package com.example.companionpulsebreak.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
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
        SettingsData(
            isDarkMode = prefs.isDarkMode,
            buttonColor = prefs.buttonColor.takeIf { it != 0 } ?: 0xFF90EE90.toInt(),
            buttonTextColor = prefs.buttonTextColor.takeIf { it != 0 } ?: 0xFF2F4F4F.toInt(),
            screenSelection = prefs.screenSelection.ifEmpty { "Grid" }
        )
    }

    // applySettings ist jetzt eine suspend-Funktion
    suspend fun applySettings(settings: SettingsData) {
        context.settingsDataStore.updateData { currentPreferences ->
            currentPreferences.toBuilder()
                .setIsDarkMode(settings.isDarkMode)
                .setButtonColor(settings.buttonColor)
                .setButtonTextColor(settings.buttonTextColor)
                .setScreenSelection(settings.screenSelection)
                .build()
        }
    }

    // loadSettings kann für den initialen, blockierenden Ladevorgang verwendet werden
    suspend fun loadInitialSettings(): SettingsData {
        return settingsFlow.first()
    }
}
