package com.example.breakreminder.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.breakreminder.sync.AppSettingsViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.example.commonlibrary.SettingsData
import com.example.breakreminder.background.BreakMonitoringService
import com.example.breakreminder.background.BreakNotificationHelper
import com.example.breakreminder.background.BreakSessionStateStore
import com.example.breakreminder.HeartRateReader
import com.example.breakreminder.stress.StressFeedbackStore
import com.example.commonlibrary.WearSyncHelper
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    viewModel: AppSettingsViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToSelection: () -> Unit,
    onReturnToMonitoring: () -> Unit
) {
    val context = LocalContext.current
    val feedbackStore = remember(context) { StressFeedbackStore(context) }
    var pendingFeedback by remember { mutableStateOf<StressFeedbackStore.PendingStressFeedback?>(null) }
    var feedbackScore by remember { mutableStateOf(3) }

    // Reset stopwatch to zero when this screen appears
    LaunchedEffect(Unit) {
        viewModel.stopStopwatch()
        viewModel.resetStopwatch()
        BreakMonitoringService.resetInterval(context)
        pendingFeedback = feedbackStore.getPendingPrompt()
    }

    LaunchedEffect(pendingFeedback) {
        if (pendingFeedback != null) {
            feedbackScore = 3
        }
    }

    val settings by viewModel.settings.collectAsState(initial = SettingsData())

    val buttonColor = runCatching { Color(settings.buttonColor) }.getOrElse { Color(0xFF90EE90) }
    val buttonTextColor = runCatching { Color(settings.buttonTextColor) }.getOrElse { Color(0xFF2F4F4F) }

    fun startStressBreak() {
        try {
            WearSyncHelper.sendSettings(
                context = context,
                settings = settings,
                includeSchedule = false,
                includeHue = true
            )
        } catch (_: Exception) {
        }
        try {
            BreakSessionStateStore.markSessionStarted(context)
        } catch (_: Exception) {
        }
        try {
            WearSyncHelper.sendSessionState(context, true)
        } catch (_: Exception) {
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
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Need a break?",
                fontSize = 24.sp,
                color = if (settings.isDarkMode) buttonColor else buttonTextColor
            )
            Spacer(modifier = Modifier.height(26.dp))
            
            pendingFeedback?.let {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 0.dp,
                    color = buttonColor.copy(alpha = if (settings.isDarkMode) 0.20f else 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "How stressed do you feel?",
                            fontSize = 18.sp,
                            color = if (settings.isDarkMode) buttonColor else buttonTextColor
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Slider(
                            value = feedbackScore.toFloat(),
                            onValueChange = { value ->
                                feedbackScore = value.roundToInt().coerceIn(1, 4)
                            },
                            valueRange = 1f..4f,
                            steps = 2,
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                "1 Not stressed",
                                fontSize = 12.sp,
                                color = if (settings.isDarkMode) buttonColor else buttonTextColor
                            )
                            Text(
                                "4 Very stressed",
                                fontSize = 12.sp,
                                color = if (settings.isDarkMode) buttonColor else buttonTextColor
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val shouldStartBreak = feedbackStore.submitPendingFeedback(feedbackScore)
                                BreakNotificationHelper.dismissStressFeedbackPrompt(context)
                                pendingFeedback = null
                                if (shouldStartBreak) {
                                    startStressBreak()
                                } else {
                                    HeartRateReader.sendHueRestoreMessage(context)
                                    onReturnToMonitoring()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor,
                                contentColor = buttonTextColor
                            )
                        ) {
                            Text("Submit")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            Button(
                onClick = { onNavigateToSelection() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonTextColor
                ),
                modifier = Modifier
                    .width(160.dp)
                    .height(50.dp)
            ) {
                Text("Start Session", fontSize = 18.sp)
            }
        }
    }
}
