package com.example.companionpulsebreak

// Copy logic of EISAPP here
import androidx.compose.runtime.*
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel
import com.example.companionpulsebreak.theme.CompanionTheme
import com.example.companionpulsebreak.screens.CompanionNavigation

@Composable
fun CompanionApp(
    viewModel: CompanionSettingsViewModel,
) {
    val settings by viewModel.settings

    CompanionTheme(darkTheme = settings.isDarkMode) {
        CompanionNavigation(
            viewModel
        )
    }
}