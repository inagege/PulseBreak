package com.example.companionpulsebreak.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel
import com.example.companionpulsebreak.sync.HueViewModel
import com.example.companionpulsebreak.sync.SettingsManager

@Composable
fun CompanionNavigation (
    viewModel: CompanionSettingsViewModel,
){
    val navController = rememberNavController()
    val startDestination = "companion_home"

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("companion_home") {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("companion_settings") },
                onNavigateToHue = { navController.navigate("hue_entry") }
            )
        }
        composable("companion_settings") {
            CompanionSettingsScreen(
                viewModel = viewModel,
                onBackToHome = { navController.navigate("companion_home") }
            )
        }

        // New Hue related routes
        composable("hue_entry") {
            // Decide whether to show connect or control screen based on existing connection
            val hueVm: HueViewModel = viewModel()
            val isConnected by hueVm.isConnected.collectAsState()
            val ctx = LocalContext.current
            val settingsManager = SettingsManager(ctx)

            if (isConnected) {
                HueAutomationHomeScreen(
                    settingsManager = settingsManager,
                    hueViewModel = hueVm,
                    settingsViewModel = viewModel,
                    onBack = { navController.navigate("companion_home") },
                    onNoConnection = {
                        navController.navigate("hue_entry") {
                            popUpTo("hue_entry") { inclusive = true }
                        }
                    }
                )
            } else {
                HueConnectScreen(
                    hueViewModel = hueVm,
                    onConnected = { navController.navigate("hue_control") }
                )
            }
        }

        composable("hue_control") {
            val hueVm: HueViewModel = viewModel()
            HueControlScreen(
                hueViewModel = hueVm,
                onBack = { navController.navigate("companion_home") }
            )
        }
    }

}