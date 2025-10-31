package com.example.companionpulsebreak.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel

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
                onNavigateToSettings = { navController.navigate("companion_settings") }
            )
        }
        composable("companion_settings") {
            CompanionSettingsScreen(
                viewModel = viewModel,
                onBackToHome = { navController.navigate("companion_home") }
            )
        }
    }

}