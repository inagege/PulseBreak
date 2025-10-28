package com.example.breakreminder.presentation

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Color.toArgb(): Int {
    return ((alpha * 255).toInt() shl 24) or
            ((red * 255).toInt() shl 16) or
            ((green * 255).toInt() shl 8) or
            (blue * 255).toInt()
}

@Composable
fun SettingsScreen(
    onThemeChanged: (Boolean) -> Unit,
    isDarkMode: Boolean,
    onButtonColorChanged: (Color, Color) -> Unit,
    buttonColor: Color,
    buttonTextColor: Color,
    onScreenSelectionChanged: (String) -> Unit,
    screenSelection: String,
    onNavigateBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("EISPreferences", Context.MODE_PRIVATE)

    var localIsDarkMode by remember { mutableStateOf(isDarkMode) }
    var localButtonColor by remember { mutableStateOf(buttonColor) }
    var localButtonTextColor by remember { mutableStateOf(buttonTextColor) }
    var localScreenSelection by remember { mutableStateOf(screenSelection) }

    val colorList = listOf(
        Color(0xFFFFC0CB), // Pink
        Color(0xFFADD8E6), // Light Blue
        Color(0xFF90EE90)  // Light Green
    )

    val dynamicColorScheme = if (localIsDarkMode) {
        darkColorScheme(
            primary = localButtonColor,
            onPrimary = localButtonTextColor
        )
    } else {
        lightColorScheme(
            primary = localButtonColor,
            onPrimary = localButtonTextColor
        )
    }

    MaterialTheme(
        colorScheme = dynamicColorScheme
    ) {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(color = localButtonColor, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings Icon",
                        tint = localButtonTextColor,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                DarkModeToggle(
                    localIsDarkMode = localIsDarkMode,
                    localButtonColor = localButtonColor,
                    localButtonTextColor = localButtonTextColor
                ) { newValue ->
                    localIsDarkMode = newValue
                    prefs.edit().putBoolean("isDarkMode", newValue).apply()
                    onThemeChanged(newValue)
                }

                Divider(color = Color.Gray, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                ColorSelectionRow(
                    colorList = colorList,
                    localButtonColor = localButtonColor,
                    localButtonTextColor = localButtonTextColor
                ) { newColor ->
                    localButtonColor = newColor
                    localButtonTextColor = getButtonTextColor(newColor)
                    val butCol = localButtonColor.toArgb()
                    val butTexCol = localButtonTextColor.toArgb()
                    prefs.edit().putInt("buttonColor", butCol).apply()
                    prefs.edit().putInt("buttonTextColor", butTexCol).apply()
                    onButtonColorChanged(newColor, localButtonTextColor)
                }

                Divider(color = Color.Gray, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                ScreenStyleSelection(
                    localScreenSelection = localScreenSelection,
                    localButtonColor = localButtonColor,
                    localButtonTextColor = localButtonTextColor
                ) { chosenStyle ->
                    localScreenSelection = chosenStyle
                    prefs.edit().putString("ScreenSelection", chosenStyle).apply()
                    onScreenSelectionChanged(chosenStyle)
                }

                Divider(color = Color.Gray, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ConfirmButton(
                        localButtonColor = localButtonColor,
                        localButtonTextColor = localButtonTextColor
                    ) {
                        onButtonColorChanged(localButtonColor, localButtonTextColor)
                        onThemeChanged(localIsDarkMode)
                        onScreenSelectionChanged(localScreenSelection)
                        onNavigateBack?.invoke()
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
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
    // Ähnlich SwitchListTile
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Dark Mode", fontSize = 18.sp)
        Switch(
            checked = localIsDarkMode,
            onCheckedChange = { newValue ->
                onDarkModeChanged(newValue)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = localButtonColor,
                checkedTrackColor = localButtonTextColor,
                uncheckedThumbColor = localButtonTextColor,
                uncheckedTrackColor = localButtonColor
            )
        )
    }
}

@Composable
fun ColorSelectionRow(
    colorList: List<Color>,
    localButtonColor: Color,
    localButtonTextColor: Color,
    onColorSelected: (Color) -> Unit
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Text(
            text = "Select Color Scheme",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            colorList.forEach { entryColor ->
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .border(
                            width = if (entryColor == localButtonColor) 2.dp else 0.dp,
                            color = if (entryColor == localButtonColor) Color.Black else Color.Transparent,
                            shape = CircleShape
                        )
                        .background(entryColor, shape = CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onColorSelected(entryColor) }
                            )
                        }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
fun ScreenStyleSelection(
    localScreenSelection: String,
    localButtonColor: Color,
    localButtonTextColor: Color,
    onSelectionChange: (String) -> Unit
) {
    Column {
        Text(
            text = "Select Screen Style",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Grid
        OptionPreviewBox(
            label = "Grid",
            isSelected = (localScreenSelection == "Grid"),
            buttonColor = localButtonColor,
            buttonTextColor = localButtonTextColor,
            previewContent = { GridPreview() }
        ) {
            onSelectionChange("Grid")
        }

        // Sliding
        OptionPreviewBox(
            label = "Sliding",
            isSelected = (localScreenSelection == "Sliding"),
            buttonColor = localButtonColor,
            buttonTextColor = localButtonTextColor,
            previewContent = { SlidingPreview() }
        ) {
            onSelectionChange("Sliding")
        }
    }
    }
}

@Composable
fun OptionPreviewBox(
    label: String,
    isSelected: Boolean,
    buttonColor: Color,
    buttonTextColor: Color,
    previewContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = if (isSelected) buttonTextColor else Color.Transparent
                )
                .clickable { onClick() }
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier.background(
                    color = if (isSelected) buttonColor else Color.Gray,
                    shape = MaterialTheme.shapes.medium
                )
            ) {
                Box(modifier = Modifier.padding(8.dp)) {
                    previewContent()
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(text = label, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
fun ConfirmButton(
    localButtonColor: Color,
    localButtonTextColor: Color,
    onConfirmClicked: () -> Unit
) {
    ElevatedButton(
        onClick = { onConfirmClicked() },
        colors = ButtonDefaults.buttonColors(
            containerColor = localButtonColor,
            contentColor = localButtonTextColor
        )
    ) {
        Text("Confirm")
    }
}

// ----------------------------
// PREVIEW WIDGETS
// ----------------------------
@Composable
fun GridPreview() {
    // Ein kleines 3x3 Grid als Vorschau
    Box(
        modifier = Modifier.size(65.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .background(Color.White, shape = CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SlidingPreview() {
    Box(
        modifier = Modifier.size(65.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(35.dp)
                    .background(Color.White, shape = CircleShape)
            )
            Spacer(modifier = Modifier.height(5.dp))
            Icon(
                imageVector = Icons.Default.Swipe,
                contentDescription = "Swipe Preview Icon",
                tint = Color.White
            )
        }
    }
}

// ----------------------------
// HILFSFUNKTION Farbe -> Textfarbe
// ----------------------------
fun getButtonTextColor(colorKey: Color): Color {
    return when (colorKey.value.toLong()) {
        0xFFFFC0CB -> Color(0xFF4D2324) // Pink
        0xFFADD8E6 -> Color(0xFF003366) // Light Blue
        0xFF90EE90 -> Color(0xFF2F4F4F) // Light Green
        else -> Color.Black
    }
}
