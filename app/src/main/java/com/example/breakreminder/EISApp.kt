package com.example.breakreminder

import androidx.compose.runtime.*
import com.example.breakreminder.screens.AppNavigation
import com.example.breakreminder.sync.AppSettingsViewModel
import com.example.breakreminder.theme.EISTheme

@Composable
fun EISApp(
    viewModel: AppSettingsViewModel,
) {
    val settings by viewModel.settings

    EISTheme(darkTheme = settings.isDarkMode) {
        AppNavigation(
            viewModel
        )
    }
}
