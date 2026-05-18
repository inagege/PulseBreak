package com.example.companionpulsebreak.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.commonlibrary.SettingsData
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StressDetectorScreen(
    viewModel: CompanionSettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState(initial = SettingsData())

    var localFeedbackPromptEnabled by remember { mutableStateOf(settings.feedbackPromptEnabled) }
    var localPersonalizationEnabled by remember { mutableStateOf(settings.personalizationEnabled) }

    val localIsDarkMode = settings.isDarkMode
    val localButtonColor = runCatching { Color(settings.buttonColor) }.getOrElse { Color(0xFF90EE90) }
    val localButtonTextColor = runCatching { Color(settings.buttonTextColor) }.getOrElse { Color(0xFF2F4F4F) }

    LaunchedEffect(settings) {
        localFeedbackPromptEnabled = settings.feedbackPromptEnabled
        localPersonalizationEnabled = settings.personalizationEnabled
    }

    val dynamicColorScheme = remember(localButtonColor, localButtonTextColor, localIsDarkMode) {
        if (localIsDarkMode) {
            androidx.compose.material3.darkColorScheme(
                primary = localButtonColor,
                onPrimary = localButtonTextColor,
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                onSurface = Color(0xFFECECEC)
            )
        } else {
            androidx.compose.material3.lightColorScheme(
                primary = localButtonColor,
                onPrimary = localButtonTextColor,
                background = Color(0xFFF0F4F5),
                surface = Color.White,
                onSurface = Color(0xFF1F1F1F)
            )
        }
    }

    fun persistStressSettings() {
        viewModel.updateSettingsPartial { current ->
            current.copy(
                feedbackPromptEnabled = localFeedbackPromptEnabled,
                personalizationEnabled = localPersonalizationEnabled
            )
        }
    }

    MaterialTheme(colorScheme = dynamicColorScheme) {
        val background = MaterialTheme.colorScheme.background
        val surface = MaterialTheme.colorScheme.surface

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Stress Detector", color = MaterialTheme.colorScheme.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = surface)
                )
            },
            bottomBar = {
                Surface(color = background, tonalElevation = 0.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("Back")
                        }
                    }
                }
            },
            containerColor = background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Stress detection",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Control feedback prompts and adaptive model personalization.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(Modifier.height(8.dp))

                        ListItem(
                            headlineContent = { Text("Ask for feedback") },
                            supportingContent = {
                                Text(
                                    "After a detected stress event, ask whether you actually feel stressed."
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = localFeedbackPromptEnabled,
                                    onCheckedChange = {
                                        localFeedbackPromptEnabled = it
                                        persistStressSettings()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = localButtonColor.copy(alpha = 0.35f),
                                        checkedThumbColor = localButtonColor,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                    )
                                )
                            }
                        )

                        ListItem(
                            headlineContent = { Text("Personalize predictions") },
                            supportingContent = {
                                Text(
                                    "Use your feedback history to tune stress predictions over time."
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = localPersonalizationEnabled,
                                    onCheckedChange = {
                                        localPersonalizationEnabled = it
                                        persistStressSettings()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = localButtonColor.copy(alpha = 0.35f),
                                        checkedThumbColor = localButtonColor,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                    )
                                )
                            }
                        )

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }

                Spacer(Modifier.weight(1f))
            }
        }
    }
}
