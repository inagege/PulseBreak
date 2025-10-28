package com.example.breakreminder.presentation

import android.widget.Toast
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun EISApp(
    firstStart: Boolean,
    isDarkMode: Boolean,
    buttonColor: Color,
    buttonTextColor: Color,
    screenSelection: String
) {

    // States (optional), falls du dynamische Änderungen zulassen willst:
    var darkMode by remember { mutableStateOf(isDarkMode) }
    var currentButtonColor by remember { mutableStateOf(buttonColor) }
    var currentButtonTextColor by remember { mutableStateOf(buttonTextColor) }
    var currentScreenSelection by remember { mutableStateOf(screenSelection) }

    // Unser Material-3-Theme
    EISTheme(darkTheme = darkMode) {
        // Navigation
        AppNavigation(
            firstStart = firstStart,
            isDarkMode = darkMode,
            buttonColor = currentButtonColor,
            buttonTextColor = currentButtonTextColor,
            screenSelection = currentScreenSelection,
            onThemeChanged = { newDarkMode -> darkMode = newDarkMode },
            onButtonColorChanged = { bc, btc ->
                currentButtonColor = bc
                currentButtonTextColor = btc
            },
            onScreenSelectionChanged = { newSelection ->
                currentScreenSelection = newSelection
            }
        )
    }
}
