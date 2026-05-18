package com.example.companionpulsebreak.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.commonlibrary.SettingsData
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel

private data class ManualSection(
    val title: String,
    val points: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionManualScreen(
    viewModel: CompanionSettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState(initial = SettingsData())
    val buttonColor = runCatching { Color(settings.buttonColor) }.getOrElse { Color(0xFF90EE90) }
    val buttonTextColor = runCatching { Color(settings.buttonTextColor) }.getOrElse { Color(0xFF2F4F4F) }
    val isDarkMode = settings.isDarkMode

    val dynamicColorScheme = remember(buttonColor, buttonTextColor, isDarkMode) {
        if (isDarkMode) {
            androidx.compose.material3.darkColorScheme(
                primary = buttonColor,
                onPrimary = buttonTextColor,
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                onSurface = Color(0xFFECECEC)
            )
        } else {
            androidx.compose.material3.lightColorScheme(
                primary = buttonColor,
                onPrimary = buttonTextColor,
                background = Color(0xFFF0F4F5),
                surface = Color.White,
                onSurface = Color(0xFF1F1F1F)
            )
        }
    }

    val sections = remember {
        listOf(
            ManualSection(
                title = "Getting Started",
                points = listOf(
                    "Open Pulse Break on your phone and watch with Bluetooth enabled.",
                    "Keep both apps running for a few seconds so settings sync can complete.",
                    "Use this companion app to configure behavior; your watch handles reminders and break sessions."
                )
            ),
            ManualSection(
                title = "Home Menu",
                points = listOf(
                    "Light Setup configures Philips Hue automation for break sessions.",
                    "Break Management controls scheduled break reminders and break durations.",
                    "Stress Detector toggles feedback prompts and model personalization.",
                    "Design Options changes app appearance and watch screen style."
                )
            ),
            ManualSection(
                title = "Light Setup (Hue)",
                points = listOf(
                    "Tap Discover to find Hue bridges on your local network.",
                    "If discovery fails, enter the bridge IP manually and tap Use IP.",
                    "Press the bridge link button, then pair.",
                    "Select rooms/zones, brightness, and color mode for break-time lighting."
                )
            ),
            ManualSection(
                title = "Break Management",
                points = listOf(
                    "Enable Schedule break intervals for periodic reminders.",
                    "Choose the interval in hours and minutes.",
                    "Set walk, nap, and window break durations in minutes."
                )
            ),
            ManualSection(
                title = "Stress Detector",
                points = listOf(
                    "Ask for feedback shows a prompt after stress detections.",
                    "Personalize predictions uses your feedback to adapt over time.",
                    "Both options sync to the watch automatically."
                )
            ),
            ManualSection(
                title = "Troubleshooting",
                points = listOf(
                    "If settings do not update on the watch, keep both devices unlocked and close/reopen both apps.",
                    "If Hue pairing fails, confirm phone and bridge are on the same Wi-Fi network.",
                    "If bridge discovery is empty, use manual IP entry from your router or Hue app."
                )
            )
        )
    }

    MaterialTheme(colorScheme = dynamicColorScheme) {
        val background = MaterialTheme.colorScheme.background
        val surface = MaterialTheme.colorScheme.surface

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Manual", color = MaterialTheme.colorScheme.onSurface) },
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Pulse Break Companion Manual",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "This page explains setup, key features, and quick fixes for the companion app.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                items(sections) { section ->
                    ManualSectionCard(
                        section = section,
                        surfaceColor = surface
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualSectionCard(
    section: ManualSection,
    surfaceColor: Color
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = surfaceColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            section.points.forEach { point ->
                Text(
                    text = "- $point",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )
            }
        }
    }
}
