package com.example.breakreminder

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.breakreminder.screens.AppNavigation
import com.example.breakreminder.sync.AppSettingsViewModel
import com.example.breakreminder.theme.EISTheme

@Composable
fun EISApp(
    viewModel: AppSettingsViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    EISTheme(darkTheme = settings.isDarkMode) {
        AppNavigation(
            viewModel
        )
    }
}
