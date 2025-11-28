package com.example.companionpulsebreak.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.companionpulsebreak.sync.HueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueConnectScreen(
    hueViewModel: HueViewModel = viewModel(),
    onConnected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected by hueViewModel.isConnected.collectAsState()
    val bridgeIp by hueViewModel.bridgeIp.collectAsState()
    val discovered by hueViewModel.discovered.collectAsState()
    val pairingStatus by hueViewModel.pairingStatus.collectAsState()

    var selectedIp by remember { mutableStateOf<String?>(null) }
    var isDiscovering by remember { mutableStateOf(false) }

    // When discovered list changes, stop the local discovering indicator
    LaunchedEffect(discovered) {
        isDiscovering = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Link,
            contentDescription = "Connect",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Philips Hue bridge", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                isDiscovering = true
                hueViewModel.discoverBridges()
            }) {
                Text("Discover")
            }
            OutlinedButton(onClick = {
                selectedIp = null
            }) {
                Text("Clear")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isDiscovering) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Searching for bridges...")
            }
        } else if (discovered.isEmpty()) {
            Text("No bridges discovered yet. Make sure your phone is on the same network and try Discover.")
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                discovered.forEach { bridge ->
                    val ip = bridge.ip
                    val display = if (!bridge.name.isNullOrEmpty()) "${bridge.name} ($ip)" else ip
                    val isSelected = selectedIp == ip
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedIp = ip },
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(display, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            if (isSelected) Text("Selected", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (selectedIp != null) {
            Button(onClick = { hueViewModel.pairWithBridge(selectedIp!!) }) {
                Text("Pair with $selectedIp")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        pairingStatus?.let { status ->
            when (status) {
                "link_button_not_pressed" -> Text("Press the link button on the bridge, then tap Pair. Retrying automatically.")
                "starting" -> Text("Starting pairing...")
                "paired" -> {
                    Text("Paired successfully")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onConnected) { Text("Continue to lights") }
                }
                "failed" -> Text("Pairing failed. Try again.")
                else -> if (status.startsWith("error")) Text(status) else Text("Status: $status")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        if (isConnected) {
            Text("Already connected to: ${bridgeIp ?: "unknown"}")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onConnected) { Text("Go to controls") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueControlScreen(
    hueViewModel: HueViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bridgeIp by hueViewModel.bridgeIp.collectAsState()
    val hueUser by hueViewModel.hueUsername.collectAsState()
    val groups by hueViewModel.groups.collectAsState()
    val lights by hueViewModel.lights.collectAsState()

    // Load current state when this screen appears
    LaunchedEffect(bridgeIp, hueUser) {
        if (!bridgeIp.isNullOrEmpty() && !hueUser.isNullOrEmpty()) {
            hueViewModel.refreshHueState()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TopAppBar(
            title = { Text("Hue Controls") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Lightbulb, contentDescription = "Back")
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text("Bridge: ${bridgeIp ?: "not set"}")
        Text("User: ${hueUser ?: "not set"}")

        Spacer(modifier = Modifier.height(16.dp))

        if (bridgeIp == null || hueUser == null) {
            Text("No Hue connection. Go back and pair with a bridge first.")
            return@Column
        }

        // Scrollable content
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Rooms / Zones
            if (groups.isNotEmpty()) {
                item {
                    Text("Rooms & Zones", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                }

                items(groups.size) { index ->
                    val group = groups[index]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${group.name} (${group.type})")
                            Spacer(Modifier.height(4.dp))
                            val value = (group.brightness ?: 100).toFloat()
                            Text("Brightness: ${value.toInt()}%")
                            Slider(
                                value = value,
                                onValueChange = {
                                    hueViewModel.setGroupBrightness(group.id, it.toInt())
                                },
                                valueRange = 0f..100f
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            // Individual lights
            if (lights.isNotEmpty()) {
                item {
                    Text("Lights", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                }

                items(lights.size) { index ->
                    val light = lights[index]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(light.name)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Brightness: ${light.brightness}%")
                                Spacer(Modifier.weight(1f))
                                // Switch to show on/off and allow toggling
                                val isOn = light.on
                                Switch(
                                    checked = isOn,
                                    onCheckedChange = { checked ->
                                        hueViewModel.setLightOn(light.id, checked)
                                    }
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // Slider adjusts brightness and automatically turns the light on when changed
                            Slider(
                                value = light.brightness.toFloat(),
                                onValueChange = { newVal ->
                                    // If user increases brightness from 0, ensure light turns on
                                    if (newVal > 0 && !light.on) {
                                        hueViewModel.setLightOn(light.id, true)
                                    }
                                    hueViewModel.setLightBrightness(light.id, newVal.toInt())
                                },
                                valueRange = 0f..100f
                            )
                        }
                    }
                }
            }
        }
    }
}
