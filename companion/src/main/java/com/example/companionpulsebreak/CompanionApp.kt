package com.example.companionpulsebreak

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel
import com.example.companionpulsebreak.theme.CompanionTheme
import com.example.companionpulsebreak.screens.CompanionNavigation

@Composable
fun CompanionApp(
    viewModel: CompanionSettingsViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    CompanionTheme(darkTheme = settings.isDarkMode) {
        CompanionNavigation(
            viewModel
        )
    }
}