// filepath: companion/src/main/java/com/example/companionpulsebreak/screens/HueBrightnessScreen.kt
package com.example.companionpulsebreak.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.companionpulsebreak.sync.SettingsManager
import com.example.commonlibrary.HueAutomationData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrightnessScreen(
    settingsManager: SettingsManager,
    initial: HueAutomationData,
    onBack: () -> Unit,
    onDraftChanged: (HueAutomationData) -> Unit,
    actionTextColor: Color
) {
    var brightness by remember { mutableStateOf(initial.brightness) }
    val debounceMs = 300L
    var lastSavedBrightness by remember { mutableStateOf(initial.brightness) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Brightness", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))

        Text("Brightness: $brightness%", color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = brightness.toFloat(),
            onValueChange = {
                brightness = it.coerceIn(0f, 100f).toInt()
                val newHue = initial.copy(brightness = brightness)
                try { onDraftChanged(newHue) } catch (_: Exception) {}
            },
            valueRange = 0f..100f
        )

        LaunchedEffect(brightness) {
            if (brightness == lastSavedBrightness) return@LaunchedEffect
            try {
                kotlinx.coroutines.delay(debounceMs)
                val sd = settingsManager.loadInitialSettings()
                val merged = sd.copy(hueAutomation = sd.hueAutomation.copy(brightness = brightness))
                settingsManager.applySettings(merged)
                lastSavedBrightness = brightness
            } catch (_: Exception) {
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
            ) { Text("Back", color = actionTextColor) }
        }
    }
}

