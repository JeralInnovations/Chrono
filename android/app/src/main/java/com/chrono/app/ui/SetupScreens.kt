@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chrono.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chrono.app.ChronoViewModel
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.Proto
import com.chrono.app.data.DistanceUnit
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.Good
import com.chrono.app.ui.theme.TextDim

/**
 * Shared "plug in and test a sensor" pane — used by the setup wizard and
 * by the retest dialog on the dashboard.
 */
@Composable
fun VerifyPane(
    sensor: Int,
    deviceState: Int,
    modifier: Modifier = Modifier,
    verifiedOverride: Boolean = false,
) {
    val listening = deviceState == if (sensor == 1) Proto.ST_VERIFY1 else Proto.ST_VERIFY2
    val verified = verifiedOverride ||
        deviceState == if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK
    val role = if (sensor == 1) "START" else "STOP"

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "SENSOR $sensor — $role",
            style = MaterialTheme.typography.labelLarge,
            color = Amber,
        )
        Spacer(Modifier.height(20.dp))

        TerminalGraphic(pulsing = listening, verified = verified)
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Text("+", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium)
            Text("–", color = TextDim, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(28.dp))

        if (verified) {
            Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "Sensor $sensor detected!",
                style = MaterialTheme.typography.headlineMedium,
                color = Good,
            )
        } else {
            Text(
                "1.  Press the spring clips and insert the two leads of the " +
                    "$role sensor into port $sensor.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "2.  Now trigger the sensor once to test it.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Amber,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(10.dp))
                Text("Listening on input $sensor…", color = TextDim)
            }
        }
    }
}

/** Step 1 of the wizard: measure both ports with nothing plugged in. */
@Composable
fun BaselineScreen(vm: ChronoViewModel, connState: ConnState) {
    val b1 = vm.calData["b1"]
    val b2 = vm.calData["b2"]

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("SETUP  1/4", style = MaterialTheme.typography.labelSmall, color = TextDim)
        Spacer(Modifier.height(24.dp))

        if (connState == ConnState.RECONNECTING) {
            ReconnectingBanner()
            Spacer(Modifier.height(16.dp))
        }

        Text("Port baseline", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "Leave both sensor ports EMPTY — nothing plugged in. The device " +
                "measures each bare port so that the sensor and cable can be " +
                "measured separately in the next steps.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        if (vm.calRunning) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text("Measuring ports…", color = TextDim)
        } else {
            Button(onClick = { vm.startBaselineCal() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (b1 == null && b2 == null) "Run baseline" else "Run baseline again")
            }
        }
        Spacer(Modifier.height(20.dp))

        CalRow("Port 1", vm.calData["b1"])
        Spacer(Modifier.height(8.dp))
        CalRow("Port 2", vm.calData["b2"])

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { vm.continueToSensor1() },
            enabled = b1 != null && b2 != null && !vm.calRunning,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Continue to sensor 1") }
    }
}

@Composable
private fun CalRow(label: String, entry: com.chrono.app.CalEntry?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (entry == null) {
            Text("—", color = TextDim)
        } else {
            Text(
                "%.2f µs  ·  σ %d ns  ·  n=%d".format(
                    entry.medianNs / 1000.0, entry.stddevNs, entry.samples
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.status == 0) TextDim else Amber,
            )
        }
    }
}

@Composable
fun SensorSetupScreen(
    vm: ChronoViewModel,
    sensor: Int,
    deviceState: Int,
    connState: ConnState,
    onContinue: () -> Unit,
) {
    // Three explicit, user-driven steps:
    //   attach   — plug the sensor in (nothing armed)
    //   measure  — capacitance check vs the bare-port baseline
    //   tap      — impact/voltage-rise test (a separate check)
    var step by remember(sensor) { mutableStateOf("attach") }
    var wasVerified by remember(sensor) { mutableStateOf(false) }
    if (deviceState == (if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK)) {
        wasVerified = true
    }

    val loadNs = vm.channelLoadNs(sensor)
    val role = if (sensor == 1) "START" else "STOP"

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("SETUP  ${sensor + 1}/4", style = MaterialTheme.typography.labelSmall, color = TextDim)
        Spacer(Modifier.height(8.dp))
        Text(
            "SENSOR $sensor — $role",
            style = MaterialTheme.typography.labelLarge,
            color = Amber,
        )
        Spacer(Modifier.height(16.dp))

        if (connState == ConnState.RECONNECTING) {
            ReconnectingBanner()
            Spacer(Modifier.height(16.dp))
        }

        when (step) {
            "attach" -> {
                TerminalGraphic(pulsing = false, verified = false)
                Spacer(Modifier.height(24.dp))
                Text(
                    "Press the spring clips and insert the $role sensor leads into " +
                        "port $sensor. Nothing is armed — take your time.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { vm.startLoadedCal(sensor); step = "measure" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) {
                    Text(
                        "Sensor plugged in — check capacitance",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            "measure" -> {
                Text(
                    "Capacitance check",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Measuring the port with the sensor attached and comparing to " +
                        "the empty-port baseline. The wire and sensor should add capacitance.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                if (vm.calRunning) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Checking capacitance…", color = TextDim)
                } else when {
                    loadNs == null -> Text(
                        "Couldn't measure — check the connection to the device.",
                        color = Amber, textAlign = TextAlign.Center,
                    )
                    loadNs > 250 -> {
                        Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Sensor detected: +%.2f µs over baseline (≈ %d pF added).".format(
                                loadNs / 1000.0, (loadNs / 12.0).toInt()
                            ),
                            color = Good,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> Text(
                        "No capacitance increase over the empty-port baseline — the " +
                            "sensor doesn't look attached. Check the clips and re-measure.",
                        color = Amber,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.startLoadedCal(sensor) },
                        enabled = !vm.calRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text("Re-measure") }
                    Spacer(Modifier.size(12.dp))
                    Button(
                        onClick = { vm.beginTapTest(sensor); step = "tap" },
                        enabled = !vm.calRunning && loadNs != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Tap test →") }
                }
            }

            else -> {
                Text(
                    "Tap test — impact detection",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Firmly tap the sensor once to confirm it produces a voltage rise " +
                        "on impact.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                VerifyPane(sensor = sensor, deviceState = deviceState, verifiedOverride = wasVerified)
                if (vm.isSimulation && !wasVerified) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { vm.simulateTap() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Simulate sensor tap") }
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onContinue,
                    enabled = wasVerified,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { Text(if (sensor == 1) "Continue to sensor 2" else "Continue") }
            }
        }
    }
}

@Composable
fun DistanceScreen(vm: ChronoViewModel) {
    var text by remember {
        mutableStateOf(if (vm.distanceValue > 0) trimZeros(vm.distanceInUnit()) else "")
    }
    var unit by remember { mutableStateOf(vm.distanceUnit) }
    var menuOpen by remember { mutableStateOf(false) }
    val value = text.replace(',', '.').toDoubleOrNull()

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("SETUP  4/4", style = MaterialTheme.typography.labelSmall, color = TextDim)
        Spacer(Modifier.height(48.dp))
        Text("Sensor spacing", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "Measure the exact distance between the START and STOP sensors. " +
                "Velocity is calculated from this value, so measure carefully.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                label = { Text("Distance") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.size(12.dp))
            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                TextField(
                    value = unit.label,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor().size(width = 96.dp, height = 56.dp),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DistanceUnit.entries.forEach { u ->
                        DropdownMenuItem(
                            text = { Text(u.label) },
                            onClick = { unit = u; menuOpen = false },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { vm.saveDistance(value ?: 0.0, unit) },
            enabled = value != null && value > 0,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Save distance") }
    }
}

fun trimZeros(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.4f".format(v).trimEnd('0').trimEnd('.')
