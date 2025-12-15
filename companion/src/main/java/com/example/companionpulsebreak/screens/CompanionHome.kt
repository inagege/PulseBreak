package com.example.companionpulsebreak.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel

data class HomeItem(
    val icon: ImageVector,
    val label: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: CompanionSettingsViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToHue: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val buttonColor = Color(settings.buttonColor)
    val buttonTextColor = Color(settings.buttonTextColor)
    val isDarkMode = settings.isDarkMode

    // Recreate the scheme whenever relevant settings change so Compose recomposes correctly.
    val dynamicColorScheme = remember(buttonColor, buttonTextColor, isDarkMode) {
        if (isDarkMode) {
            darkColorScheme(
                primary = buttonColor,
                onPrimary = buttonTextColor,
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                onSurface = Color(0xFFECECEC)
            )
        } else {
            lightColorScheme(
                primary = buttonColor,
                onPrimary = buttonTextColor,
                background = Color(0xFFF0F4F5), // match your intended light background
                surface = Color.White,
                onSurface = Color(0xFF1F1F1F)
            )
        }
    }

    // keep rest of HomeScreen behavior; using explicit surface/background makes the change visible
    val homeItems = listOf(
        HomeItem(Icons.Default.Lightbulb, "Light Setup", "Customize your lighting"),
        HomeItem(Icons.Default.Timer, "Break Management", "Manage your break intervals"),
        HomeItem(Icons.Default.Palette, "Design Options", "Personalize the app's look"),
        HomeItem(Icons.Default.Book, "Manual", "Learn how to use the app")
    )

    val cardShadow: Dp = if (isDarkMode) 8.dp else 4.dp

    MaterialTheme(colorScheme = dynamicColorScheme) {
        val dynamicPrimaryColor = MaterialTheme.colorScheme.primary
        val dynamicBackgroundColor = MaterialTheme.colorScheme.background
        val dynamicSurfaceColor = MaterialTheme.colorScheme.surface
        val dynamicOnSurfaceColor = MaterialTheme.colorScheme.onSurface
        val dynamicOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Pulse Break", fontWeight = FontWeight.Bold, color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = dynamicSurfaceColor,
                        titleContentColor = dynamicPrimaryColor
                    )
                )
            },
            containerColor = dynamicBackgroundColor
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(all = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(homeItems) { item ->
                    FeatureCard(
                        item = item,
                        surfaceColor = dynamicSurfaceColor,
                        onSurfaceColor = dynamicOnSurfaceColor,
                        primaryColor = dynamicPrimaryColor,
                        descriptionColor = dynamicOnSurfaceVariant,
                        shadowElevation = cardShadow,
                        onClick = {
                            when (item.label) {
                                "Design Options" -> onNavigateToSettings()
                                "Light Setup" -> onNavigateToHue()
                                else -> {
                                    // no-op for preliminary draft
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FeatureCard(
    item: HomeItem,
    surfaceColor: Color,
    onSurfaceColor: Color,
    primaryColor: Color,
    descriptionColor: Color,
    shadowElevation: Dp = 4.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            // apply explicit shadow so it's visible in dark mode as well
            .shadow(elevation = shadowElevation, shape = MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        // keep card elevation zero because we already applied shadow
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(40.dp),
                tint = primaryColor
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = onSurfaceColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    color = descriptionColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}
