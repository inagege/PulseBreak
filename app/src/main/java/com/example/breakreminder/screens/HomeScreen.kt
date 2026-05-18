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
import com.example.commonlibrary.SettingsData
import com.example.breakreminder.background.BreakMonitoringService
import com.example.breakreminder.stress.StressFeedbackStore

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

    // Reset stopwatch to zero when this screen appears
    LaunchedEffect(Unit) {
        viewModel.stopStopwatch()
        viewModel.resetStopwatch()
        BreakMonitoringService.resetInterval(context)
        pendingFeedback = feedbackStore.getPendingPrompt()
    }

    val settings by viewModel.settings.collectAsState(initial = SettingsData())

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
                        Button(
                            onClick = {
                                feedbackStore.submitPendingFeedback(userFeelsStressed = true)
                                pendingFeedback = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor,
                                contentColor = buttonTextColor
                            )
                        ) {
                            Text("Yes, good idea")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                feedbackStore.submitPendingFeedback(userFeelsStressed = false)
                                pendingFeedback = null
                                onReturnToMonitoring()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor,
                                contentColor = buttonTextColor
                            )
                        ) {
                            Text("No, not stressed")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                // "No, no time" still indicates stress in most cases,
                                // but user cannot take a break now.
                                feedbackStore.submitPendingFeedback(userFeelsStressed = true)
                                pendingFeedback = null
                                onReturnToMonitoring()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor,
                                contentColor = buttonTextColor
                            )
                        ) {
                            Text("No, no time")
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
