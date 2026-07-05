package com.chrono.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chrono.app.ChronoViewModel
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.DeviceStatus
import com.chrono.app.ble.Proto
import com.chrono.app.data.TestResult
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.Bad
import com.chrono.app.ui.theme.Good
import com.chrono.app.ui.theme.Teal
import com.chrono.app.ui.theme.TextDim
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReconnectingBanner() {
    val t = rememberInfiniteTransition(label = "recon")
    val a by t.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "reconAlpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bad.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.BluetoothDisabled, null,
            tint = Bad, modifier = Modifier.size(18.dp).alpha(a),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            "Connection lost — reconnecting… results are safe on the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = Bad,
        )
    }
}

@Composable
fun DashboardScreen(vm: ChronoViewModel, connState: ConnState, deviceStatus: DeviceStatus?) {
    val state = deviceStatus?.state ?: -1
    val armed = state == Proto.ST_ARMED
    val running = state == Proto.ST_RUNNING
    var editing by remember { mutableStateOf<TestResult?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TopBar(vm, connState, deviceStatus) }

        if (connState == ConnState.RECONNECTING) {
            item { ReconnectingBanner() }
        }

        item { SensorsCard(vm, enabled = connState == ConnState.CONNECTED && !armed && !running) }

        item { NextTestCard(vm, armed = armed || running) }

        item {
            ArmButton(
                armed = armed,
                running = running,
                connected = connState == ConnState.CONNECTED || connState == ConnState.RECONNECTING,
                onArm = { vm.arm() },
                onDisarm = { vm.disarm() },
            )
        }

        if (vm.results.isNotEmpty()) {
            item {
                Text(
                    "RESULTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
            items(vm.results, key = { it.uid }) { r ->
                ResultCard(r, latest = r == vm.results.firstOrNull(), onEdit = { editing = r })
            }
        }
    }

    // Retest overlay
    vm.retestSensor?.let { sensor ->
        val verified = (deviceStatus?.state ?: -1) ==
            if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK
        AlertDialog(
            onDismissRequest = { vm.finishRetest() },
            confirmButton = {
                TextButton(onClick = { vm.finishRetest() }) {
                    Text(if (verified) "Done" else "Cancel")
                }
            },
            text = {
                VerifyPane(
                    sensor = sensor,
                    deviceState = deviceStatus?.state ?: -1,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    editing?.let { r ->
        EditResultDialog(
            result = r,
            onDismiss = { editing = null },
            onSave = { label, epochMillis ->
                vm.updateResult(r.uid, label = label, epochMillis = epochMillis)
                editing = null
            },
            onDelete = {
                vm.deleteResult(r.uid)
                editing = null
            },
        )
    }
}

@Composable
private fun TopBar(vm: ChronoViewModel, connState: ConnState, deviceStatus: DeviceStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("CHRONO", style = MaterialTheme.typography.headlineMedium, color = Amber)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint, label) = when (connState) {
                    ConnState.CONNECTED ->
                        Triple(Icons.Filled.BluetoothConnected, Good, "Connected")
                    ConnState.RECONNECTING ->
                        Triple(Icons.Filled.BluetoothDisabled, Bad, "Reconnecting")
                    else -> Triple(Icons.Filled.BluetoothDisabled, TextDim, "Disconnected")
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(5.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
                Spacer(Modifier.size(14.dp))
                val timeOk = deviceStatus?.timeValid == true
                Icon(
                    Icons.Filled.AccessTime, null,
                    tint = if (timeOk) Teal else TextDim,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { vm.syncTime() },
                )
                Spacer(Modifier.size(5.dp))
                Text(
                    if (timeOk) "Clock synced" else "Clock not set — tap to sync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (timeOk) Teal else TextDim,
                    modifier = Modifier.clickable { vm.syncTime() },
                )
            }
        }
        TextButton(onClick = { vm.disconnect() }) { Text("Disconnect", color = TextDim) }
    }
}

@Composable
private fun SensorsCard(vm: ChronoViewModel, enabled: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sensors", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.startRetest(1) }, enabled = enabled) { Text("Retest 1") }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { vm.startRetest(2) }, enabled = enabled) { Text("Retest 2") }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Straighten, null, tint = Teal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    "Spacing:  ${trimZeros(vm.distanceInUnit())} ${vm.distanceUnit.label}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = { vm.changeDistance() }, enabled = enabled) { Text("Change") }
            }
        }
    }
}

@Composable
private fun NextTestCard(vm: ChronoViewModel, armed: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Test label", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Applied to the next result. You can also edit it afterwards.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextDim,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = vm.pendingLabel,
                onValueChange = { vm.pendingLabel = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. .22 pellet, spring #3", color = TextDim) },
                singleLine = true,
                enabled = true,
            )
        }
    }
}

@Composable
private fun ArmButton(
    armed: Boolean,
    running: Boolean,
    connected: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "arm")
    val pulse by t.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "armPulse",
    )

    when {
        running -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Teal.copy(alpha = 0.18f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "TIMING…  shot in flight",
                    style = MaterialTheme.typography.titleMedium,
                    color = Teal,
                    modifier = Modifier.alpha(pulse),
                )
            }
        }
        armed -> {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(Amber.copy(alpha = 0.16f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "STANDBY — WAITING FOR SHOT",
                            style = MaterialTheme.typography.titleMedium,
                            color = Amber,
                            modifier = Modifier.alpha(pulse),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "You can walk away; the result uploads when you're back in range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextDim,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onDisarm, modifier = Modifier.fillMaxWidth()) {
                    Text("Disarm")
                }
            }
        }
        else -> {
            Button(
                onClick = onArm,
                enabled = connected,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text("ARM  —  TEST STANDBY", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ResultCard(r: TestResult, latest: Boolean, onEdit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (latest) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.label.ifBlank { "Untitled test" },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (r.label.isBlank()) TextDim else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, "Edit", tint = TextDim, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.1f".format(r.feetPerSecond),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 40.sp,
                    color = Amber,
                )
                Spacer(Modifier.size(6.dp))
                Text("ft/s", color = TextDim, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.size(18.dp))
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        "%.2f m/s".format(r.metersPerSecond),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                    )
                    Text(
                        "%.3f ms split".format(r.splitUs / 1000.0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val date = r.formattedDate()
            Text(
                date ?: "No date — tap ✎ to set one",
                style = MaterialTheme.typography.bodyMedium,
                // grayed out when the device had no synced clock for this test
                color = if (date != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                else TextDim.copy(alpha = 0.55f),
            )
        }
    }
}

private val EDIT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
private fun EditResultDialog(
    result: TestResult,
    onDismiss: () -> Unit,
    onSave: (label: String, epochMillis: Long?) -> Unit,
    onDelete: () -> Unit,
) {
    var label by remember { mutableStateOf(result.label) }
    var dateText by remember {
        mutableStateOf(
            result.epochMillis?.let {
                EDIT_DATE_FORMAT.format(
                    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault())
                )
            } ?: ""
        )
    }
    val parsedDate: Long? = dateText.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching {
            LocalDateTime.parse(it, EDIT_DATE_FORMAT)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
    val dateError = dateText.isNotBlank() && parsedDate == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit test") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Date & time") },
                    placeholder = { Text("yyyy-MM-dd HH:mm", color = TextDim) },
                    singleLine = true,
                    isError = dateError,
                    supportingText = {
                        if (dateError) Text("Use format 2026-07-05 14:30")
                        else if (result.epochMillis == null && dateText.isBlank())
                            Text("Device clock wasn't synced for this test", color = TextDim)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Delete this result", color = Bad)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label.trim(), parsedDate) },
                enabled = !dateError,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
