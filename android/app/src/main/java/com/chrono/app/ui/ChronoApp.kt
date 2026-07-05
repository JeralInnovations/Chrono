package com.chrono.app.ui

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

    when (vm.screen) {
        Screen.CONNECT -> ConnectScreen(vm, connState)
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
