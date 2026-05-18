package com.example.breakreminder.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.breakreminder.sync.AppSettingsViewModel
import com.example.commonlibrary.SettingsData

@Composable
fun AppNavigation(
    viewModel: AppSettingsViewModel,
    openBreakStartOnLaunch: Boolean = false,
    onBreakStartLaunchConsumed: (() -> Unit)? = null
) {

    val navController = rememberNavController()
    // Keep start destination stable for the lifetime of this NavHost instance.
    // Otherwise, flipping openBreakStartOnLaunch back to false can rebuild the graph and
    // bounce the UI back to default_screen shortly after opening home_screen.
    val startDestination = remember {
        if (openBreakStartOnLaunch) "home_screen" else "default_screen"
    }

    LaunchedEffect(openBreakStartOnLaunch) {
        if (!openBreakStartOnLaunch) return@LaunchedEffect
        navController.navigate("home_screen") {
            launchSingleTop = true
            popUpTo("default_screen") { inclusive = true }
        }
        onBreakStartLaunchConsumed?.invoke()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // SetupScreen
        composable("setup_screen") {
            SetupScreen(
                viewModel,
                onNavigateToHome = {
                    navController.navigate("home_screen") {
                        popUpTo("setup_screen") { inclusive = true }
                    }
                },
                onDeny = {
                    // Z. B. App schließen oder Reset
                }
            )
        }

        composable("default_screen") {
            DefaultScreen(
                viewModel,
                onNavigateToSettings = {
                    navController.navigate("settings_screen")
                },
                onNavigateToHome = {
                    navController.navigate("home_screen") {
                        popUpTo("default_screen") { inclusive = true }
                    }
                }
            )
        }

        // HomeScreen
        composable("home_screen") {
            // Collect state here, in the composable scope (use a safe initial value)
            val settings by viewModel.settings.collectAsState(initial = SettingsData())

            HomeScreen(
                viewModel,
                onNavigateToSettings = {
                    navController.navigate("settings_screen")
                },
                onNavigateToSelection = {
                    // Use the collected state value in the navigation logic
                    if (settings.screenSelection == "Grid") {
                        navController.navigate("selection_screen")
                    } else {
                        navController.navigate("selection_swipe_screen")
                    }
                },
                onReturnToMonitoring = {
                    navController.navigate("default_screen") {
                        popUpTo("default_screen") { inclusive = true }
                    }
                }
            )
        }

        // SettingsScreen
        composable("settings_screen") {
            SettingsScreen(
                viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // SelectionScreen
        composable("selection_screen") {
            SelectionScreen(
                viewModel,
                onNavigateToVent = {
                    navController.navigate("air_start")
                },
                onNavigateToYoga = {
                    navController.navigate("yoga_screen")
                },
                onNavigateToWalk = {
                    navController.navigate("walk_start")
                },
                onNavigateToNap = {
                    navController.navigate("nap_start")
                }


            )
        }

        // SelectionSwipeScreen
        composable("selection_swipe_screen") {
            SelectionSwipeScreen(
                viewModel,
                onNavigateToVent = {
                    navController.navigate("air_start")
                },
                onNavigateToYoga = {
                    navController.navigate("yoga_screen")
                },
                onNavigateToWalk = {
                    navController.navigate("walk_start")
                },
                onNavigateToNap = {
                    navController.navigate("nap_start")
                }


            )
        }

        composable("yoga_screen") {
            YogaScreens(
                viewModel,
                onBackToHome = {
                    navController.navigate("default_screen") {
                        popUpTo("default_screen")
                    }
                }
            )
        }

        composable("coffee_prompt") {
            CoffeePromptScreen(
                viewModel,
                onStartMachine = { navController.navigate("coffee_video") }
            )
        }

        composable("coffee_video") {
            CoffeeVideoScreen(
                viewModel,
                onFinishCoffeeVideo = {
                    navController.navigate("coffee_screen") {
                        popUpTo("coffee_video") { inclusive = true }
                    }
                }
            )
        }

        composable("coffee_screen") {
            CoffeeScreen(
                viewModel,
                onBackToHome = {
                    navController.navigate("default_screen") {
                        popUpTo("default_screen")
                    }
                }
            )
        }

        composable("cleaning_prompt") {
            CleaningPromptScreen(
                viewModel,
                onStartCleaning = { navController.navigate("cleaning_video") }
            )
        }

        composable("cleaning_video") {
            CleaningVideoScreen(
                viewModel,
                onFinishCleaningVideo = { navController.navigate("cleaning_screen") }
            )
        }

        composable("cleaning_screen") {
            CleaningScreen(
                viewModel,
                onBackToHome = { navController.navigate("default_screen") }
            )
        }
        composable("walk_start") {
            WalkStartScreen(
                viewModel,
                onStartWalk = {
                    navController.navigate("walk_screen")
                }
            )
        }

        composable("walk_screen") {
            WalkScreen(
                viewModel,
                onBackToHome = {
                    navController.navigate("default_screen") {
                        popUpTo("default_screen")
                    }
                }
            )
        }
        composable("nap_start") {
            NapStartScreen(
                viewModel,
                onStartNap = { navController.navigate("nap_screen") }
            )
        }

        composable("nap_screen") {
            NapScreen(
                viewModel,
                onBackToHome = {
                    navController.navigate("default_screen") {
                        popUpTo("default_screen")
                    }
                }
            )
        }
        composable("air_start") {
            AirStartScreen(
                viewModel,
                onStartVent = { navController.navigate("air_screen") }
            )
        }

        composable("air_screen") {
            AirScreen(
                viewModel,
                onBackToHome = { navController.navigate("default_screen") }
            )
        }



        // Weitere Screens (Yoga, Nap, Coffee, Clean, etc.) könntest du hier ergänzen.
    }
}
