package com.example.breakreminder

import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import com.example.breakreminder.screens.AppNavigation
import com.example.breakreminder.sync.AppSettingsViewModel
import com.example.breakreminder.theme.EISTheme
import com.example.commonlibrary.SettingsData

@Composable
fun EISApp(
    viewModel: AppSettingsViewModel,
    openBreakStartOnLaunch: Boolean = false,
    onBreakStartLaunchConsumed: (() -> Unit)? = null
) {
    val settings by viewModel.settings.collectAsState(initial = SettingsData())

    EISTheme(darkTheme = settings.isDarkMode) {
        AppNavigation(
            viewModel = viewModel,
            openBreakStartOnLaunch = openBreakStartOnLaunch,
            onBreakStartLaunchConsumed = onBreakStartLaunchConsumed
        )
    }
}
