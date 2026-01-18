package com.example.companionpulsebreak.screens

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.WbSunny
import com.example.companionpulsebreak.sync.HueLight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.commonlibrary.HueAutomationData
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel
import com.example.companionpulsebreak.sync.HueViewModel
import com.example.companionpulsebreak.sync.SettingsManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.commonlibrary.HueColorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate
import org.json.JSONObject

/**
 * NOTE:
 * This file keeps the high-level HueAutomationHomeScreen and helper card + test runner.
 * Detailed screens (light selection, brightness, scene/color) were moved to separate files
 * for clarity: `HueLightSelectionScreens.kt`, `HueBrightnessScreen.kt`, `HueSceneScreens.kt`.
 */

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
    var screen by rememberSaveable { mutableStateOf("home") }
    val settingsFlow by remember { mutableStateOf(settingsManager.settingsFlow) }
    var hueSettings by remember { mutableStateOf(HueAutomationData()) }
    // remember last persisted value to avoid re-writing immediately after load
    var lastPersisted by remember { mutableStateOf<HueAutomationData?>(null) }

    // Initialize draft from persisted settings once when the screen is shown so we don't
    // immediately overwrite persisted settings with the empty draft.
    LaunchedEffect(settingsFlow) {
        try {
            val sd = settingsManager.loadInitialSettings()
            val persisted = sd.hueAutomation
            // only seed the draft if nothing was edited yet
            if (hueSettings.lightIds.isEmpty() && hueSettings.groupIds.isEmpty()) {
                hueSettings = persisted
            }
            lastPersisted = persisted
            Log.d("HueAutomation", "initial persisted hueAutomation: lights=${persisted.lightIds} groups=${persisted.groupIds}")
        } catch (e: Exception) {
            Log.w("HueAutomation", "failed to load initial settings: ${e.message}")
        }
    }

    // Persist hueSettings automatically whenever it changes (debounced by lastPersisted comparison).
    LaunchedEffect(hueSettings) {
        // Do not persist until we have loaded the initial persisted settings (lastPersisted will be non-null).
        if (lastPersisted == null) return@LaunchedEffect
        // If nothing changed compared to the persisted copy, skip the save.
        if (hueSettings == lastPersisted) return@LaunchedEffect

        // launch a background save; update lastPersisted when done.
        scope.launch {
            try {
                val sd = settingsManager.loadInitialSettings()
                val merged = sd.copy(hueAutomation = hueSettings)
                settingsManager.applySettings(merged)
                lastPersisted = hueSettings
            } catch (_: Exception) {
                // ignore save failures for now
            }
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
        // Snackbar host for in-UI feedback (e.g., when Test is pressed but nothing selected)
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor))
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
                                Icons.Filled.ViewModule,
                                "Rooms & Zones",
                                "Select which lights this automation controls"
                            ),
                            HomeItem(
                                Icons.Filled.WbSunny,
                                "Brightness",
                                "Set the target brightness for the automation"
                            ),
                            HomeItem(
                                Icons.Filled.Palette,
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
                                    onClick = {
                                        Log.d("HueAutomation", "Test button clicked. hueSettings.lightIds=${hueSettings.lightIds}, groupIds=${hueSettings.groupIds}")

                                        scope.launch {
                                            // For Test we must only target the individual lights currently selected in the LightSelection screen.
                                            val selectedIds = hueSettings.lightIds.toSet()
                                            Log.d("HueAutomation", "Computed selected lightIds (from draft)=$selectedIds")

                                            if (selectedIds.isEmpty()) {
                                                // Abort: user didn't select any individual lights in the UI — do not touch groups or default to all.
                                                Log.d("HueAutomation", "Test aborted: no individual lights selected in LightSelection")
                                                try { snackbarHostState.showSnackbar("No lights selected for Test") } catch (_: Exception) {}
                                            } else {
                                                // Build a settings copy that includes only the selected light ids so runTest targets them
                                                val useSettings = hueSettings.copy(lightIds = selectedIds.toList())
                                                runTest(useSettings, hueViewModel, scope, snackbarHostState)
                                            }
                                         }
                                     },
                                     modifier = Modifier.fillMaxWidth(),
                                     colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
                                 ) { Text("Test", color = actionTextColor) }
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
                            // Keep the home-level hueSettings draft up-to-date with in-UI selections so Test uses them
                            onSelectionChange = { lightsSet, groupsSet ->
                                Log.d("HueAutomation", "onSelectionChange fired in home: lights=$lightsSet groups=$groupsSet")
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
                            // update the home draft immediately so UI elsewhere reflects changes
                            onDraftChanged = { newSettings -> hueSettings = newSettings },
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
                        HueColorScreen(
                            settingsManager = settingsManager,
                            initial = hueSettings,
                            hueViewModel = hueViewModel,
                            onBack = { screen = "home" },
                            onDraftChanged = { newSettings -> hueSettings = newSettings },
                            actionTextColor = actionTextColor
                        )
                    }
                }
            }
        }
    }
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
             verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
         ) {
            Surface(
                color = primaryColor.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(imageVector = item.icon, contentDescription = null, tint = primaryColor)
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
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = descriptionColor,
                modifier = Modifier.rotate(180f)
            )
        }
    }
}

/** ---------------------------------------------------
 * TEST RUNNER (kept in this file)
 * --------------------------------------------------- */
private fun runTest(settings: HueAutomationData, hueViewModel: HueViewModel, scope: CoroutineScope, snackbarHostState: SnackbarHostState) {
     scope.launch {
        var _successCount = 0
        var _failureCount = 0

        suspend fun safeCall(desc: String, block: suspend () -> Unit) {
            Log.w("HueAutomation", "safeCall attempt: $desc")
            try {
                block()
                _successCount += 1
                Log.i("HueAutomation", "safeCall succeeded: $desc")
            } catch (e: Exception) {
                _failureCount += 1
                Log.w("HueAutomation", "safeCall failed: $desc: ${e.message}", e)
            }
        }

        // Sanity: ensure bridge info present
        val ip = hueViewModel.bridgeIp.value
        val user = hueViewModel.hueUsername.value
        if (ip.isNullOrEmpty() || user.isNullOrEmpty()) {
            try { snackbarHostState.showSnackbar("Hue not configured: pair with a bridge first") } catch (_: Exception) {}
            return@launch
        }

        val allLights = hueViewModel.lights.value

        // For Test, ONLY target the explicit individual lights selected in the LightSelection UI.
        // The user expects a preview: include selected lights even if they're currently OFF so we can
        // turn them on for the preview. We'll restore original states afterwards.
        val affected = computeTestAffectedLights(allLights, settings, requireOn = false)

        // Capture the original ON/brightness state for ALL lights as a fallback, but for full
        // color/ct/hue restoration we fetch the raw per-light state JSON from the bridge and
        // restore from that after the preview.
        val originalStates = allLights.associate { it.id to (it.on to it.brightness) }

        // Fetch raw state objects for all lights in a single request; then pick entries for affected ids.
        val rawStates = try {
            val allRaw = try { hueViewModel.fetchAllLightsRawStates() } catch (e: Exception) {
                Log.w("HueAutomation", "fetchAllLightsRawStates failed: ${e.message}")
                emptyMap<String, JSONObject?>()
            }
            // filter to affected ids only
            allRaw.filterKeys { k -> affected.any { it.id == k } }
        } catch (e: Exception) {
            Log.w("HueAutomation", "failed to prepare raw light states: ${e.message}")
            emptyMap<String, JSONObject?>()
        }

        val targetBriPercent = settings.brightness.coerceIn(0, 100)

        // Build a per-light action and run them in a single parallel pass to minimize round trips.
        val start = System.currentTimeMillis()

        try {
            // Reduce PUTs by applying group-level actions where safe: if an entire group's member IDs
            // are included in the affected set, use one PUT for the group. Remaining lights are updated per-light.
            val affectedIds = affected.map { it.id }.toMutableSet()
            val groupsAll = hueViewModel.groups.value

            // Greedy pick: prefer larger groups first
            val candidateGroups = groupsAll.sortedByDescending { it.lightIds.size }
                .filter { g -> g.lightIds.all { affectedIds.contains(it) } }

            val deferreds = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            kotlinx.coroutines.coroutineScope {
                // Apply group actions for fully-covered groups
                for (g in candidateGroups) {
                    // ensure we haven't already removed the group's members
                    val members = g.lightIds.filter { affectedIds.contains(it) }
                    if (members.isEmpty()) continue

                    // choose action based on color mode
                    val d = async {
                        safeCall("apply preview group ${g.id}") {
                            when (settings.colorMode) {
                                HueColorMode.CUSTOM_COLOR -> {
                                    hueViewModel.setGroupColorAndBrightnessSuspend(g.id, settings.colorArgb, targetBriPercent, immediate = true)
                                }
                                HueColorMode.CUSTOM_WHITE -> {
                                    hueViewModel.setGroupCtAndBrightnessForGroupSuspend(g.id, settings.colorTemperature, targetBriPercent, immediate = true)
                                }
                                HueColorMode.SCENE -> {
                                    val previewArgb = settings.scenePreviewArgb
                                    if (previewArgb != 0) {
                                        hueViewModel.setGroupColorAndBrightnessSuspend(g.id, previewArgb, targetBriPercent, immediate = true)
                                    } else {
                                        hueViewModel.setGroupBrightnessForGroupSuspend(g.id, targetBriPercent, immediate = true)
                                    }
                                }
                            }
                        }
                        // remove members from remaining set so we don't update per-light
                        members.forEach { affectedIds.remove(it) }
                    }
                    deferreds.add(d)
                }

                // For any remaining lights, send per-light updates in parallel
                val remaining = affected.filter { affectedIds.contains(it.id) }
                for (l in remaining) {
                    val d = async {
                        safeCall("apply preview ${l.id}") {
                            when (settings.colorMode) {
                                HueColorMode.CUSTOM_COLOR -> {
                                    if (l.supportsColor) {
                                        hueViewModel.setColorAndBrightnessForLightSuspend(l.id, settings.colorArgb, targetBriPercent, immediate = true)
                                    } else {
                                        hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent, immediate = true)
                                    }
                                }
                                HueColorMode.CUSTOM_WHITE -> {
                                    if (l.supportsCt) {
                                        hueViewModel.setCtAndBrightnessForLightSuspend(l.id, settings.colorTemperature, targetBriPercent, immediate = true)
                                    } else {
                                        hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent, immediate = true)
                                    }
                                }
                                HueColorMode.SCENE -> {
                                    val previewArgb = settings.scenePreviewArgb
                                    if (settings.lightIds.isNotEmpty() && previewArgb != 0 && l.supportsColor) {
                                        hueViewModel.setColorAndBrightnessForLightSuspend(l.id, previewArgb, targetBriPercent, immediate = true)
                                    } else {
                                        hueViewModel.setLightBrightnessSuspend(l.id, targetBriPercent, immediate = true)
                                    }
                                }
                            }
                        }
                    }
                    deferreds.add(d)
                }

                // await all group + per-light actions
                try { deferreds.awaitAll() } catch (e: Exception) { Log.w("HueAutomation", "apply preview grouped awaitAll failed: ${e.message}", e) }
            }
        } catch (e: Exception) {
            Log.w("HueAutomation", "apply preview grouped failed: ${e.message}", e)
        }

        val showDurationMs = 3_000L

        try { delay(showDurationMs) } catch (_: Exception) {}

        try {
            kotlinx.coroutines.coroutineScope {
                // Prefer restoring from rawStates (full color/ct/hue). Fall back to originalStates if raw missing.
                affected.map { l -> async {
                    val id = l.id
                    val raw = rawStates[id]
                    safeCall("restore state $id") {
                        if (raw != null) {
                            hueViewModel.restoreLightStateFromRaw(id, raw)
                        } else {
                            // fallback: restore on/bri only
                            val pair = originalStates[id]
                            if (pair != null) {
                                val (wasOn, bri) = pair
                                if (wasOn) {
                                    hueViewModel.setLightBrightnessSuspend(id, bri)
                                    delay(10)
                                    hueViewModel.setLightOnSuspend(id, true)
                                } else {
                                    hueViewModel.setLightOnSuspend(id, false)
                                }
                            }
                        }
                    }
                } }.awaitAll()
            }
        } catch (_: Exception) {
            // ignore
        }
    }
}

// Helper used by unit tests: compute which lights would be affected by Test based on current bridge state
@Suppress("unused")
internal fun computeTestAffectedLights(allLights: List<HueLight>, settings: HueAutomationData, requireOn: Boolean = true): List<HueLight> {
    val targetIds = settings.lightIds.toSet()
    if (targetIds.isEmpty()) return emptyList()
    var affected = allLights.filter { targetIds.contains(it.id) }
    if (requireOn) affected = affected.filter { it.on }
    return affected
}
