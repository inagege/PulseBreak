package com.example.breakreminder.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.breakreminder.HeartRateReader
import com.example.breakreminder.sync.AppSettingsViewModel

@Composable
fun DefaultScreen(
    viewModel: AppSettingsViewModel,
    onNavigateToHome: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var isActive by remember { mutableStateOf(true) }

    val heartRateReader = remember {
        HeartRateReader(
            context = context,
            shouldTriggerNavigation = { isActive },
            onNavigateToHome = onNavigateToHome
        )
    }

    DisposableEffect(Unit) {
        heartRateReader.startReading()
        onDispose {
            isActive = false
            heartRateReader.stopReading()
        }
    }

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
        val adjustedPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() - 25.dp,
            bottom = innerPadding.calculateBottomPadding(),
            start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
            end = innerPadding.calculateEndPadding(LayoutDirection.Ltr)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(adjustedPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Your heart rate is being measured. You will get notifications if a pause is recommended.",
                fontSize = 18.sp,
                lineHeight = 20.sp,
                color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(195.dp)
            )
        }
    }
}