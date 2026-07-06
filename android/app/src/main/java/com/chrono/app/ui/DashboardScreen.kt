package com.chrono.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chrono.app.ChronoViewModel
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.DeviceStatus
import com.chrono.app.ble.Proto
import com.chrono.app.data.Exporter
import com.chrono.app.data.TestResult
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.Bad
import com.chrono.app.ui.theme.Good
import com.chrono.app.ui.theme.PanelHigh
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
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TopBar(vm, connState, deviceStatus) }

        if (connState == ConnState.RECONNECTING) {
            item { ReconnectingBanner() }
        }

        if (vm.isSimulation) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Teal.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Simulation mode — no hardware connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Teal,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { vm.simulateSignalLoss() },
                        enabled = connState == ConnState.CONNECTED,
                    ) { Text("Drop signal", color = Teal) }
                }
            }
        }

        item { RigCard(vm, enabled = connState == ConnState.CONNECTED && !armed && !running) }

        item {
            ChannelsCard(
                vm,
                enabled = connState == ConnState.CONNECTED && !armed && !running && !vm.calRunning,
            )
        }

        item { NextTestCard(vm, armed = armed || running) }

        item {
            ArmButton(
                armed = armed,
                running = running,
                connected = connState == ConnState.CONNECTED || connState == ConnState.RECONNECTING,
                sensorsReady = vm.sensor1Ready && vm.sensor2Ready,
                onArm = { vm.arm() },
                onDisarm = { vm.disarm() },
            )
        }

        if (vm.results.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp),
                ) {
                    Text(
                        "RESULTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { Exporter.export(context, vm.results.toList()) }) {
                        Icon(
                            Icons.Filled.Share, null,
                            tint = TextDim, modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Export", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(vm.results, key = { it.uid }) { r ->
                ResultCard(
                    r,
                    latest = r == vm.results.firstOrNull(),
                    ciPercent = vm.ciPercentFor(r),
                    onEdit = { editing = r },
                )
            }
        }
    }

    // Retest overlay
    vm.retestSensor?.let { sensor ->
        val verified = (deviceStatus?.state ?: -1) ==
            if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK
        AlertDialog(
            onDismissRequest = { vm.finishRetest(verified) },
            confirmButton = {
                TextButton(onClick = { vm.finishRetest(verified) }) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CHRONO", style = MaterialTheme.typography.headlineMedium, color = Amber)
                if (vm.isSimulation) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "SIM",
                        style = MaterialTheme.typography.labelSmall,
                        color = Teal,
                        modifier = Modifier
                            .background(Teal.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
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

/**
 * The rig, drawn the way it stands on the bench: sensor 1 up front, the
 * measured gap, sensor 2 behind it. Tap a sensor to retest/replace it; tap
 * the distance to change it. Screens consumed by a shot show torn + amber.
 */
@Composable
private fun RigCard(vm: ChronoViewModel, enabled: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                "SHOT DIRECTION  ▸",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SensorGraphic(1, vm.sensor1Ready, enabled) { vm.startRetest(1) }
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SpanArrow()
                    TextButton(onClick = { vm.changeDistance() }, enabled = enabled) {
                        Text(
                            "${trimZeros(vm.distanceInUnit())} ${vm.distanceUnit.label}",
                            color = Teal,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        "tap to change",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim,
                    )
                }
                SensorGraphic(2, vm.sensor2Ready, enabled) { vm.startRetest(2) }
            }
            if (!vm.sensor1Ready || !vm.sensor2Ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Each shot consumes the screens. Fit new wire, then tap the torn sensor to retest it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Amber,
                )
            }
        }
    }
}

@Composable
private fun SensorGraphic(number: Int, ready: Boolean, enabled: Boolean, onTap: () -> Unit) {
    val frame = if (ready) Good else Amber
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled, onClick = onTap),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp, 80.dp)) {
                val w = size.width
                val h = size.height
                drawRoundRect(color = PanelHigh, cornerRadius = CornerRadius(10f, 10f))
                // screen mesh
                for (i in 1..3) {
                    val x = w * i / 4f
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 6f), Offset(x, h - 6f), 2f)
                    val y = h * i / 4f
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(6f, y), Offset(w - 6f, y), 2f)
                }
                drawRoundRect(
                    color = frame,
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 5f),
                )
                if (!ready) {
                    // jagged tear through a consumed screen
                    val tear = Path().apply {
                        moveTo(w * 0.55f, 0f)
                        lineTo(w * 0.35f, h * 0.30f)
                        lineTo(w * 0.62f, h * 0.46f)
                        lineTo(w * 0.40f, h * 0.72f)
                        lineTo(w * 0.55f, h)
                    }
                    drawPath(tear, Amber, style = Stroke(width = 4f))
                }
            }
            Text(
                "$number",
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                color = frame,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (number == 1) "START" else "STOP",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
        )
        Text(
            if (ready) "Ready" else "Retest",
            style = MaterialTheme.typography.bodyMedium,
            color = frame,
        )
    }
}

/** A |◀——▶| measurement arrow spanning the gap between the two screens. */
@Composable
private fun SpanArrow() {
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val w = size.width
        val cy = size.height / 2f
        drawLine(TextDim, Offset(2f, 3f), Offset(2f, size.height - 3f), 3f)
        drawLine(TextDim, Offset(w - 2f, 3f), Offset(w - 2f, size.height - 3f), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(w - 2f, cy), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(12f, cy - 5f), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(12f, cy + 5f), 3f)
        drawLine(TextDim, Offset(w - 2f, cy), Offset(w - 12f, cy - 5f), 3f)
        drawLine(TextDim, Offset(w - 2f, cy), Offset(w - 12f, cy + 5f), 3f)
    }
}

@Composable
private fun ChannelsCard(vm: ChronoViewModel, enabled: Boolean) {
    val load1 = vm.channelLoadNs(1)
    val load2 = vm.channelLoadNs(2)
    val mismatch = vm.channelMismatchNs()
    val hasBaseline = vm.calData.containsKey("b1") && vm.calData.containsKey("b2")

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Channel calibration",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (vm.calRunning) {
                    CircularProgressIndicator(
                        color = Amber, modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    )
                } else {
                    OutlinedButton(
                        onClick = { vm.recalibrateChannels() },
                        enabled = enabled && hasBaseline,
                    ) { Text("Recheck") }
                }
            }
            Spacer(Modifier.height(10.dp))
            for ((ch, load) in listOf(1 to load1, 2 to load2)) {
                Text(
                    if (load != null)
                        "Port $ch load:  +%.2f µs  (≈ %d pF)".format(load / 1000.0, (load / 12.0).toInt())
                    else "Port $ch load:  — not measured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(6.dp))
            when {
                !hasBaseline -> Text(
                    "No baseline captured — redo setup with empty ports to enable.",
                    style = MaterialTheme.typography.bodyMedium, color = TextDim,
                )
                mismatch == null -> Text(
                    "Plug in and measure both sensors to compare channels.",
                    style = MaterialTheme.typography.bodyMedium, color = TextDim,
                )
                mismatch < 600 -> Text(
                    "Channels matched  (Δ $mismatch ns — timing impact negligible)",
                    style = MaterialTheme.typography.bodyMedium, color = Good,
                )
                else -> Text(
                    "Channel mismatch Δ $mismatch ns (≈ ${(mismatch / 12.0).toInt()} pF) — " +
                        "check that both cables are the same length and type.",
                    style = MaterialTheme.typography.bodyMedium, color = Amber,
                )
            }
            Spacer(Modifier.height(8.dp))
            val hw by vm.ble.hwInfo.collectAsState()
            Text(
                hw?.let {
                    "Hardware rev ${it.hwRev} · fw ${it.fwMajor}.${it.fwMinor} · " +
                        "%.1f ns timer · auto-identified".format(it.tickPs / 1000.0)
                } ?: "Hardware not identified — assuming rev 1 accuracy",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
            TextButton(onClick = { vm.redoSetup() }, enabled = enabled) {
                Text("Redo full setup (new baseline)", style = MaterialTheme.typography.bodyMedium)
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
    sensorsReady: Boolean,
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
            Column {
                Button(
                    onClick = onArm,
                    enabled = connected && sensorsReady,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("ARM  —  TEST STANDBY", style = MaterialTheme.typography.labelLarge)
                }
                if (connected && !sensorsReady) {
                    Text(
                        "Replace and retest both sensors before arming.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: TestResult, latest: Boolean, ciPercent: Double, onEdit: () -> Unit) {
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
                        "%.3f ms split".format(r.splitMillis),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                    )
                    Text(
                        if (ciPercent >= 0.05) "±%.1f%% (95%% CI)".format(ciPercent)
                        else "±<0.1% (95% CI)",
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
