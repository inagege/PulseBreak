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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- 1. NEW: A professional and cohesive color palette ---
// Using a dedicated object for colors makes them reusable and easy to change.
object AppColors {
    val primary = Color(0xFF006778)      // A calming dark teal for primary elements
    val background = Color(0xFFF0F4F5)  // A light, neutral gray for the background
    val surface = Color.White           // Clean white for card backgrounds
    val onSurface = Color(0xFF1F1F1F)   // Dark color for primary text
    val onSurfaceVariant = Color(0xFF5F5F5F) // Lighter gray for secondary text
}

// --- 2. UPDATED: Data class with more context ---
// Added a description field to provide more information to the user on the card.
data class HomeItem(
    val icon: ImageVector,
    val label: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit
) {
    // Updated list of items with the new data class structure.
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
                    containerColor = AppColors.surface, // Use a clean surface color
                    titleContentColor = AppColors.primary
                )
            )
        },
        containerColor = AppColors.background // Set the background for the whole screen
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
                    onClick = { onNavigateToSettings() }
                )
            }
        }
    }
}

// --- 4. NEW: A reusable Card composable for each feature ---
// This replaces the old HomeQuadrantButton. Cards are a staple of modern UI design.
@Composable
fun FeatureCard(
    item: HomeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large, // Nicely rounded corners
        colors = CardDefaults.cardColors(
            containerColor = AppColors.surface // Use the clean surface color
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp // Adds a subtle shadow for a sense of depth
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp) // Ample padding inside the card
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp) // Space between icon and text
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(40.dp), // A more balanced icon size
                tint = AppColors.primary // Consistent icon color
            )
            // --- 5. NEW: Improved typography and hierarchy ---
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = AppColors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold // Title is bold and larger
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    color = AppColors.onSurfaceVariant,
                    fontSize = 14.sp, // Subtitle is smaller and lighter
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}