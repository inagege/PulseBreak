package com.example.companionpulsebreak.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakSessionsScreen(
    viewModel: CompanionSettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var localScheduleBreakIntervals by remember { mutableStateOf(settings.scheduleBreakIntervals) }
    var localBreakIntervalHours by remember { mutableStateOf(settings.breakIntervalHours) }
    var localBreakIntervalMinutes by remember { mutableStateOf(settings.breakIntervalMinutes) }

    val localIsDarkMode = settings.isDarkMode
    val localButtonColor = Color(settings.buttonColor)
    val localButtonTextColor = Color(settings.buttonTextColor)

    LaunchedEffect(settings) {
        localScheduleBreakIntervals = settings.scheduleBreakIntervals
        localBreakIntervalHours = settings.breakIntervalHours
        localBreakIntervalMinutes = settings.breakIntervalMinutes
    }

    val dynamicColorScheme = remember(localButtonColor, localButtonTextColor, localIsDarkMode) {
        if (localIsDarkMode) {
            darkColorScheme(
                primary = localButtonColor,
                onPrimary = localButtonTextColor,
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                onSurface = Color(0xFFECECEC)
            )
        } else {
            lightColorScheme(
                primary = localButtonColor,
                onPrimary = localButtonTextColor,
                background = Color(0xFFF0F4F5),
                surface = Color.White,
                onSurface = Color(0xFF1F1F1F)
            )
        }
    }

    fun persistBreakSettings() {
        viewModel.updateSettingsPartial { current ->
            current.copy(
                scheduleBreakIntervals = localScheduleBreakIntervals,
                breakIntervalHours = localBreakIntervalHours,
                breakIntervalMinutes = localBreakIntervalMinutes
            )
        }
    }

    fun intervalSummary(hours: Int, minutes: Int): String {
        val h = hours.coerceIn(0, 12)
        val m = minutes.coerceIn(0, 55)
        return when {
            h == 0 && m == 0 -> "Not set"
            h == 0 -> "Every ${m}m"
            m == 0 -> "Every ${h}h"
            else -> "Every ${h}h ${m}m"
        }
    }

    MaterialTheme(colorScheme = dynamicColorScheme) {
        val background = MaterialTheme.colorScheme.background
        val surface = MaterialTheme.colorScheme.surface
        val cardShadow: Dp = 0.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Break Sessions", color = MaterialTheme.colorScheme.onSurface) },
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
                // Main settings card
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = surface,
                    tonalElevation = 0.dp,
                    shadowElevation = cardShadow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {

                        Text(
                            text = "Scheduled breaks",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Set regular break reminders in addition to stress-based scheduling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )

                        Spacer(Modifier.height(12.dp))
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(Modifier.height(8.dp))

                        // Toggle row using ListItem (cleaner alignment)
                        ListItem(
                            headlineContent = {
                                Text(
                                    "Schedule break intervals",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    "When enabled, you’ll get a break reminder at your chosen interval." ,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = localScheduleBreakIntervals,
                                    onCheckedChange = {
                                        localScheduleBreakIntervals = it
                                        persistBreakSettings()
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

                        // Interval controls (animated)
                        AnimatedVisibility(
                            visible = localScheduleBreakIntervals,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Summary chip-like line (no new colors; just onSurface alpha)
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    tonalElevation = 0.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Break interval",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            text = intervalSummary(localBreakIntervalHours, localBreakIntervalMinutes),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    IntervalDropdownField(
                                        modifier = Modifier.weight(1f),
                                        label = "Hours",
                                        valueText = "${localBreakIntervalHours}",
                                        options = (0..12).toList(),
                                        optionLabel = { "$it h" },
                                        onSelected = {
                                            localBreakIntervalHours = it
                                            persistBreakSettings()
                                        }
                                    )

                                    IntervalDropdownField(
                                        modifier = Modifier.weight(1f),
                                        label = "Minutes",
                                        valueText = "${localBreakIntervalMinutes}",
                                        options = (0..11).map { it * 5 },
                                        optionLabel = { "$it min" },
                                        onSelected = {
                                            localBreakIntervalMinutes = it
                                            persistBreakSettings()
                                        }
                                    )
                                }

                                Text(
                                    text = "Tip: 60 to 90 minutes is a good starting point for most people.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }

                // Spacer to push content up (bottomBar handles the button)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> IntervalDropdownField(
    modifier: Modifier = Modifier,
    label: String,
    valueText: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = valueText,
            onValueChange = { /* read-only */ },
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(optionLabel(item)) },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    }
                )
            }
        }
    }
}
