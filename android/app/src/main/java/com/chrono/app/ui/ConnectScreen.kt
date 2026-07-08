package com.chrono.app.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chrono.app.ChronoViewModel
import com.chrono.app.ble.ConnState
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.TextDim

@Composable
fun ConnectScreen(vm: ChronoViewModel, connState: ConnState) {
    val found by vm.ble.found.collectAsState()
    var btEnabled by remember {
        mutableStateOf(runCatching { vm.ble.adapter?.isEnabled == true }.getOrDefault(false))
    }
    val enableBt = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { btEnabled = runCatching { vm.ble.adapter?.isEnabled == true }.getOrDefault(false) }

    // Scan while this screen is visible; stop when we leave it.
    DisposableEffect(btEnabled) {
        if (btEnabled) vm.ble.startScan()
        onDispose { vm.ble.stopScan() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            "JERAL INNOVATIONS",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
        )
        Spacer(Modifier.height(4.dp))
        Text("CHRONO LOGGER", style = MaterialTheme.typography.displayLarge, color = Amber)
        Text(
            "BLE SHOT LOGGER",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
        )
        Spacer(Modifier.height(56.dp))

        when {
            !btEnabled -> {
                Icon(Icons.Filled.Bluetooth, null, tint = TextDim, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Bluetooth is off. Turn it on to find your logger.",
                    textAlign = TextAlign.Center,
                    color = TextDim,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = {
                    runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                }) {
                    Text("Turn on Bluetooth")
                }
            }

            connState == ConnState.CONNECTING || connState == ConnState.RECONNECTING -> {
                CircularProgressIndicator(color = Amber)
                Spacer(Modifier.height(16.dp))
                Text("Connecting…", color = TextDim)
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BluetoothSearching, null, tint = Amber)
                    Spacer(Modifier.size(10.dp))
                    Text("Searching for your logger...", color = TextDim)
                }
                Spacer(Modifier.height(24.dp))
                if (found.isEmpty()) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Power the device and keep it nearby.\nIt will appear here automatically.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                    )
                } else {
                    // fill=false: take only what the list needs, so the
                    // simulation entry below is never pushed off-screen.
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(found, key = { it.device.address }) { d ->
                            Card(
                                onClick = { vm.ble.connect(d.device) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Bluetooth, null, tint = Amber)
                                    Spacer(Modifier.size(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            d.device.address,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextDim,
                                        )
                                    }
                                    Text("${d.rssi} dBm", color = TextDim,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Always reachable — lets the UI be exercised with no hardware present.
        Spacer(Modifier.weight(1f))
        HorizontalDivider(
            Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedButton(
            onClick = { vm.connectSimulated() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.PlayCircleOutline, null,
                tint = TextDim, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Simulation mode", color = TextDim, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.enterManualMode() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.EditNote, null,
                tint = TextDim, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Manual logging (no chrono)", color = TextDim, maxLines = 1)
        }
    }
}
