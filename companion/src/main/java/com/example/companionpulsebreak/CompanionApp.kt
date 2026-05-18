package com.example.companionpulsebreak

import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel
import com.example.companionpulsebreak.theme.CompanionTheme
import com.example.companionpulsebreak.screens.CompanionNavigation
import com.example.commonlibrary.SettingsData

@Composable
fun CompanionApp(
    viewModel: CompanionSettingsViewModel,
) {
    // Provide a safe initial SettingsData to avoid lifecycle-related collection races
    val settings by viewModel.settings.collectAsState(initial = SettingsData())

    CompanionTheme(darkTheme = settings.isDarkMode) {
        CompanionNavigation(
            viewModel
        )
    }
}