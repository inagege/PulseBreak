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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate
import org.json.JSONObject

/**
 * NOTE:
 * This file keeps the high-level HueAutomationHomeScreen and helper card + test runner.
 * Detailed screens (light selection, brightness, scene/color) were moved to separate files
 * for clarity: `HueLightSelectionScreens.kt`, `HueBrightnessScreen.kt`, `HueSceneScreens.kt`.
 */

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun HueAutomationHomeScreen(
    settingsManager: SettingsManager,
    modifier: Modifier = Modifier,
    settingsViewModel: CompanionSettingsViewModel = viewModel(),
    hueViewModel: HueViewModel = viewModel(),
    onBack: () -> Unit = {},
    onNoConnection: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf("home") }

    // Ensure developer bypass is disabled when this screen is composed so background
    // refreshes are not accidentally skipped (fixes case where devBypass remained true).
    LaunchedEffect(Unit) {
        try { hueViewModel.setDevBypass(false) } catch (_: Exception) {}
    }

    LaunchedEffect(screen) {
        try { Log.d("HueAutomation", "screen changed -> $screen at ${System.currentTimeMillis()}") } catch (_: Exception) {}
        // NOTE: debug devBypass toggling removed to avoid accidentally skipping refreshes when
        // navigating quickly between screens. Leaving the bypass enabled could cause the
        // detail screens to show empty state (observed in logs as "skipped due to devBypass").
    }
    val settingsFlow by remember { mutableStateOf(settingsManager.settingsFlow) }
    var hueSettings by remember { mutableStateOf(HueAutomationData()) }
    // remember last persisted value to avoid re-writing immediately after load
    var lastPersisted by remember { mutableStateOf<HueAutomationData?>(null) }

    // Initialize draft from persisted settings once when the screen is shown so we don't
    // immediately overwrite persisted settings with the empty draft.
    LaunchedEffect(settingsFlow) {
        try {
            // perform the initial DataStore read on IO to avoid blocking the UI/main coroutine
            val sd = withContext(kotlinx.coroutines.Dispatchers.IO) { settingsManager.loadInitialSettings() }
            val persisted = sd.hueAutomation
            // only seed the draft if nothing was edited yet
            if (hueSettings.lightIds.isEmpty() && hueSettings.groupIds.isEmpty()) {
                hueSettings = persisted
            }
            lastPersisted = persisted
            Log.d("HueAutomation", "initial persisted hueAutomation: lights=${persisted.lightIds} groups=${persisted.groupIds}")
        } catch (_: Exception) {
            Log.w("HueAutomation", "failed to load initial settings")
        }
    }

    // Persist hueSettings automatically whenever it changes — debounce rapid UI changes to avoid writes/ANRs.
    LaunchedEffect(Unit) {
        // Wait until initial persisted value loaded
        snapshotFlow { lastPersisted }.filterNotNull().first()
    }

    LaunchedEffect(Unit) {
        // Collect changes to hueSettings, debounce user interactions, then persist in IO dispatcher
        snapshotFlow { hueSettings }
            .debounce(500) // wait for 500ms of quiet before saving
            .filter { lastPersisted != null } // ensure initial load done
            .collect { newDraft ->
                if (newDraft == lastPersisted) return@collect
                try {
                    // perform saving using the lightweight hue-only helper to avoid blocking full reads
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        settingsManager.applyHueAutomation(newDraft)
                    }
                    lastPersisted = newDraft
                } catch (_: Exception) {
                    // ignore save failures for now
                }
            }
    }

    // ensure we refresh when the hue sub-screens are visible and we're connected
    // NOTE: avoid triggering refresh work while the Home screen is visible to prevent UI freezes
    val isConnected by hueViewModel.isConnected.collectAsState()
    val hasNotifiedNoConnection = remember { mutableStateOf(false) }
    LaunchedEffect(screen, isConnected) {
        // If we're navigating to any of the detail screens, proactively try to load
        // persisted bridge credentials and trigger a short forced refresh so the
        // `lights`/`groups` flows are populated for the detail UIs. Previously we
        // returned early when `isConnected` was false which could happen before the
        // ViewModel had loaded persisted credentials; that made the detail screens
        // show empty state even when credentials existed on disk.
        if (screen == "lights" || screen == "brightness" || screen == "color") {
            try { hueViewModel.setDevBypass(false) } catch (_: Exception) {}
            try { hueViewModel.loadPersistedCredentialsIfMissing() } catch (_: Exception) {}
            try {
                // Start a background force refresh (non-blocking)
                hueViewModel.forceRefreshHueState()
                // Also attempt a short wait for the refresh to populate lights/groups so
                // the detail screens can render immediately. This is best-effort and will
                // time out quickly if the bridge is unreachable.
                try { kotlinx.coroutines.withTimeout(2000) { hueViewModel.forceRefreshHueStateAndWait(1500, 200) } } catch (_: Exception) {}
            } catch (_: Exception) {}
        }

        // After attempting to seed credentials/refresh, fall back to the existing
        // connected-check behavior and show a notification if no bridge info is present.
        if (!isConnected) {
            if (!hasNotifiedNoConnection.value) {
                hasNotifiedNoConnection.value = true
                onNoConnection()
            }
            return@LaunchedEffect
        }

        // Only run periodic refreshes while on one of the Hue detail screens (not the Home list)
        if (screen == "lights" || screen == "brightness" || screen == "color") {
            hasNotifiedNoConnection.value = false
            // initial immediate refresh (non-blocking)
            hueViewModel.refreshHueState()
            // then periodically refresh while the detail screen remains visible
            while (screen == "lights" || screen == "brightness" || screen == "color") {
                try {
                    delay(6000)
                    hueViewModel.refreshHueState()
                } catch (_: Throwable) {
                    break
                }
            }
        }
    }

    // App-wide dynamic theme values (use safe initial and guard Color construction)
    val settings by settingsViewModel.settings.collectAsState(initial = com.example.commonlibrary.SettingsData())
    val buttonColor = runCatching { Color(settings.buttonColor) }.getOrElse { Color(0xFF90EE90) }
    val buttonTextColor = runCatching { Color(settings.buttonTextColor) }.getOrElse { Color(0xFF2F4F4F) }
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
                            color = if (settings.isDarkMode) buttonColor else buttonTextColor
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (screen == "home") onBack() else screen = "home"
                            }
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = if (settings.isDarkMode) buttonColor else buttonTextColor)
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

        // Sanity: ensure bridge info present. Try to load persisted credentials into the ViewModel
        // if the ViewModel delayed refresh (Home optimization) and credentials are available on disk.
        try { hueViewModel.loadPersistedCredentialsIfMissing() } catch (_: Exception) {}
        val ip = hueViewModel.bridgeIp.value
        val user = hueViewModel.hueUsername.value
        val userDisplay = user?.let { if (it.length > 6) it.take(3) + "..." + it.takeLast(3) else it } ?: "<none>"
        Log.d("HueAutomation", "runTest: using bridgeIp=$ip hueUser=$userDisplay")
        if (ip.isNullOrEmpty() || user.isNullOrEmpty()) {
            try { snackbarHostState.showSnackbar("Hue not configured: pair with a bridge first") } catch (_: Exception) {}
            return@launch
        }

        // Quick reachability check: if bridge isn't reachable with current credentials, surface a message
        val reachable = try { hueViewModel.checkBridgeReachable() } catch (_: Exception) { false }
        if (!reachable) {
            try { snackbarHostState.showSnackbar("Hue bridge not reachable — attempting anyway") } catch (_: Exception) {}
            Log.w("HueAutomation", "runTest: bridge not reachable or auth failed (ip=$ip user=$userDisplay) — continuing")
            // proceed anyway: don't abort the Test. We'll attempt actions and report successes/failures below.
        }

        // Attempt a short refresh to populate lights/groups before applying preview — non-blocking and short timeout.
        try {
            Log.d("HueAutomation", "runTest: requesting short FORCE refresh before preview")
            try { hueViewModel.forceRefreshHueState() } catch (_: Exception) {}
            try { kotlinx.coroutines.withTimeout(2000) { hueViewModel.forceRefreshHueStateAndWait(1500, 200) } } catch (_: Exception) {}
        } catch (_: Exception) {}

        val allLights = hueViewModel.lights.value

        // For Test, ONLY target the explicit individual lights selected in the LightSelection UI.
        // The user expects a preview: include selected lights even if they're currently OFF so we can
        // turn them on for the preview. We'll restore original states afterwards.
        var affected = computeTestAffectedLights(allLights, settings, requireOn = false)
        // If bridge metadata not yet available (allLights empty), but the user selected specific IDs,
        // synthesize lightweight HueLight entries so Test can still target the requested lights.
        if (affected.isEmpty() && settings.lightIds.isNotEmpty()) {
            affected = settings.lightIds.map { id ->
                allLights.find { it.id == id } ?: HueLight(
                    id = id,
                    name = id,
                    on = false,
                    brightness = settings.brightness.coerceIn(0, 100),
                    supportsColor = true,
                    supportsCt = true,
                    ctMired = null,
                    colorGamut = null
                )
            }
        }

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

        // Provide user feedback about the result of the Test run
        try {
            val msg = "Test finished: ${_successCount} success, ${_failureCount} failed"
            Log.d("HueAutomation", msg)
            try { snackbarHostState.showSnackbar(msg) } catch (_: Exception) {}
        } catch (_: Exception) {}
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
