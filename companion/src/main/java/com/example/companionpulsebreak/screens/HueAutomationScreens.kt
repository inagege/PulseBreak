package com.example.companionpulsebreak.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
import com.example.commonlibrary.HueColorMode
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.commonlibrary.HueAutomationData
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel
import com.example.companionpulsebreak.sync.HueGroup
import com.example.companionpulsebreak.sync.HueLight
import com.example.companionpulsebreak.sync.HueViewModel
import com.example.companionpulsebreak.sync.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NOTE:
 * This file assumes you can extend HueAutomationData. If HueAutomationData is in a shared module and
 * you cannot edit it yet, you can temporarily keep those extra fields elsewhere – but the UI will be
 * much cleaner and persist correctly if HueAutomationData has these fields:
 *
 * enum class HueColorMode { SCENE, CUSTOM_COLOR, CUSTOM_WHITE }
 * val colorMode: HueColorMode
 * val sceneId: String?
 * val colorArgb: Int
 * val ctMired: Int
 *
 * Also assumes HueLight has supportsColor (Boolean). If you don't have it, add it in your Hue parsing.
 */

/** --- Optional helper UI model for scene tiles --- */
private data class SceneUi(val id: String, val name: String, val preview: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueAutomationHomeScreen(
    settingsManager: SettingsManager,
    settingsViewModel: CompanionSettingsViewModel = viewModel(),
    hueViewModel: HueViewModel = viewModel(),
    onBack: () -> Unit = {},
    onNoConnection: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("home") }
    val settingsFlow by remember { mutableStateOf(settingsManager.settingsFlow) }
    val settingsState by settingsFlow.collectAsState(initial = null)
    var hueSettings by remember { mutableStateOf(HueAutomationData()) }

    LaunchedEffect(settingsState) {
        settingsState?.let { s ->
            hueSettings = s.hueAutomation
        }
    }

    // ensure we refresh when the screen appears and we're connected
    val isConnected by hueViewModel.isConnected.collectAsState()
    // Avoid repeatedly invoking onNoConnection during transient recompositions — call it once per disconnect.
    val hasNotifiedNoConnection = remember { mutableStateOf(false) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            // reset notification flag when we regain connection
            hasNotifiedNoConnection.value = false
            // initial immediate refresh
            hueViewModel.refreshHueState()
            // then periodically refresh to detect network loss while the screen is visible
            while (true) {
                try {
                    delay(6000)
                    hueViewModel.refreshHueState()
                } catch (t: Throwable) {
                    // ignore and allow outer catch in viewmodel to set isConnected=false
                }
            }
        } else {
            // if not connected, inform caller once so they can navigate to the Hue screens
            if (!hasNotifiedNoConnection.value) {
                hasNotifiedNoConnection.value = true
                onNoConnection()
            }
        }
    }

    // App-wide dynamic theme values
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val buttonColor = Color(settings.buttonColor)
    val buttonTextColor = Color(settings.buttonTextColor)
    val isDarkMode = settings.isDarkMode

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
                background = Color(0xFFF0F4F5),
                surface = Color.White,
                onSurface = Color(0xFF1F1F1F)
            )
        }
    }

    MaterialTheme(colorScheme = dynamicColorScheme) {

        val dynamicPrimaryColor = MaterialTheme.colorScheme.primary
        val dynamicBackgroundColor = MaterialTheme.colorScheme.background
        val dynamicSurfaceColor = MaterialTheme.colorScheme.surface
        val dynamicOnSurfaceColor = MaterialTheme.colorScheme.onSurface
        val dynamicOnPrimaryColor = MaterialTheme.colorScheme.onPrimary
        val actionTextColor = if (isDarkMode) dynamicPrimaryColor else Color(settings.buttonTextColor)
        val dynamicOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val cardShadow: Dp = if (isDarkMode) 8.dp else 4.dp

        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (screen) {
                                "lights" -> "Rooms & Zones"
                                "brightness" -> "Brightness"
                                "color" -> "Color"
                                else -> "Light Options"
                            },
                            fontWeight = FontWeight.Bold,
                            color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor)
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (screen == "home") onBack() else screen = "home"
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = dynamicSurfaceColor,
                        titleContentColor = dynamicPrimaryColor
                    )
                )
            },
            containerColor = dynamicBackgroundColor
        ) { paddingValues ->

            when (screen) {
                "home" -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentPadding = PaddingValues(all = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val automationItems = listOf(
                            HomeItem(
                                Icons.Default.ViewModule,
                                "Rooms & Zones",
                                "Select which lights this automation controls"
                            ),
                            HomeItem(
                                Icons.Default.WbSunny,
                                "Brightness",
                                "Set the target brightness for the automation"
                            ),
                            HomeItem(
                                Icons.Default.Palette,
                                "Color",
                                "Choose scene, color, or warmth"
                            )
                        )

                        items(automationItems) { item ->
                            FeatureCard(
                                item = item,
                                surfaceColor = dynamicSurfaceColor,
                                onSurfaceColor = dynamicOnSurfaceColor,
                                primaryColor = dynamicPrimaryColor,
                                descriptionColor = dynamicOnSurfaceVariant,
                                shadowElevation = cardShadow,
                                onClick = {
                                    when (item.label) {
                                        "Rooms & Zones" -> screen = "lights"
                                        "Brightness" -> screen = "brightness"
                                        "Color" -> screen = "color"
                                    }
                                }
                            )
                        }

                        item {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { runTest(hueSettings, hueViewModel, scope) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)

                                ) { Text("Test", color = actionTextColor) }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            val sd = settingsManager.loadInitialSettings()
                                            val merged = sd.copy(hueAutomation = hueSettings)
                                            settingsManager.applySettings(merged)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Save") }
                            }
                        }
                    }
                }

                "lights" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        LightsSelectionScreen(
                            settingsManager = settingsManager,
                            hueViewModel = hueViewModel,
                            initial = hueSettings,
                            onBack = { screen = "home" },
                            onSave = { newSettings ->
                                hueSettings = newSettings
                                screen = "home"
                            },
                            // Keep the home-level hueSettings draft up-to-date with in-UI selections so Test uses them
                            onSelectionChange = { lightsSet, groupsSet ->
                                hueSettings = hueSettings.copy(lightIds = lightsSet.toList(), groupIds = groupsSet.toList())
                            },
                             surfaceColor = dynamicSurfaceColor,
                             onSurfaceColor = dynamicOnSurfaceColor,
                             primaryColor = dynamicPrimaryColor,
                             onPrimaryColor = dynamicOnPrimaryColor,
                             actionTextColor = actionTextColor,
                             shadowElevation = cardShadow
                         )
                    }
                }

                "brightness" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        BrightnessScreen(
                            settingsManager = settingsManager,
                            initial = hueSettings,
                            onBack = { screen = "home" },
                            onSave = { newSettings ->
                                hueSettings = newSettings
                                screen = "home"
                            },
                            actionTextColor = actionTextColor
                        )
                    }
                }

                "color" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        // UPDATED: pass hueViewModel so we can detect color capability of selected targets
                        ColorScreen(
                            initial = hueSettings,
                            hueViewModel = hueViewModel,
                            onBack = { screen = "home" },
                            onSave = { newSettings ->
                                hueSettings = newSettings
                                screen = "home"
                            },
                            actionTextColor = actionTextColor
                        )
                    }
                }
            }
        }
    }
}

/** ---------------------------------------------------
 * LIGHT SELECTION SCREEN
 * --------------------------------------------------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LightsSelectionScreen(
    settingsManager: SettingsManager,
    hueViewModel: HueViewModel,
    initial: HueAutomationData,
    onBack: () -> Unit,
    onSave: (HueAutomationData) -> Unit,
    onSelectionChange: (selectedLights: Set<String>, selectedGroups: Set<String>) -> Unit,
    surfaceColor: Color,
    onSurfaceColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    actionTextColor: Color,
    shadowElevation: Dp
) {
    val lights by hueViewModel.lights.collectAsState()
    val groups by hueViewModel.groups.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var selectedLights by remember { mutableStateOf(initial.lightIds.toSet()) }
    var selectedGroups by remember { mutableStateOf(initial.groupIds.toSet()) }

    LaunchedEffect(settingsManager) {
        try {
            val sd = settingsManager.loadInitialSettings()
            val persisted = sd.hueAutomation
            selectedLights = persisted.lightIds.toSet()
            selectedGroups = persisted.groupIds.toSet()
            // notify caller of initial loaded selection so the home draft stays in sync
            onSelectionChange(selectedLights, selectedGroups)
        } catch (_: Exception) {
        }
    }

    var currentGroupId by remember { mutableStateOf<String?>(null) }
    val currentGroup = groups.find { it.id == currentGroupId }

    when (currentGroupId) {
        null -> {
            // Compute a set of group IDs that should appear visually selected: either the group is selected
            // explicitly (selectedGroups) or any child light of the group is selected (selectedLights).
            val visualSelectedGroups = remember(selectedGroups, selectedLights, groups) {
                val byChild = groups.filter { g -> g.lightIds.any { selectedLights.contains(it) } }.map { it.id }.toSet()
                selectedGroups + byChild
            }

            GroupListScreen(
                groups = groups,
                selectedGroups = selectedGroups,
                // selectedLights = selectedLights,
                visualSelectedGroups = visualSelectedGroups,
                onClearGroupChildren = { gid ->
                    // remove all lights of that group from selection
                    val group = groups.find { it.id == gid }
                    if (group != null) {
                        selectedLights = selectedLights - group.lightIds.toSet()
                        onSelectionChange(selectedLights, selectedGroups)
                    }
                },
                 onToggleGroup = { id, isSelected ->
                     selectedGroups = if (isSelected) selectedGroups + id else selectedGroups - id
                     onSelectionChange(selectedLights, selectedGroups)
                 },
                onGroupClick = { id -> currentGroupId = id },
                onSave = {
                    val newHue = initial.copy(
                        lightIds = selectedLights.toList(),
                        groupIds = selectedGroups.toList()
                    )

                    coroutineScope.launch {
                        try {
                            val sd = settingsManager.loadInitialSettings()
                            val merged = sd.copy(hueAutomation = newHue)
                            settingsManager.applySettings(merged)
                        } catch (_: Exception) {
                        }
                        onSave(newHue)
                    }
                },
                onBack = onBack,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                primaryColor = primaryColor,
                onPrimaryColor = onPrimaryColor,
                actionTextColor = actionTextColor,
                shadowElevation = shadowElevation
            )
        }

        else -> {
            val groupLights = lights.filter { l ->
                currentGroup?.lightIds?.contains(l.id) == true
            }

            GroupLightsScreen(
                groupName = currentGroup?.name ?: "",
                lights = groupLights,
                selectedLights = selectedLights,
                onToggleLight = { lightId, isSelected ->
                    selectedLights = if (isSelected) selectedLights + lightId else selectedLights - lightId
                    onSelectionChange(selectedLights, selectedGroups)
                },
                // Save from the group-light view: persist current selections and bubble the new settings up.
                onSaveLights = {
                    val newHue = initial.copy(
                        lightIds = selectedLights.toList(),
                        groupIds = selectedGroups.toList()
                    )

                    coroutineScope.launch {
                        try {
                            val sd = settingsManager.loadInitialSettings()
                            val merged = sd.copy(hueAutomation = newHue)
                            settingsManager.applySettings(merged)
                        } catch (_: Exception) {
                        }
                        // inform the caller that settings changed
                        onSave(newHue)
                    }

                    // return to groups list
                    currentGroupId = null
                },
                onBack = { currentGroupId = null },
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                primaryColor = primaryColor,
                onPrimaryColor = onPrimaryColor,
                actionTextColor = actionTextColor,
                shadowElevation = shadowElevation
            )
        }
    }
}

@Composable
private fun GroupListScreen(
    groups: List<HueGroup>,
    selectedGroups: Set<String>,
    // visualSelectedGroups controls only the visual state of the group's switch. It does NOT change
    // selection semantics when toggling; toggling still only adds/removes the group's id via onToggleGroup.
    visualSelectedGroups: Set<String>,
    // invoked when the user wants to clear all child light selections for a group (toggle-off visual-only case)
    onClearGroupChildren: (groupId: String) -> Unit,
    onToggleGroup: (groupId: String, isSelected: Boolean) -> Unit,
    onGroupClick: (groupId: String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    surfaceColor: Color,
    onSurfaceColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    actionTextColor: Color,
    shadowElevation: Dp
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(groups) { g ->
                // Determine whether the group is actually selected
                val hasGroupSelected = selectedGroups.contains(g.id)
                // Visual checked: either the group is selected OR any child lights are selected
                val checked = hasGroupSelected || visualSelectedGroups.contains(g.id)

                // Build a safe toggle handler: if the group toggle is turned ON -> add group id.
                // If turned OFF and the group id is present -> remove group id.
                // If turned OFF but the group id wasn't present (it was only visually ON because of child lights)
                // -> clear the child light selections instead.
                val toggleHandler: (Boolean) -> Unit = { newState ->
                    if (newState) {
                        onToggleGroup(g.id, true)
                    } else {
                        if (hasGroupSelected) {
                            onToggleGroup(g.id, false)
                        } else {
                            onClearGroupChildren(g.id)
                        }
                    }
                }

                GroupRow(
                    name = g.name,
                    isSelected = checked,
                    onClick = { onGroupClick(g.id) },
                    onToggle = toggleHandler,
                    surfaceColor = surfaceColor,
                    onSurfaceColor = onSurfaceColor,
                    primaryColor = primaryColor,
                    onPrimaryColor = onPrimaryColor,
                    shadowElevation = shadowElevation
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
            ) { Text("Save") }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
            ) { Text("Cancel", color = actionTextColor) }
        }
    }
}

@Composable
private fun GroupRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    surfaceColor: Color,
    onSurfaceColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    shadowElevation: Dp
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = surfaceColor,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = onSurfaceColor
                )
            }

            Switch(
                checked = isSelected,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = primaryColor,
                    checkedTrackColor = primaryColor.copy(alpha = 0.35f),
                    uncheckedThumbColor = onPrimaryColor,
                    uncheckedTrackColor = primaryColor.copy(alpha = 0.12f)
                )
            )
        }
    }
}

@Composable
private fun GroupLightsScreen(
    groupName: String,
    lights: List<HueLight>,
    selectedLights: Set<String>,
    onToggleLight: (lightId: String, isSelected: Boolean) -> Unit,
    // Persist/save the selection from the group lights screen. This does not change selection logic
    // immediately; the caller should persist and then navigate back.
    onSaveLights: () -> Unit,
    onBack: () -> Unit,
    surfaceColor: Color,
    onSurfaceColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    actionTextColor: Color,
    shadowElevation: Dp
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = groupName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(16.dp))

        Text(text = "Lights", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(lights) { l ->
                val checked = selectedLights.contains(l.id)

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = surfaceColor,
                    tonalElevation = 0.dp,
                    shadowElevation = shadowElevation,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = l.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            color = onSurfaceColor
                        )

                        Switch(
                            checked = checked,
                            onCheckedChange = { onToggleLight(l.id, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = primaryColor,
                                checkedTrackColor = primaryColor.copy(alpha = 0.35f),
                                uncheckedThumbColor = onPrimaryColor,
                                uncheckedTrackColor = primaryColor.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSaveLights,
                modifier = Modifier.weight(1f)
            ) { Text("Save") }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
            ) { Text("Cancel", color = actionTextColor) }
        }
    }
}

/** ---------------------------------------------------
 * BRIGHTNESS SCREEN
 * --------------------------------------------------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrightnessScreen(
    settingsManager: SettingsManager,
    initial: HueAutomationData,
    onBack: () -> Unit,
    onSave: (HueAutomationData) -> Unit,
    actionTextColor: Color
) {
    var brightness by remember { mutableStateOf(initial.brightness) }
    val coroutineScope = rememberCoroutineScope()

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
            // round to nearest integer so the right-most position can reach 100
            onValueChange = { brightness = it.roundToInt() },
            valueRange = 0f..100f
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val newHue = initial.copy(brightness = brightness)
                    coroutineScope.launch {
                        try {
                            val sd = settingsManager.loadInitialSettings()
                            val merged = sd.copy(hueAutomation = newHue)
                            settingsManager.applySettings(merged)
                        } catch (_: Exception) {
                        }
                        onSave(newHue)
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Save") }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
            ) { Text("Cancel", color = actionTextColor) }
        }
    }
}

/** ---------------------------------------------------
 * COLOR SCREEN (NEW: Scene gallery + Custom wheel)
 * --------------------------------------------------- */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorScreen(
    initial: HueAutomationData,
    hueViewModel: HueViewModel,
    onBack: () -> Unit,
    onSave: (HueAutomationData) -> Unit,
    actionTextColor: Color
) {
    val lights by hueViewModel.lights.collectAsState()
    val groups by hueViewModel.groups.collectAsState()

    val hasColor = remember(initial.lightIds, initial.groupIds, lights, groups) {
        selectedTargetsHaveColor(initial, lights, groups)
    }

    // Prefer bridge-provided scenes when available; fall back to curated presets.
    val bridgeScenes by hueViewModel.scenes.collectAsState()
    val fallbackScenes = remember {
        listOf(
            SceneUi("bright", "Bright", Color(0xFFFFF1D6)),
            SceneUi("relax", "Relax", Color(0xFFFFD8A8)),
            SceneUi("nightlight", "Nightlight", Color(0xFFB8742A)),
            SceneUi("spring", "Spring", Color(0xFFB6FFB6)),
            SceneUi("frosty", "Frosty dawn", Color(0xFFCFE8FF)),
            SceneUi("sunset", "Sunset", Color(0xFFFF9B6A)),
        )
    }
    val scenes = if (bridgeScenes.isNotEmpty()) {
        bridgeScenes.map { SceneUi(it.id, it.name, Color.White) } // no preview color from bridge; use white
    } else fallbackScenes

    // Use a "draft" state, then save.
    var draft by remember { mutableStateOf(initial) }

    // If HueAutomationData doesn't yet have these fields, you'll need to add them.
    // These accessors compile only if you added: colorMode/sceneId/colorArgb/ctMired to HueAutomationData.
    // If you haven't added them, search for "draft.colorMode" and replace with your temporary state.
    Column(Modifier.fillMaxSize().padding(16.dp)) {

        Text("Hue scene gallery", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
             columns = GridCells.Fixed(3),
             verticalArrangement = Arrangement.spacedBy(12.dp),
             horizontalArrangement = Arrangement.spacedBy(12.dp),
             modifier = Modifier.weight(1f)
         ) {
             item {
                 val selectedCustom = try { draft.colorMode != HueColorMode.SCENE } catch (_: Throwable) { false }
                 SceneTile(
                     title = "Custom",
                     preview = if (hasColor) Color.White else Color(0xFFFFE7C2),
                     selected = selectedCustom,
                     onClick = {
                         draft = draft.copy(
                             colorMode = if (hasColor) HueColorMode.CUSTOM_COLOR else HueColorMode.CUSTOM_WHITE,
                             sceneId = null
                         )
                     }
                 )
             }

            items(scenes.size) { idx ->
                val s = scenes[idx]
                val selected = try { draft.colorMode == HueColorMode.SCENE && draft.sceneId == s.id } catch (_: Throwable) { false }
                SceneTile(
                    title = s.name,
                    preview = s.preview,
                    selected = selected,
                    onClick = {
                        // persist the preview color as ARGB so we can show it later
                        val previewArgb = try { s.preview.toArgb() } catch (_: Throwable) { 0xFFFFFFFF.toInt() }
                        draft = draft.copy(colorMode = HueColorMode.SCENE, sceneId = s.id, scenePreviewArgb = previewArgb)
                    }
                )
            }
         }


         // Custom picker area
         val mode = try { draft.colorMode } catch (_: Throwable) { HueColorMode.SCENE }

        if (mode == HueColorMode.CUSTOM_COLOR && hasColor) {
             Spacer(Modifier.height(12.dp))
             HueColorWheel(
                 colorArgb = try { draft.colorArgb } catch (_: Throwable) { 0xFFFFFFFF.toInt() },
                 onColorChanged = { argb ->
                     try {
                         draft = draft.copy(colorArgb = argb)
                     } catch (_: Throwable) {
                     }
                 }
             )
         } else if (mode == HueColorMode.CUSTOM_WHITE || (!hasColor && mode != HueColorMode.SCENE)) {
             Spacer(Modifier.height(12.dp))
             WhiteTempWheel(
                 ctMired = try { draft.colorTemperature } catch (_: Throwable) { 366 },
                 onCtChanged = { mired ->
                     try {
                         draft = draft.copy(colorTemperature = mired)
                     } catch (_: Throwable) {
                     }
                 }
             )
         }

         Spacer(Modifier.height(16.dp))
         Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
             Button(
                 onClick = { onSave(draft) },
                 modifier = Modifier.weight(1f)
             ) { Text("Save") }

             OutlinedButton(
                 onClick = onBack,
                 modifier = Modifier.weight(1f),
                 colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
             ) { Text("Cancel", color = actionTextColor) }
         }
     }
 }

@Composable
private fun SceneTile(
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

/** Hue-like color wheel (for color capable lights) */
@Composable
private fun HueColorWheel(
    colorArgb: Int,
    onColorChanged: (Int) -> Unit,
    size: Dp = 280.dp
) {
    var px by remember { mutableStateOf(1f) }
    val radius = px / 2f

    val hsv = remember(colorArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(colorArgb, it) }
    }
    var hue by remember(colorArgb) { mutableStateOf(hsv[0]) }
    var sat by remember(colorArgb) { mutableStateOf(hsv[1]) }

    fun updateFromTouch(x: Float, y: Float) {
        val cx = radius
        val cy = radius
        val dx = x - cx
        val dy = y - cy
        val r = min(sqrt(dx * dx + dy * dy), radius)
        val angle = ((atan2(dy, dx) * 180f / Math.PI.toFloat()) + 360f) % 360f

        hue = angle
        sat = (r / radius).coerceIn(0f, 1f)

        val argb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, 1f))
        onColorChanged(argb)
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(size)
                .onGloballyPositioned { px = it.size.width.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> updateFromTouch(o.x, o.y) },
                        onDrag = { c, _ -> updateFromTouch(c.position.x, c.position.y) }
                    )
                }
        ) {
            val sweep = Brush.sweepGradient(
                listOf(
                    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                )
            )
            drawCircle(brush = sweep, radius = radius)

            // white center (sat->0)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    radius = radius
                ),
                radius = radius
            )

            // thumb
            val thumbR = sat * radius
            val theta = hue * (Math.PI.toFloat() / 180f)
            val tx = radius + thumbR * cos(theta)
            val ty = radius + thumbR * sin(theta)

            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 18f, center = Offset(tx, ty))
            drawCircle(Color.White, radius = 14f, center = Offset(tx, ty))
        }
    }
}

/** Warm/cool (ct) wheel for non-color lights */
@Composable
private fun WhiteTempWheel(
    ctMired: Int,
    onCtChanged: (Int) -> Unit,
    size: Dp = 280.dp,
    minMired: Int = 153,
    maxMired: Int = 500
) {
    var px by remember { mutableStateOf(1f) }
    val radius = px / 2f

    fun yToMired(y: Float): Int {
        val t = (y / (radius * 2f)).coerceIn(0f, 1f)
        return (minMired + (maxMired - minMired) * t).toInt()
    }

    fun miredToY(m: Int): Float {
        val t = ((m - minMired).toFloat() / (maxMired - minMired).toFloat()).coerceIn(0f, 1f)
        return (t * radius * 2f)
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(size)
                .onGloballyPositioned { px = it.size.width.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> onCtChanged(yToMired(o.y)) },
                        onDrag = { c, _ -> onCtChanged(yToMired(c.position.y)) }
                    )
                }
        ) {
            val warm = Color(0xFFFFD7A1)
            val cool = Color(0xFFCFE6FF)

            drawCircle(
                brush = Brush.verticalGradient(listOf(warm, Color.White, cool)),
                radius = radius
            )

            // subtle edge vignette
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                    radius = radius
                ),
                radius = radius
            )

            val ty = miredToY(ctMired).coerceIn(0f, radius * 2f)
            val tx = radius

            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 18f, center = Offset(tx, ty))
            drawCircle(Color.White, radius = 14f, center = Offset(tx, ty))
        }
    }
}

/** Detect if selected targets include any color-capable light */
private fun selectedTargetsHaveColor(
    data: HueAutomationData,
    allLights: List<HueLight>,
    allGroups: List<HueGroup>
): Boolean {
    val groupMemberIds = allGroups
        .filter { data.groupIds.contains(it.id) }
        .flatMap { it.lightIds }

    val targetIds = (data.lightIds + groupMemberIds).toSet()
    val affected = if (targetIds.isEmpty()) allLights else allLights.filter { it.id in targetIds }

    // You must have HueLight.supportsColor for this to work:
    return affected.any { it.supportsColor }
}

/** ---------------------------------------------------
 * HOME FEATURE CARD (self-contained helper)
 * --------------------------------------------------- */
@Composable
private fun FeatureCard(
    item: HomeItem,
    surfaceColor: Color,
    onSurfaceColor: Color,
    primaryColor: Color,
    descriptionColor: Color,
    shadowElevation: Dp,
    onClick: () -> Unit
) {
    Surface(
        color = surfaceColor,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = primaryColor.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.icon, contentDescription = null, tint = primaryColor)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.titleMedium, color = onSurfaceColor)
                Spacer(Modifier.height(2.dp))
                Text(item.description, style = MaterialTheme.typography.bodyMedium, color = descriptionColor)
            }

            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack, // quick chevron-ish; replace if you want
                contentDescription = null,
                tint = descriptionColor,
                modifier = Modifier.rotate(180f)
            )
        }
    }
}

/** ---------------------------------------------------
 * TEST RUNNER (update later to support scenes + ct)
 * --------------------------------------------------- */
private fun runTest(settings: HueAutomationData, hueViewModel: HueViewModel, scope: CoroutineScope) {
    scope.launch {
        val allLights = hueViewModel.lights.value
        val allGroups = hueViewModel.groups.value

        val groupMemberIds = allGroups
            .filter { settings.groupIds.contains(it.id) }
            .flatMap { it.lightIds }

        val targetIds = (settings.lightIds + groupMemberIds).toSet()
        // If nothing is selected, do not default to all lights — treat as no-op to avoid surprising behavior.
        if (targetIds.isEmpty()) {
            // nothing selected -> don't run the test
            return@launch
        }
        val affected = allLights.filter { targetIds.contains(it.id) }

        // Capture the original on/bri state for ALL lights so we can restore everything even if a scene
        // recall or other API call touched more lights than we targeted.
        val originalStates = allLights.associate { it.id to (it.on to it.brightness) }

        // Use brightness percent (0..100) expected by HueViewModel.setLightBrightnessSuspend
        val targetBriPercent = settings.brightness.coerceIn(0, 100)

        // Apply target brightness for affected lights immediately (this sets 'on' appropriately as well)
        // Do this in parallel so lights come on at the requested brightness instead of briefly showing
        // their previous brightness when turned on separately.
        try {
            kotlinx.coroutines.coroutineScope {
                affected.map { l -> async { try { hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent) } catch (_: Exception) {} } }.awaitAll()
            }
        } catch (_: Exception) {}

        // Decide how long the test state should be visible (increase this so the settings are shown longer).
        val showDurationMs = 3_500L

        when (settings.colorMode) {
            HueColorMode.CUSTOM_COLOR -> {
                // apply brightness + color together per affected light to avoid visible flashes
                try {
                    kotlinx.coroutines.coroutineScope {
                        affected.map { l -> async {
                            try { if (l.supportsColor) hueViewModel.setColorAndBrightnessForLightSuspend(l.id, settings.colorArgb, targetBriPercent) else hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent) } catch (_: Exception) {}
                        } }.awaitAll()
                    }
                } catch (_: Exception) {}
            }
            HueColorMode.CUSTOM_WHITE -> {
                try {
                    kotlinx.coroutines.coroutineScope {
                        affected.map { l -> async {
                            try { if (l.supportsCt) hueViewModel.setCtAndBrightnessForLightSuspend(l.id, settings.colorTemperature, targetBriPercent) else hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent) } catch (_: Exception) {}
                        } }.awaitAll()
                    }
                } catch (_: Exception) {}
            }
            HueColorMode.SCENE -> {
                settings.sceneId?.let { sid ->
                    if (sid.isNotEmpty()) {
                        try {
                            val gid = settings.groupIds.firstOrNull()
                            if (!gid.isNullOrEmpty()) {
                                // recall scene for the group (scene will set color & its own brightness)
                                hueViewModel.recallSceneForGroupSuspend(sid, gid)

                                // give the bridge a short moment to apply the scene
                                delay(600)

                                // Override the scene brightness for affected lights in parallel (may re-apply same value)
                                try {
                                    kotlinx.coroutines.coroutineScope {
                                        affected.map { l -> async { try { hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent) } catch (_: Exception) {} } }.awaitAll()
                                    }
                                } catch (_: Exception) {}

                                // If we stored a preview color for the scene, apply it afterwards per-light in parallel (use combined setter)
                                val previewArgb = try { settings.scenePreviewArgb } catch (_: Exception) { 0 }
                                if (previewArgb != 0) {
                                    delay(200)
                                    try {
                                        kotlinx.coroutines.coroutineScope {
                                            affected.map { l -> async { if (l.supportsColor) try { hueViewModel.setColorAndBrightnessForLightSuspend(l.id, previewArgb, targetBriPercent) } catch (_: Exception) {} } }.awaitAll()
                                        }
                                    } catch (_: Exception) {}
                                }
                            } else {
                                // No group selected -> do not recall scene globally. Apply preview per affected light in parallel
                                val previewArgb = try { settings.scenePreviewArgb } catch (_: Exception) { 0 }
                                if (previewArgb != 0) {
                                    try {
                                        kotlinx.coroutines.coroutineScope {
                                            affected.map { l -> async {
                                                try { hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent) } catch (_: Exception) {}
                                                if (l.supportsColor) try { hueViewModel.setColorForLightSuspend(l.id, previewArgb) } catch (_: Exception) {}
                                            } }.awaitAll()
                                        }
                                    } catch (_: Exception) {}
                                } else {
                                    try {
                                        kotlinx.coroutines.coroutineScope {
                                            affected.map { l -> async { try { hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent) } catch (_: Exception) {} } }.awaitAll()
                                        }
                                    } catch (_: Exception) {}
                                }
                             }
                         } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        // Debug: log affected ids
        try { android.util.Log.d("HueAutomation", "runTest affected lights: ${affected.map { it.id }}") } catch (_: Throwable) {}

        // Keep the test state visible for a longer duration
        try { delay(showDurationMs) } catch (_: Exception) {}

        // Restore original states for ALL known lights (this will also revert any unintended global changes)
        try {
            kotlinx.coroutines.coroutineScope {
                originalStates.map { (id, pair) -> async {
                    val (wasOn, bri) = pair
                    try {
                        if (wasOn) {
                            try { hueViewModel.setLightBrightnessSuspend(id, bri) } catch (_: Exception) {}
                            delay(50)
                            try { hueViewModel.setLightOnSuspend(id, true) } catch (_: Exception) {}
                        } else {
                            try { hueViewModel.setLightOnSuspend(id, false) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                } }.awaitAll()
            }
        } catch (_: Exception) {}
    }
}
