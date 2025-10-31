package com.example.breakreminder.sync

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.example.commonlibrary.SettingsData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.commonlibrary.WearSyncHelper

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

    private val context = application.applicationContext

    // Global settings state (observed by all screens)
    var settings = mutableStateOf(SettingsManager.loadSettings(context))
        private set

    init {
        // Start global listener for companion app updates
        SettingsManager.startListening(context) { newSettings ->
            settings.value = newSettings
        }
    }

    fun updateSettings(newSettings: SettingsData) {
        settings.value = newSettings
        // Send to companion
        WearSyncHelper.sendSettings(context, newSettings)
        // Persist locally
        SettingsManager.applySettings(context, newSettings)
    }
}
