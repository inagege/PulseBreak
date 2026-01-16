// filepath: companion/src/main/java/com/example/companionpulsebreak/screens/HueLightSelectionScreens.kt
package com.example.companionpulsebreak.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import android.util.Log
import com.example.companionpulsebreak.sync.HueViewModel
import com.example.companionpulsebreak.sync.HueLight
import com.example.companionpulsebreak.sync.HueGroup
import com.example.companionpulsebreak.sync.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightsSelectionScreen(
    settingsManager: SettingsManager,
    hueViewModel: HueViewModel,
    initial: com.example.commonlibrary.HueAutomationData,
    onBack: () -> Unit,
    onSelectionChange: (selectedLights: Set<String>, selectedGroups: Set<String>) -> Unit,
    surfaceColor: androidx.compose.ui.graphics.Color,
    onSurfaceColor: androidx.compose.ui.graphics.Color,
    primaryColor: androidx.compose.ui.graphics.Color,
    onPrimaryColor: androidx.compose.ui.graphics.Color,
    actionTextColor: androidx.compose.ui.graphics.Color,
    shadowElevation: Dp
) {
    val lights by hueViewModel.lights.collectAsState()
    val groups by hueViewModel.groups.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var selectedLights by remember { mutableStateOf(initial.lightIds.toSet()) }
    var selectedGroups by remember { mutableStateOf(initial.groupIds.toSet()) }

    LaunchedEffect(settingsManager) {
        try {
            // Only load persisted settings if the incoming draft is empty. If the caller passed a
            // non-empty `initial`, prefer that draft so navigation from Home preserves user edits.
            if (initial.lightIds.isNotEmpty() || initial.groupIds.isNotEmpty()) {
                // inform parent of current draft so Home keeps in sync
                onSelectionChange(selectedLights, selectedGroups)
                return@LaunchedEffect
            }

            val sd = settingsManager.loadInitialSettings()
            val persisted = sd.hueAutomation
            selectedLights = persisted.lightIds.toSet()
            selectedGroups = persisted.groupIds.toSet()
            onSelectionChange(selectedLights, selectedGroups)
        } catch (_: Exception) {
        }
    }

    var currentGroupId by remember { mutableStateOf<String?>(null) }
    val currentGroup = groups.find { it.id == currentGroupId }

    // When returning from a group's detail screen, ensure any explicit group selections
    // that no longer have selected children are cleaned up immediately.
    LaunchedEffect(currentGroupId) {
        if (currentGroupId == null) {
            try {
                val toRemove = selectedGroups.filter { gid ->
                    val g = groups.find { it.id == gid }
                    if (g == null) return@filter true
                    !g.lightIds.any { lid -> selectedLights.contains(lid) }
                }.toSet()
                if (toRemove.isNotEmpty()) {
                    selectedGroups = selectedGroups - toRemove
                    Log.d("HueAutomation", "Cleanup on exit group: removed empty groups $toRemove -> selectedGroups=$selectedGroups selectedLights=$selectedLights")
                    onSelectionChange(selectedLights, selectedGroups)
                } else {
                    Log.d("HueAutomation", "Cleanup on exit group: nothing to remove. selectedGroups=$selectedGroups selectedLights=${selectedLights.size}")
                }
            } catch (e: Exception) { Log.w("HueAutomation", "Cleanup on exit group failed: ${e.message}") }
        }
    }

    // If the user manually deselects all lights belonging to an explicitly selected group
    // (for example by entering a parent group and toggling out all subgroup lights), remove
    // that group's explicit selection so the toggle no longer appears checked.
    LaunchedEffect(selectedLights, groups) {
        try {
            val toRemove = selectedGroups.filter { gid ->
                val g = groups.find { it.id == gid }
                if (g == null) return@filter true
                // If none of this group's lights are currently in selectedLights, remove the group
                !g.lightIds.any { lid -> selectedLights.contains(lid) }
            }.toSet()

            if (toRemove.isNotEmpty()) {
                selectedGroups = selectedGroups - toRemove
                Log.d("HueAutomation", "Auto-cleared empty explicit groups: $toRemove -> selectedGroups=$selectedGroups")
                onSelectionChange(selectedLights, selectedGroups)
            }
        } catch (_: Exception) {}
    }

    when (currentGroupId) {
        null -> {
            // Visual selection should include groups explicitly selected OR groups that contain
            // draft-selected lights OR groups that currently have any member light switched ON
            // (so toggles reflect actual physical state as well as draft state).
            // Visual selection includes groups explicitly selected, groups containing draft-selected lights,
            // and groups that currently contain any light that is physically ON. Compute directly so the
            // UI updates immediately when `lights` changes.
            // Compute which groups should appear checked: any child is in the draft selection.
            // Do NOT consider live bridge state. Selection is driven solely by the user's draft.
            val checkedGroups by remember(selectedLights, groups) {
                derivedStateOf {
                    groups.filter { g ->
                        g.lightIds.any { lid -> selectedLights.contains(lid) }
                    }.map { it.id }.toSet()
                }
            }
            LaunchedEffect(checkedGroups) {
                try { Log.d("HueAutomation", "checkedGroups recomputed: $checkedGroups selectedGroups=$selectedGroups selectedLights=${selectedLights.size}") } catch (_: Exception) {}
            }

            GroupListScreen(
                groups = groups,
                selectedGroups = selectedGroups,
                checkedGroups = checkedGroups,
                onClearGroupChildren = { gid ->
                    val group = groups.find { it.id == gid }
                    if (group != null) {
                        val parentLights = group.lightIds.toSet()

                        // Remove any individually selected lights that belong to this group
                        selectedLights = selectedLights - parentLights

                        // Also clear any explicitly selected subgroup ids whose light sets are fully
                        // contained within this parent's lights (covers nested subgroups).
                        val containedGroupIds = groups.filter { g ->
                            // skip the parent itself
                            g.id != gid && g.lightIds.isNotEmpty() && g.lightIds.all { parentLights.contains(it) }
                        }.map { it.id }.toSet()

                        if (containedGroupIds.isNotEmpty()) {
                            selectedGroups = selectedGroups - containedGroupIds
                        }

                        // Persist centrally from parent (avoid racing writes here)
                        Log.d("HueAutomation","onClearGroupChildren: gid=$gid removedLights=${parentLights.size} containedGroups=$containedGroupIds selectedLights=$selectedLights selectedGroups=$selectedGroups")
                        onSelectionChange(selectedLights, selectedGroups)
                    }
                },
                onToggleGroup = { id, isSelected ->
                    val group = groups.find { it.id == id }
                    if (isSelected) {
                        // immediate local update so checkedGroups recomputes synchronously
                        selectedGroups = selectedGroups + id
                        if (group != null) selectedLights = selectedLights + group.lightIds.toSet()
                    } else {
                        selectedGroups = selectedGroups - id
                        if (group != null) selectedLights = selectedLights - group.lightIds.toSet()
                    }

                    // Persist centrally from parent (avoid racing writes here)
                    onSelectionChange(selectedLights, selectedGroups)

                    // Do NOT change physical light state here. Selection changes only update the draft.
                    Log.i("HueAutomation", "Group selection changed for group=${group?.id}: isSelected=$isSelected (draft updated only)")
                 },
                 onGroupClick = { id -> currentGroupId = id },
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
                     Log.d("HueAutomation","onToggleLight: lightId=$lightId isSelected=$isSelected selectedLights=$selectedLights (draft updated only)")
                     onSelectionChange(selectedLights, selectedGroups)
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
internal fun GroupListScreen(
    groups: List<HueGroup>,
    selectedGroups: Set<String>,
    checkedGroups: Set<String>,
    onClearGroupChildren: (groupId: String) -> Unit,
    onToggleGroup: (groupId: String, isSelected: Boolean) -> Unit,
    onGroupClick: (groupId: String) -> Unit,
    onBack: () -> Unit,
    surfaceColor: androidx.compose.ui.graphics.Color,
    onSurfaceColor: androidx.compose.ui.graphics.Color,
    primaryColor: androidx.compose.ui.graphics.Color,
    onPrimaryColor: androidx.compose.ui.graphics.Color,
    actionTextColor: androidx.compose.ui.graphics.Color,
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
                val isExplicitSelected = selectedGroups.contains(g.id)
                val isChecked = checkedGroups.contains(g.id)

                Log.d("HueAutomation", "GroupListScreen item: id=${g.id} name=${g.name} explicit=$isExplicitSelected checked=$isChecked")

                val toggleHandler: (Boolean) -> Unit = { newState ->
                    if (newState) {
                        onToggleGroup(g.id, true)
                    } else {
                        if (isExplicitSelected) {
                            onToggleGroup(g.id, false)
                        } else {
                            onClearGroupChildren(g.id)
                        }
                    }
                }

                GroupRow(
                    name = g.name,
                    isSelected = isChecked,
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
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
            ) { Text("Back", color = actionTextColor) }
        }
    }
}

@Composable
internal fun GroupRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    surfaceColor: androidx.compose.ui.graphics.Color,
    onSurfaceColor: androidx.compose.ui.graphics.Color,
    primaryColor: androidx.compose.ui.graphics.Color,
    onPrimaryColor: androidx.compose.ui.graphics.Color,
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
internal fun GroupLightsScreen(
    groupName: String,
    lights: List<HueLight>,
    selectedLights: Set<String>,
    onToggleLight: (lightId: String, isSelected: Boolean) -> Unit,
    onBack: () -> Unit,
    surfaceColor: androidx.compose.ui.graphics.Color,
    onSurfaceColor: androidx.compose.ui.graphics.Color,
    primaryColor: androidx.compose.ui.graphics.Color,
    onPrimaryColor: androidx.compose.ui.graphics.Color,
    actionTextColor: androidx.compose.ui.graphics.Color,
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
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = actionTextColor)
            ) { Text("Back", color = actionTextColor) }
        }
    }
}
