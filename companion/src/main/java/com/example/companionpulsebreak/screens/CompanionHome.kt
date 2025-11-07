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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel

// This object can be removed or kept for default/fallback values if needed.
object AppColors {
    val primary = Color(0xFF006778)
    val background = Color(0xFFF0F4F5)
    val surface = Color.White
    val onSurface = Color(0xFF1F1F1F)
    val onSurfaceVariant = Color(0xFF5F5F5F)
}

data class HomeItem(
    val icon: ImageVector,
    val label: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: CompanionSettingsViewModel, // Add ViewModel parameter
    onNavigateToSettings: () -> Unit
) {
    // Collect settings state
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val buttonColor = Color(settings.buttonColor)
    val isDarkMode = settings.isDarkMode

    // Define colors based on settings
    val dynamicPrimaryColor = if (isDarkMode) buttonColor else AppColors.primary
    val dynamicBackgroundColor = if (isDarkMode) Color.DarkGray else AppColors.background
    val dynamicSurfaceColor = if (isDarkMode) Color.Black else AppColors.surface
    val dynamicOnSurfaceColor = if (isDarkMode) Color.White else AppColors.onSurface

    val homeItems = listOf(
        HomeItem(Icons.Default.Lightbulb, "Light Setup", "Customize your lighting"),
        HomeItem(Icons.Default.Timer, "Break Management", "Manage your break intervals"),
        HomeItem(Icons.Default.Palette, "Design Options", "Personalize the app's look"),
        HomeItem(Icons.Default.Book, "Manual", "Learn how to use the app")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pulse Break", fontWeight = FontWeight.Bold) },
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
                    onClick = {
                        // All cards navigate to settings, so this is correct.
                        if (item.label == "Design Options") {
                            onNavigateToSettings()
                        }
                    }
                )
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                    color = AppColors.onSurfaceVariant, // Can also be made dynamic
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}
