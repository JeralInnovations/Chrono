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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
    var btEnabled by remember { mutableStateOf(vm.ble.adapter?.isEnabled == true) }
    val enableBt = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { btEnabled = vm.ble.adapter?.isEnabled == true }

    // Scan while this screen is visible; stop when we leave it.
    DisposableEffect(btEnabled) {
        if (btEnabled) vm.ble.startScan()
        onDispose { vm.ble.stopScan() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("CHRONO", style = MaterialTheme.typography.displayLarge, color = Amber)
        Text(
            "BLE CHRONOGRAPH",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
        )
        Spacer(Modifier.height(56.dp))

        when {
            !btEnabled -> {
                Icon(Icons.Filled.Bluetooth, null, tint = TextDim, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Bluetooth is off. Turn it on to find your chronograph.",
                    textAlign = TextAlign.Center,
                    color = TextDim,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
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
                    Text("Searching for your chronograph…", color = TextDim)
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
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
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
    }
}
