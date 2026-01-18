@file:Suppress("UNUSED_PARAMETER")

package com.example.companionpulsebreak.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel
import com.example.commonlibrary.SettingsData

fun Color.toArgb(): Int {
    return ((alpha * 255).toInt() shl 24) or
            ((red * 255).toInt() shl 16) or
            ((green * 255).toInt() shl 8) or
            (blue * 255).toInt()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionSettingsScreen(
    viewModel: CompanionSettingsViewModel,
    onBackToHome: (() -> Unit)? = null
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Local state for Compose UI (keeps UI responsive)
    var localIsDarkMode by remember { mutableStateOf(settings.isDarkMode) }
    var localButtonColor by remember { mutableStateOf(Color(settings.buttonColor)) }
    var localButtonTextColor by remember { mutableStateOf(Color(settings.buttonTextColor)) }
    var localScreenSelection by remember { mutableStateOf(settings.screenSelection) }
    var localScheduleBreakIntervals by remember { mutableStateOf(settings.scheduleBreakIntervals) }
    var localBreakIntervalHours by remember { mutableStateOf(settings.breakIntervalHours) }
    var localBreakIntervalMinutes by remember { mutableStateOf(settings.breakIntervalMinutes) }

    // Keep locals in sync if settings update from elsewhere
    LaunchedEffect(settings) {
        localIsDarkMode = settings.isDarkMode
        localButtonColor = Color(settings.buttonColor)
        localButtonTextColor = Color(settings.buttonTextColor)
        localScreenSelection = settings.screenSelection
        localScheduleBreakIntervals = settings.scheduleBreakIntervals
        localBreakIntervalHours = settings.breakIntervalHours
        localBreakIntervalMinutes = settings.breakIntervalMinutes
    }

    val colorList = listOf(
        "Pink" to Color(0xFFFFC0CB),
        "Blue" to Color(0xFFADD8E6),
        "Green" to Color(0xFF90EE90)
    )

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

    MaterialTheme(colorScheme = dynamicColorScheme) {
        val background = MaterialTheme.colorScheme.background
        val surface = MaterialTheme.colorScheme.surface

        val cardShadow: Dp = 0.dp

        fun pushSettingsUpdate(
            isDark: Boolean = localIsDarkMode,
            btnColor: Color = localButtonColor,
            btnText: Color = localButtonTextColor,
            screenSel: String = localScreenSelection
        ) {
            viewModel.updateSettingsPartial { current ->
                current.copy(
                    isDarkMode = isDark,
                    buttonColor = btnColor.toArgb(),
                    buttonTextColor = btnText.toArgb(),
                    screenSelection = screenSel
                )
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.Bold, color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor)) },
                    navigationIcon = {
                        IconButton(onClick = { onBackToHome?.invoke() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = surface,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            containerColor = background
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SettingsCard(
                        title = "Appearance",
                        surfaceColor = surface,
                        shadowElevation = cardShadow
                    ) {
                        DarkModeToggle(
                            localIsDarkMode = localIsDarkMode,
                            localButtonColor = localButtonColor,
                            localButtonTextColor = localButtonTextColor
                        ) { newValue ->
                            localIsDarkMode = newValue
                            pushSettingsUpdate(isDark = newValue)
                        }
                    }
                }

                item {
                    SettingsCard(
                        title = "Color Scheme",
                        surfaceColor = surface,
                        shadowElevation = cardShadow
                    ) {
                        ColorSelectionRow(
                            colorList = colorList,
                            localButtonColor = localButtonColor
                        ) { newColor ->
                            localButtonColor = newColor
                            localButtonTextColor = getButtonTextColor(newColor.toArgb())
                            pushSettingsUpdate(
                                btnColor = localButtonColor,
                                btnText = localButtonTextColor
                            )
                        }
                    }
                }

                item {
                    SettingsCard(
                        title = "Screen Style on Watch",
                        surfaceColor = surface,
                        shadowElevation = cardShadow
                    ) {
                        ScreenStyleSelection(
                            localScreenSelection = localScreenSelection,
                            localButtonColor = localButtonColor,
                            localButtonTextColor = localButtonTextColor
                        ) { chosenStyle ->
                            localScreenSelection = chosenStyle
                            pushSettingsUpdate(screenSel = chosenStyle)
                        }
                    }
                }

                // Settings apply instantly -> no Cancel button; keep Confirm button style as requested.
                item {
                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = { onBackToHome?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = localButtonColor,
                            contentColor = localButtonTextColor
                        )
                    ) {
                        Text("Confirm", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    surfaceColor: Color,
    shadowElevation: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = surfaceColor,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
fun DarkModeToggle(
    localIsDarkMode: Boolean,
    localButtonColor: Color,
    localButtonTextColor: Color,
    onDarkModeChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Dark Mode", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = localIsDarkMode,
            onCheckedChange = onDarkModeChanged,
            colors = SwitchDefaults.colors(
                checkedTrackColor = localButtonColor.copy(alpha = 0.35f),
                checkedThumbColor = localButtonColor,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
            )
        )
    }
}

@Composable
fun ColorSelectionRow(
    colorList: List<Pair<String, Color>>,
    localButtonColor: Color,
    onColorSelected: (Color) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        colorList.forEach { (label, entryColor) ->
            val isSelected = entryColor == localButtonColor

            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.08f else 1.0f,
                label = "colorScale"
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable { onColorSelected(entryColor) },
                    contentAlignment = Alignment.Center
                ) {
                    // Outer ring (accent) when selected
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .border(
                                    width = 3.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                )
                        )
                        // Inner ring (white/neutral) to improve contrast on similar colors
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .padding(3.dp)
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = CircleShape
                                )
                        )
                    } else {
                        // Unselected subtle ring for structure
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                    shape = CircleShape
                                )
                        )
                    }

                    // Actual color fill
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(if (isSelected) 7.dp else 3.dp)
                            .background(entryColor, CircleShape)
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
    }
}


@Composable
fun ScreenStyleSelection(
    localScreenSelection: String,
    localButtonColor: Color,
    localButtonTextColor: Color,
    onSelectionChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OptionPreviewBox(
            modifier = Modifier.weight(1f),
            label = "Grid",
            isSelected = (localScreenSelection == "Grid"),
            buttonColor = localButtonColor,
            buttonTextColor = localButtonTextColor,
            previewContent = { GridPreview() },
            onClick = { onSelectionChange("Grid") })
        OptionPreviewBox(
            modifier = Modifier.weight(1f),
            label = "Sliding",
            isSelected = (localScreenSelection == "Sliding"),
            buttonColor = localButtonColor,
            buttonTextColor = localButtonTextColor,
            previewContent = { SlidingPreview() },
            onClick = {
                onSelectionChange(
                    "Sliding"
                )
            })
    }
}

@Composable
fun OptionPreviewBox(
    label: String,
    isSelected: Boolean,
    buttonColor: Color,
    buttonTextColor: Color,
    previewContent: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor =
        if (isSelected) buttonColor else buttonTextColor.copy(alpha = 0.25f)
    Column(
        modifier =
        modifier, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, MaterialTheme.shapes.large)
                .clickable { onClick() },
            color = if (isSelected) buttonColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier.padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) buttonColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) { Box(Modifier.padding(8.dp)) { previewContent() } }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun GridPreview() {
    Box(modifier = Modifier.size(65.dp), contentAlignment = Alignment.Center) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .background(MaterialTheme.colorScheme.onSurface, shape = CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SlidingPreview() {
    Box(modifier = Modifier.size(65.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(35.dp)
                    .background(MaterialTheme.colorScheme.onSurface, shape = CircleShape)
            )
            Spacer(modifier = Modifier.height(5.dp))
            Icon(
                imageVector =
                Icons.Default.Swipe, contentDescription = "Swipe Preview Icon", tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// Helper: robust matching by ARGB Int (avoid Color.value comparisons)
fun getButtonTextColor(buttonColorArgb: Int): Color {
    return when (buttonColorArgb) {
        0xFFFFC0CB.toInt() -> Color(0xFF4D2324) // Pink
        0xFFADD8E6.toInt() -> Color(0xFF003366) // Light Blue
        0xFF90EE90.toInt() -> Color(0xFF2F4F4F) // Light Green
        else -> {
            // Compute luminance to decide on a readable text color (black or white)
            val r = ((buttonColorArgb shr 16) and 0xFF) / 255.0
            val g = ((buttonColorArgb shr 8) and 0xFF) / 255.0
            val b = (buttonColorArgb and 0xFF) / 255.0
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if (lum > 0.6) Color.Black else Color.White
        }
    }
}
