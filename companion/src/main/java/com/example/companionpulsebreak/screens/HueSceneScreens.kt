// filepath: companion/src/main/java/com/example/companionpulsebreak/screens/HueSceneScreens.kt
package com.example.companionpulsebreak.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.companionpulsebreak.sync.HueViewModel
import com.example.companionpulsebreak.sync.SettingsManager
import com.example.commonlibrary.HueColorMode
import com.example.commonlibrary.HueAutomationData
import kotlinx.coroutines.launch

// small helper model for scene tiles (unique name)
internal data class SceneUi(val id: String, val name: String, val preview: Color)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HueColorScreen(
    settingsManager: SettingsManager,
    initial: HueAutomationData,
    hueViewModel: HueViewModel,
    onBack: () -> Unit,
    onDraftChanged: (HueAutomationData) -> Unit,
    actionTextColor: Color
) {
    val lights by hueViewModel.lights.collectAsState()
    val groups by hueViewModel.groups.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(initial) }

    val hasColor by remember {
        derivedStateOf { selectedTargetsHaveColor(draft, lights, groups) }
    }

    // Use only the local hard-coded fallback scenes per user's request.
    val scenes = remember {
        listOf(
            SceneUi("bright", "Bright", Color(0xFFFFF1D6)),
            SceneUi("relax", "Relax", Color(0xFFFFD8A8)),
            SceneUi("nightlight", "Nightlight", Color(0xFFB8742A)),
            SceneUi("spring", "Spring", Color(0xFFB6FFB6)),
            SceneUi("frosty", "Frosty dawn", Color(0xFFCFE8FF)),
            SceneUi("sunset", "Sunset", Color(0xFFFF9B6A))
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Hue scene gallery", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))

        // If there are no color-capable lights selected, show an informational message
        // instead of the scenes/custom grid. Otherwise show the regular grid.
        if (hasColor) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    val selectedCustom = try { draft.colorMode != HueColorMode.SCENE } catch (_: Throwable) { false }
                    val customPreview = try { if (hasColor) Color(draft.colorArgb) else Color(0xFFFFE7C2) } catch (_: Throwable) { Color(0xFFFFE7C2) }

                    SceneTileView(
                        title = "Custom",
                        preview = customPreview,
                        selected = selectedCustom,
                        onClick = {
                            draft = draft.copy(
                                colorMode = if (hasColor) HueColorMode.CUSTOM_COLOR else HueColorMode.CUSTOM_WHITE,
                                sceneId = null
                            )
                            try { onDraftChanged(draft) } catch (_: Exception) {}
                            coroutineScope.launch {
                                try {
                                    val sd = settingsManager.loadInitialSettings()
                                    val base = sd.hueAutomation
                                    val updated = base.copy(
                                        colorMode = draft.colorMode,
                                        sceneId = draft.sceneId,
                                        scenePreviewArgb = draft.scenePreviewArgb
                                    )
                                    val merged = sd.copy(hueAutomation = updated)
                                    settingsManager.applySettings(merged)
                                } catch (_: Exception) {}
                            }
                        }
                    )
                }

                items(scenes.size) { idx ->
                    val s = scenes[idx]
                    val selected = try { draft.colorMode == HueColorMode.SCENE && draft.sceneId == s.id } catch (_: Throwable) { false }
                    SceneTileView(
                        title = s.name,
                        preview = s.preview,
                        selected = selected,
                        onClick = {
                            val previewArgb = try { s.preview.toArgb() } catch (_: Throwable) { 0xFFFFFFFF.toInt() }
                            draft = draft.copy(colorMode = HueColorMode.SCENE, sceneId = s.id, scenePreviewArgb = previewArgb)
                            try { onDraftChanged(draft) } catch (_: Exception) {}
                            coroutineScope.launch {
                                try {
                                    val sd = settingsManager.loadInitialSettings()
                                    val base = sd.hueAutomation
                                    val updated = base.copy(
                                        colorMode = draft.colorMode,
                                        sceneId = draft.sceneId,
                                        scenePreviewArgb = draft.scenePreviewArgb
                                    )
                                    val merged = sd.copy(hueAutomation = updated)
                                    settingsManager.applySettings(merged)
                                } catch (_: Exception) {}
                            }
                        }
                    )
                }
            }
        } else {
            // Inform the user that only non-color lights are selected and color settings can't be applied.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(
                        "No color-capable lights selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Currently only non-color lights are selected, so color or scene settings cannot be applied.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val mode = try { draft.colorMode } catch (_: Throwable) { HueColorMode.SCENE }

        // Only show color/temperature wheels when color-capable lights are selected.
        if (hasColor) {
            if (mode == HueColorMode.CUSTOM_COLOR) {
                Spacer(Modifier.height(12.dp))
                HueColorWheel(
                    colorArgb = try { draft.colorArgb } catch (_: Throwable) { 0xFFFFFFFF.toInt() },
                    onColorChanged = { argb ->
                        try {
                            draft = draft.copy(colorArgb = argb)
                            try { onDraftChanged(draft) } catch (_: Exception) {}
                            coroutineScope.launch {
                                try {
                                    val sd = settingsManager.loadInitialSettings()
                                    val base = sd.hueAutomation
                                    val updated = base.copy(colorArgb = draft.colorArgb, colorMode = draft.colorMode)
                                    val merged = sd.copy(hueAutomation = updated)
                                    settingsManager.applySettings(merged)
                                } catch (_: Exception) {}
                            }
                        } catch (_: Throwable) {
                        }
                    }
                )
            } else if (mode == HueColorMode.CUSTOM_WHITE) {
                Spacer(Modifier.height(12.dp))
                WhiteTempWheel(
                    ctMired = try { draft.colorTemperature } catch (_: Throwable) { 366 },
                    onCtChanged = { mired ->
                        try {
                            draft = draft.copy(colorTemperature = mired)
                            try { onDraftChanged(draft) } catch (_: Exception) {}
                            coroutineScope.launch {
                                try {
                                    val sd = settingsManager.loadInitialSettings()
                                    val base = sd.hueAutomation
                                    val updated = base.copy(colorTemperature = draft.colorTemperature, colorMode = draft.colorMode)
                                    val merged = sd.copy(hueAutomation = updated)
                                    settingsManager.applySettings(merged)
                                } catch (_: Exception) {}
                            }
                        } catch (_: Throwable) {
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
            ) { Text("Back", color = actionTextColor) }
        }
    }
}

@Composable
internal fun SceneTileView(
    title: String,
    preview: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = if (selected) 4.dp else 0.dp,
        shadowElevation = if (selected) 6.dp else 0.dp,
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = preview,
                modifier = Modifier.size(44.dp)
            ) {}

            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
