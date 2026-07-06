package com.chrono.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chrono.app.ChronoViewModel
import com.chrono.app.Screen

/** Top-level router. Navigation is a simple state machine held by the ViewModel. */
@Composable
fun ChronoApp(vm: ChronoViewModel) {
    val connState by vm.ble.connState.collectAsState()
    val deviceStatus by vm.ble.status.collectAsState()

    // Once per app launch: keep logging into the last test folder, or start new?
    if (vm.sessionPrompt) {
        AlertDialog(
            onDismissRequest = { vm.chooseContinueSession() },
            title = { Text("Test folder") },
            text = {
                Text(
                    "Keep logging into \"${vm.sessionName}\" or start a new " +
                        "test folder for this session?"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.chooseNewSession() }) { Text("New folder") }
            },
            dismissButton = {
                TextButton(onClick = { vm.chooseContinueSession() }) { Text("Continue") }
            },
        )
    }

    when (vm.screen) {
        Screen.CONNECT -> ConnectScreen(vm, connState)
        Screen.BASELINE -> BaselineScreen(vm, connState)
        Screen.SENSOR1 -> SensorSetupScreen(
            vm = vm,
            sensor = 1,
            deviceState = deviceStatus?.state ?: -1,
            connState = connState,
            onContinue = { vm.continueToSensor2() },
        )
        Screen.SENSOR2 -> SensorSetupScreen(
            vm = vm,
            sensor = 2,
            deviceState = deviceStatus?.state ?: -1,
            connState = connState,
            onContinue = { vm.continueToDistance() },
        )
        Screen.DISTANCE -> DistanceScreen(vm)
        Screen.DASHBOARD -> DashboardScreen(vm, connState, deviceStatus)
    }
}
