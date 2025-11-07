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

    private val context = application.applicationContext
    private val settingsManager = SettingsManager(context) // Instantiate the new manager
    private val dataClient = Wearable.getDataClient(context)

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
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/settings") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val newSettings = SettingsData(
                    isDarkMode = dataMap.getBoolean("isDarkMode"),
                    buttonColor = dataMap.getInt("buttonColor"),
                    buttonTextColor = dataMap.getInt("buttonTextColor"),
                    screenSelection = dataMap.getString("screenSelection") ?: "Grid"
                )
                // When remote data arrives, save it locally. DataStore will then emit the update.
                viewModelScope.launch {
                    settingsManager.applySettings(newSettings)
                    Log.d("ViewModel", "Received and applied remote settings: $newSettings")
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
            // Persist locally using the new suspend function
            settingsManager.applySettings(newSettings)
            // Send to companion
            WearSyncHelper.sendSettings(context, newSettings)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up the listener when the ViewModel is destroyed
        dataClient.removeListener(dataChangedListener)
        Log.d("ViewModel", "Data listener removed.")
    }
}
