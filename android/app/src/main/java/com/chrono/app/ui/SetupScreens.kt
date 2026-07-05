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
) {
    val listening = deviceState == if (sensor == 1) Proto.ST_VERIFY1 else Proto.ST_VERIFY2
    val verified = deviceState == if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK
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

@Composable
fun SensorSetupScreen(
    vm: ChronoViewModel,
    sensor: Int,
    deviceState: Int,
    connState: ConnState,
    onContinue: () -> Unit,
) {
    val verified = deviceState == if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "SETUP  ${sensor}/3",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
        )
        Spacer(Modifier.height(24.dp))

        if (connState == ConnState.RECONNECTING) {
            ReconnectingBanner()
            Spacer(Modifier.height(16.dp))
        }

        VerifyPane(sensor = sensor, deviceState = deviceState)

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onContinue,
            enabled = verified,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(if (sensor == 1) "Continue to sensor 2" else "Continue")
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
        Text("SETUP  3/3", style = MaterialTheme.typography.labelSmall, color = TextDim)
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
