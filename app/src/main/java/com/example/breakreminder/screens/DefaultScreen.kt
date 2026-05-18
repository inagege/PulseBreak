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
import com.example.breakreminder.HeartRateReader
import com.example.breakreminder.sync.AppSettingsViewModel
import com.example.breakreminder.background.BreakCause
import com.example.breakreminder.background.BreakNotificationHelper
import com.example.breakreminder.background.BreakSessionStateStore
import com.example.commonlibrary.SettingsData
import com.example.commonlibrary.WearSyncHelper
import com.example.breakreminder.stress.StressFeedbackConfig
import com.example.breakreminder.stress.StressFeedbackStore

@Composable
fun DefaultScreen(
    viewModel: AppSettingsViewModel,
    onNavigateToHome: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState(initial = SettingsData())

    val stopwatchMillis by viewModel.stopwatchMillis.collectAsState(initial = 0L)

    val context = LocalContext.current
    var isActive by remember { mutableStateOf(true) }
    val latestSettings by rememberUpdatedState(settings)
    val feedbackStore = remember(context) { StressFeedbackStore(context) }

    // Remember whether we've already triggered navigation to avoid double-calls
    var navigationTriggered by remember { mutableStateOf(false) }

    val heartRateReader = remember {
        HeartRateReader(
            context = context,
            shouldTriggerNavigation = { isActive },
            feedbackConfigProvider = {
                StressFeedbackConfig(
                    feedbackPromptEnabled = latestSettings.feedbackPromptEnabled,
                    personalizationEnabled = latestSettings.personalizationEnabled
                )
            },
            onStressFeedbackPromptRequested = { prediction ->
                feedbackStore.setPendingPrompt(
                    adjustedScore = prediction.adjustedScore,
                    personalizationEnabled = latestSettings.personalizationEnabled
                )
            },
            autoNavigateOnFeedbackPrompt = true,
            settingsProvider = { latestSettings },
            onNavigateToHome = {
                try { onNavigateToHome() } catch (_: Exception) {}
            }
        )
    }

    DisposableEffect(Unit) {
        heartRateReader.startReading()
        onDispose {
            isActive = false
            // Stop the sensor listener only — do not stop the ViewModel-level stopwatch here.
            heartRateReader.stopReading()
        }
    }

    // Ensure the stopwatch (managed by ViewModel) is running when this screen appears
    LaunchedEffect(Unit) {
        viewModel.startStopwatch()
        // Restart other session state when the composable enters composition
        HeartRateReader.sendHueRestoreMessage(context)
    }

    // Watch stopwatch + settings and navigate to Home when configured interval is exceeded
    LaunchedEffect(stopwatchMillis, settings.scheduleBreakIntervals, settings.breakIntervalHours, settings.breakIntervalMinutes) {
        if (navigationTriggered) return@LaunchedEffect
        if (!settings.scheduleBreakIntervals) return@LaunchedEffect

        // Combine hours + minutes from settings into a total interval in milliseconds
        val totalMinutes = settings.breakIntervalHours * 60 + settings.breakIntervalMinutes
        if (totalMinutes <= 0) return@LaunchedEffect // treat as not set

        val thresholdMillis = totalMinutes * 60 * 1000L
        if (stopwatchMillis >= thresholdMillis) {
            navigationTriggered = true
            var ok = false
            try { ok = WearSyncHelper.sendSettingsAndAwait(context, settings, includeSchedule = false, includeHue = true, timeoutMs = 3000L) } catch (_: Exception) {}
            if (!ok) {
                // fallback: try fire-and-forget
                try { WearSyncHelper.sendSettings(context, settings, includeSchedule = false, includeHue = true) } catch (_: Exception) {}
                try { kotlinx.coroutines.delay(600L) } catch (_: Exception) {}
            }
            try { BreakNotificationHelper.showBreakStartNotification(context, BreakCause.INTERVAL) } catch (_: Exception) {}
            try { BreakSessionStateStore.markSessionStarted(context) } catch (_: Exception) {}
            try { WearSyncHelper.sendSessionState(context, true) } catch (_: Exception) {}
            try { onNavigateToHome() } catch (_: Exception) {}
        }
    }

    val buttonColor = runCatching { Color(settings.buttonColor) }.getOrElse { Color(0xFF90EE90) }
    val buttonTextColor = runCatching { Color(settings.buttonTextColor) }.getOrElse { Color(0xFF2F4F4F) }

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
                        containerColor = buttonColor,
                        contentColor = buttonTextColor
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

        Box(modifier = Modifier
            .fillMaxSize()
            .padding(adjustedPadding)
        ) {
            Text(
                text = "Your physiological signals are being measured. You will get notifications if a pause is recommended.",
                fontSize = 18.sp,
                lineHeight = 20.sp,
                color = if (settings.isDarkMode) buttonColor else buttonTextColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(195.dp)
                    .align(Alignment.Center)
            )
        }
    }
}
