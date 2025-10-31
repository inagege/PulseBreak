package com.example.breakreminder.sync

import android.content.Context
import com.example.commonlibrary.SettingsData

interface ISettingsManager {
    fun startListening(context: Context, onSettingsChanged: (SettingsData) -> Unit)
    fun stopListening(context: Context)
    fun applySettings(context: Context, settings: SettingsData)
    fun loadSettings(context: Context): SettingsData
}

