package com.example.companionpulsebreak.sync

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.example.commonlibrary.SettingsData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.commonlibrary.WearSyncHelper

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

    private val context = application.applicationContext

    // Global settings state (observed by all screens)
    var settings = mutableStateOf(CompanionSettingsManager.loadSettings(context))
        private set

    init {
        // Start global listener for companion app updates
        CompanionSettingsManager.startListening(context) { newSettings ->
            settings.value = newSettings
        }
    }

    fun updateSettings(newSettings: SettingsData) {
        settings.value = newSettings
        // Send to companion
        WearSyncHelper.sendSettings(context, newSettings)
        // Persist locally
        CompanionSettingsManager.applySettings(context, newSettings)
    }
}
