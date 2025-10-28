package com.example.breakreminder.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable
fun SelectionScreen(
    onThemeChanged: (Boolean) -> Unit,
    isDarkMode: Boolean,
    onButtonColorChanged: (Color, Color) -> Unit,
    buttonColor: Color,
    buttonTextColor: Color,
    onScreenSelectionChanged: (String) -> Unit,
    screenSelection: String,
    // Navigation-Callbacks:
    onNavigateToYoga: () -> Unit = {},
    onNavigateToWalk: () -> Unit = {},
    onNavigateToNap: () -> Unit = {},
    onNavigateToVent: () -> Unit = {},
    onNavigateToCoffee: () -> Unit = {},
    onNavigateToClean: () -> Unit = {}
) {
    val categories = listOf("Yoga", "Walk", "Nap", "Vent", "Coffee", "Clean")
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Kleiner Kreis oben
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .offset(y = 10.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(buttonColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassBottom,
                    contentDescription = "Hourglass Icon",
                    tint = buttonTextColor,
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(25.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(categories) { name ->
                    // Button-Größe (Breite/Höhe) so wählen, dass sie auf dem runden Display nicht zu groß werden
                    // Beispiel: (screenWidth - 50.dp) / 3
                    RoundButton(
                        label = name,
                        size = (screenWidth - 50.dp) / 3f,
                        buttonColor = buttonColor,
                        buttonTextColor = buttonTextColor
                    ) {
                        when (name) {
                            "Yoga" -> onNavigateToYoga()
                            "Walk" -> onNavigateToWalk()
                            "Nap" -> onNavigateToNap()
                            "Vent" -> onNavigateToVent()
                            "Coffee" -> onNavigateToCoffee()
                            "Clean" -> onNavigateToClean()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RoundButton(
    label: String,
    size: Dp,
    buttonColor: Color,
    buttonTextColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size) // quadratisch = width & height identisch
            .background(buttonColor, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = buttonTextColor,
                fontWeight = FontWeight.Normal,
                fontSize = (size.value * 0.2).sp
            )
        )
    }
}
