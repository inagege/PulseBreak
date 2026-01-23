package com.example.breakreminder.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.breakreminder.HeartRateReader
import com.example.breakreminder.sync.AppSettingsViewModel
import kotlinx.coroutines.delay

@Composable
fun DefaultScreen(
    viewModel: AppSettingsViewModel,
    onNavigateToHome: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var isActive by remember { mutableStateOf(true) }

    val heartRateReader = remember {
        HeartRateReader(
            context = context,
            shouldTriggerNavigation = { isActive },
            onNavigateToHome = onNavigateToHome
        )
    }

    DisposableEffect(Unit) {
        heartRateReader.startReading()
        onDispose {
            isActive = false
            heartRateReader.stopReading()
        }
    }

    // When the default screen appears, request the companion to restore any previewed lights
    LaunchedEffect(Unit) {
        HeartRateReader.sendHueRestoreMessage(context)
    }

    // Timer: compute configured interval from settings and run a countdown while DefaultScreen is active
    val configuredIntervalMillis = remember(settings.breakIntervalHours, settings.breakIntervalMinutes) {
        (settings.breakIntervalHours * 60 * 60 * 1000L) + (settings.breakIntervalMinutes * 60 * 1000L)
    }

    // remaining time state
    var remainingMillis by remember { mutableStateOf(configuredIntervalMillis) }

    // Reset timer whenever we enter the DefaultScreen or the configured interval changes
    LaunchedEffect(key1 = configuredIntervalMillis) {
        // If the user changed the interval to a smaller value than remaining, trigger immediately
        if (configuredIntervalMillis < remainingMillis) {
            try {
                onNavigateToHome()
            } catch (_: Exception) {}
            // reset remaining for next time (navigation will usually dispose this composable)
            remainingMillis = configuredIntervalMillis
            return@LaunchedEffect
        }
        // otherwise reset the timer to the new configured interval
        remainingMillis = configuredIntervalMillis
    }

    // Countdown loop
    LaunchedEffect(key1 = isActive, key2 = configuredIntervalMillis) {
        if (!isActive) return@LaunchedEffect
        // initialize remaining if zero
        if (remainingMillis <= 0L) remainingMillis = configuredIntervalMillis
        var lastTime = System.currentTimeMillis()
        while (isActive) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime
            lastTime = now
            remainingMillis = (remainingMillis - elapsed).coerceAtLeast(0L)
            if (remainingMillis <= 0L) {
                // timer expired -> trigger HomeScreen and reset
                try {
                    onNavigateToHome()
                } catch (_: Exception) {}
                remainingMillis = configuredIntervalMillis
                break
            }
            // tick every second
            delay(1000L)
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                FilledIconButton(
                    onClick = { onNavigateToSettings() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(settings.buttonColor),
                        contentColor = Color(settings.buttonTextColor)
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings Icon"
                    )
                }
            }
        }
    ) { innerPadding ->
        val adjustedPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() - 25.dp,
            bottom = innerPadding.calculateBottomPadding(),
            start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
            end = innerPadding.calculateEndPadding(LayoutDirection.Ltr)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(adjustedPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Your heart rate is being measured. You will get notifications if a pause is recommended.",
                fontSize = 18.sp,
                lineHeight = 20.sp,
                color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(195.dp)
            )
        }
    }
}