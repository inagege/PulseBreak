package com.example.breakreminder.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.example.commonlibrary.SettingsData
import com.example.breakreminder.sync.AppSettingsViewModel

@Composable
fun SelectionScreen(
    viewModel: AppSettingsViewModel,
    // Navigation-Callbacks:
    onNavigateToYoga: () -> Unit = {},
    onNavigateToWalk: () -> Unit = {},
    onNavigateToNap: () -> Unit = {},
    onNavigateToVent: () -> Unit = {}
) {
    val activities = listOf(
        TileActivity("Yoga", Icons.Default.SelfImprovement),
        TileActivity("Walk", Icons.Default.DirectionsWalk),
        TileActivity("Nap", Icons.Default.Bed),
        TileActivity("Vent", Icons.Default.Air)
    )
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val settings by viewModel.settings.collectAsState(initial = SettingsData())
    val buttonColor = runCatching { Color(settings.buttonColor) }.getOrElse { Color(0xFF90EE90) }
    val buttonTextColor = runCatching { Color(settings.buttonTextColor) }.getOrElse { Color(0xFF2F4F4F) }


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
                    .background(Color(settings.buttonColor), CircleShape),
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
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(activities) { activity ->
                    RoundButton(
                        label = activity.name,
                        icon = activity.icon,
                        size = (screenWidth - 58.dp) / 2f,
                        buttonColor = buttonColor,
                        buttonTextColor = buttonTextColor
                    ) {
                        when (activity.name) {
                            "Yoga" -> onNavigateToYoga()
                            "Walk" -> onNavigateToWalk()
                            "Nap" -> onNavigateToNap()
                            "Vent" -> onNavigateToVent()
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
    icon: ImageVector,
    size: Dp,
    buttonColor: Color,
    buttonTextColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(buttonColor, RoundedCornerShape(22.dp))
            .border(
                width = 1.dp,
                color = buttonTextColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(22.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = buttonTextColor,
                modifier = Modifier.size((size.value * 0.30f).dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = TextStyle(
                    color = buttonTextColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.15).sp
                )
            )
        }
    }
}

data class TileActivity(
    val name: String,
    val icon: ImageVector
)
