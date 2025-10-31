package com.example.breakreminder.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.breakreminder.sync.AppSettingsViewModel

@Composable
fun HomeScreen(
    viewModel: AppSettingsViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToSelection: () -> Unit
) {
    val settings by viewModel.settings
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                FilledIconButton(
                    onClick = { onNavigateToSettings() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(settings.buttonColor),
                        contentColor = Color(settings.buttonTextColor)
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings Icon"
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Need a break?",
                fontSize = 24.sp,
                color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor)
            )
            Spacer(modifier = Modifier.height(26.dp))

            Button(
                onClick = { onNavigateToSelection() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(settings.buttonColor),
                    contentColor = Color(settings.buttonTextColor)
                ),
                modifier = Modifier
                    .width(160.dp)
                    .height(50.dp)
            ) {
                Text("Start Session", fontSize = 18.sp)
            }
        }
    }
}
