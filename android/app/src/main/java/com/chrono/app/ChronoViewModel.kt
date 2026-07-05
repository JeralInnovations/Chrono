package com.chrono.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrono.app.ble.ChronoBle
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.Proto
import com.chrono.app.ble.RawResult
import com.chrono.app.data.DistanceUnit
import com.chrono.app.data.ResultStore
import com.chrono.app.data.TestResult
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { CONNECT, SENSOR1, SENSOR2, DISTANCE, DASHBOARD }

class ChronoViewModel(app: Application) : AndroidViewModel(app) {

    val ble = ChronoBle(app)
    private val store = ResultStore(app)
    private val prefs = app.getSharedPreferences("chrono", Application.MODE_PRIVATE)

    var screen by mutableStateOf(Screen.CONNECT)
        private set

    val results = mutableStateListOf<TestResult>()

    /** Label applied to the next test; can also be edited on a result afterwards. */
    var pendingLabel by mutableStateOf("")

    // The distance is kept exactly as the user typed it, in their chosen unit.
    // Converting to meters and back would turn "12 in" into 11.999… on screen.
    var distanceValue by mutableStateOf(prefs.getFloat("distanceValue", 0f).toDouble())
        private set
    var distanceUnit by mutableStateOf(
        runCatching { DistanceUnit.valueOf(prefs.getString("unit", "INCHES")!!) }
            .getOrDefault(DistanceUnit.INCHES)
    )
        private set

    /** Meters, derived only for the velocity math. */
    val distanceM: Double get() = distanceValue * distanceUnit.toMeters

    /** Non-null while the user is re-testing a sensor from the dashboard. */
    var retestSensor by mutableStateOf<Int?>(null)
        private set

    private val setupDone: Boolean get() = prefs.getBoolean("setupDone", false)

    init {
        results.addAll(store.load())
        viewModelScope.launch { ble.results.collect { onRawResult(it) } }
        viewModelScope.launch {
            ble.connState.collect { cs ->
                when (cs) {
                    ConnState.CONNECTED -> if (screen == Screen.CONNECT) {
                        if (setupDone) {
                            screen = Screen.DASHBOARD
                        } else {
                            screen = Screen.SENSOR1
                            ble.sendCommand(Proto.CMD_VERIFY1)
                        }
                    }
                    ConnState.DISCONNECTED -> {
                        // Only a deliberate disconnect lands here; unexpected drops
                        // go to RECONNECTING and keep the current screen.
                        screen = Screen.CONNECT
                        retestSensor = null
                    }
                    else -> Unit
                }
            }
        }
    }

    // -------------------------------------------------------------- results

    private fun onRawResult(r: RawResult) {
        // FETCH after a reconnect can re-deliver a result we already stored.
        val duplicate = results.any { it.deviceResultId == r.id && it.splitNs == r.splitNs }
        if (!duplicate) {
            results.add(
                0,
                TestResult(
                    uid = UUID.randomUUID().toString(),
                    deviceResultId = r.id,
                    splitNs = r.splitNs,
                    distanceM = distanceM,
                    label = pendingLabel.trim(),
                    epochMillis = if (r.epochSec > 0) r.epochSec * 1000L else null,
                )
            )
            persist()
        }
        // Tell the device it can forget this result now that it's stored on the phone.
        ble.sendCommand(Proto.CMD_ACK, r.id)
    }

    fun updateResult(uid: String, label: String? = null, epochMillis: Long? = null) {
        val idx = results.indexOfFirst { it.uid == uid }
        if (idx < 0) return
        val r = results[idx]
        results[idx] = r.copy(
            label = label ?: r.label,
            epochMillis = epochMillis ?: r.epochMillis,
        )
        persist()
    }

    fun deleteResult(uid: String) {
        results.removeAll { it.uid == uid }
        persist()
    }

    private fun persist() = store.save(results.toList())

    // ---------------------------------------------------------- setup flow

    fun continueToSensor2() {
        screen = Screen.SENSOR2
        ble.sendCommand(Proto.CMD_VERIFY2)
    }

    fun continueToDistance() {
        ble.sendCommand(Proto.CMD_CANCEL)
        screen = Screen.DISTANCE
    }

    fun saveDistance(value: Double, unit: DistanceUnit) {
        distanceValue = value
        distanceUnit = unit
        prefs.edit()
            .putFloat("distanceValue", value.toFloat())
            .putString("unit", unit.name)
            .putBoolean("setupDone", true)
            .apply()
        screen = Screen.DASHBOARD
    }

    /** Distance expressed in the user's chosen unit, for display/editing. */
    fun distanceInUnit(): Double = distanceValue

    // ------------------------------------------------------------ dashboard

    fun startRetest(sensor: Int) {
        retestSensor = sensor
        ble.sendCommand(if (sensor == 1) Proto.CMD_VERIFY1 else Proto.CMD_VERIFY2)
    }

    fun finishRetest() {
        retestSensor = null
        ble.sendCommand(Proto.CMD_CANCEL)
    }

    fun arm() = ble.sendCommand(Proto.CMD_ARM)
    fun disarm() = ble.sendCommand(Proto.CMD_DISARM)
    fun syncTime() = ble.syncTime()

    fun changeDistance() {
        screen = Screen.DISTANCE
    }

    fun disconnect() = ble.disconnect()

    override fun onCleared() {
        ble.disconnect()
    }
}
