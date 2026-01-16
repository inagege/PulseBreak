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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.companionpulsebreak.sync.HueViewModel
import com.example.companionpulsebreak.sync.CompanionSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueConnectScreen(
    hueViewModel: HueViewModel = viewModel(),
    onConnected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settingsViewModel: CompanionSettingsViewModel = viewModel()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

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
        Text("Philips Hue bridge", style = MaterialTheme.typography.titleLarge, color = if (settings.isDarkMode) Color(settings.buttonColor) else Color(settings.buttonTextColor))
        Spacer(modifier = Modifier.height(8.dp))

        var manualIp by remember { mutableStateOf("") }

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

        Spacer(modifier = Modifier.height(8.dp))

        // Manual IP entry (useful when discovery fails) - minimal UI
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text("Manual IP") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (manualIp.isNotBlank()) {
                    // call pairing directly with the provided IP
                    hueViewModel.pairWithBridge(manualIp.trim())
                    selectedIp = manualIp.trim()
                }
            }) {
                Text("Use IP")
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnected) { Text("Go to controls") }
                OutlinedButton(onClick = { hueViewModel.disconnect() }) { Text("Forget bridge") }
            }
        }
    }
}