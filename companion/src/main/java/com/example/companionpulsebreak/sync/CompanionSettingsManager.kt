package com.example.companionpulsebreak.sync

import android.content.Context
import android.util.Log
import com.example.commonlibrary.SettingsData
import com.google.android.gms.wearable.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object CompanionSettingsManager: ICompanionSettingsManager {

    private const val SETTINGS_PATH = "/settings"
    private const val PREFS_NAME = "EISPreferences"

    // Keep reference to the listener so it can be removed if needed
    private var listener: DataClient.OnDataChangedListener? = null

    /**
     * Start listening for settings changes sent from the companion app.
     * The callback [onSettingsChanged] updates your UI/state.
     */

// ... existing code ...
    override fun startListening(context: Context, onSettingsChanged: (SettingsData) -> Unit) {
        if (listener != null) return // Already listening

        GlobalScope.launch {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    listener = DataClient.OnDataChangedListener { dataEvents ->
                        for (event in dataEvents) {
                            if (event.type == DataEvent.TYPE_CHANGED &&
                                event.dataItem.uri.path == SETTINGS_PATH
                            ) {
                                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                                dataMap.getLong("updatedAt", 0L)
                                val newSettings = SettingsData(
                                    isDarkMode = dataMap.getBoolean("isDarkMode"),
                                    buttonColor = dataMap.getInt("buttonColor"),
                                    buttonTextColor = dataMap.getInt("buttonTextColor"),
                                    screenSelection = dataMap.getString("screenSelection") ?: "Grid"
                                )
                                Log.d("SettingsManager", "Received settings update: $newSettings")
                                applySettings(context, newSettings)
                                onSettingsChanged(newSettings)
                            }
                        }
                    }
                    Wearable.getDataClient(context).addListener(listener!!)
                        .addOnSuccessListener { Log.d("SettingsManager", "Successfully added listener.") }
                        .addOnFailureListener { e -> Log.e("SettingsManager", "Failed to add listener.", e) }
                } else {
                    Log.w("SettingsManager", "No node connected.")
                }
            } catch (e: Exception) {
                Log.e("SettingsManager", "Failed to check for connection.", e)
            }
        }
    }

    /**
     * Stop listening for settings changes (optional).
     */
    override fun stopListening(context: Context) {
        listener?.let {
            Wearable.getDataClient(context).removeListener(it)
        }
        listener = null
    }

    /**
     * Apply settings to SharedPreferences.
     */
    override fun applySettings(context: Context, settings: SettingsData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("isDarkMode", settings.isDarkMode)
            .putInt("buttonColor", settings.buttonColor)
            .putInt("buttonTextColor", settings.buttonTextColor)
            .putString("ScreenSelection", settings.screenSelection)
            .apply()
    }

    /**
     * Load current settings from SharedPreferences.
     */
    override fun loadSettings(context: Context): SettingsData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SettingsData(
            isDarkMode = prefs.getBoolean("isDarkMode", false),
            buttonColor = prefs.getInt("buttonColor", 0xFF90EE90.toInt()),
            buttonTextColor = prefs.getInt("buttonTextColor", 0xFF2F4F4F.toInt()),
            screenSelection = prefs.getString("ScreenSelection", "Grid") ?: "Grid"
        )
    }
}