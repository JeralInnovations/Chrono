package com.chrono.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Mirrors the protocol defined in firmware/Chronograph/Chronograph.ino */
object Proto {
    val SERVICE: UUID = UUID.fromString("a5c40001-9d95-4e4c-8c5a-c1d6f2a80de1")
    val STATUS: UUID = UUID.fromString("a5c40002-9d95-4e4c-8c5a-c1d6f2a80de1")
    val CONTROL: UUID = UUID.fromString("a5c40003-9d95-4e4c-8c5a-c1d6f2a80de1")
    val RESULT: UUID = UUID.fromString("a5c40004-9d95-4e4c-8c5a-c1d6f2a80de1")
    val TIME: UUID = UUID.fromString("a5c40005-9d95-4e4c-8c5a-c1d6f2a80de1")
    val CAL: UUID = UUID.fromString("a5c40006-9d95-4e4c-8c5a-c1d6f2a80de1")
    val INFO: UUID = UUID.fromString("a5c40007-9d95-4e4c-8c5a-c1d6f2a80de1")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_VERIFY1: Byte = 1
    const val CMD_VERIFY2: Byte = 2
    const val CMD_ARM: Byte = 3
    const val CMD_DISARM: Byte = 4
    const val CMD_ACK: Byte = 5
    const val CMD_CANCEL: Byte = 6
    const val CMD_FETCH: Byte = 7
    const val CMD_CALIBRATE: Byte = 8

    const val ST_IDLE = 0
    const val ST_VERIFY1 = 1
    const val ST_VERIFY1_OK = 2
    const val ST_VERIFY2 = 3
    const val ST_VERIFY2_OK = 4
    const val ST_ARMED = 5
    const val ST_RUNNING = 6
    const val ST_CALIBRATING = 7
}

data class DeviceStatus(val state: Int, val pendingCount: Int, val timeValid: Boolean)

data class RawResult(val id: Int, val splitNs: Long, val epochSec: Long)

/**
 * Self-reported identity and timing spec of the connected hardware. The app
 * derives its accuracy model from these numbers, so a future revision that
 * reports a finer tick or lower jitter automatically shows tighter confidence.
 */
data class HwInfo(
    val hwRev: Int,
    val fwMajor: Int,
    val fwMinor: Int,
    val tickPs: Long,         // timer tick period, picoseconds
    val clockPpm: Int,        // crystal tolerance
    val edgeJitterNs: Int,    // per-edge front-end uncertainty
) {
    companion object {
        /** Assumed when the device predates the INFO characteristic. */
        val DEFAULT = HwInfo(1, 1, 0, 62_500, 30, 300)
    }
}

/** One calibration sweep summary from the device (all times in ns). */
data class CalReading(
    val channel: Int,
    val status: Int,      // 0 ok, 1 some timeouts, 2 failed
    val samples: Int,
    val medianNs: Long,
    val meanNs: Long,
    val stddevNs: Long,
    val minNs: Long,
)

data class FoundDevice(val device: BluetoothDevice, val name: String, val rssi: Int)

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Handles all BluetoothGatt plumbing: scanning, connecting, a serialized
 * operation queue (BLE allows only one GATT operation in flight), and
 * automatic reconnection when the link drops mid-test.
 */
@SuppressLint("MissingPermission") // permissions are requested before any screen can reach this class
class ChronoBle(private val context: Context) {

    val connState = MutableStateFlow(ConnState.DISCONNECTED)
    val status = MutableStateFlow<DeviceStatus?>(null)
    val results = MutableSharedFlow<RawResult>(extraBufferCapacity = 32)
    val cal = MutableSharedFlow<CalReading>(extraBufferCapacity = 8)
    val hwInfo = MutableStateFlow<HwInfo?>(null)
    val found = MutableStateFlow<List<FoundDevice>>(emptyList())

    val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var userDisconnected = false
    private val prefs = context.getSharedPreferences("chrono_ble", Context.MODE_PRIVATE)

    private var chControl: BluetoothGattCharacteristic? = null
    private var chStatus: BluetoothGattCharacteristic? = null
    private var chResult: BluetoothGattCharacteristic? = null
    private var chTime: BluetoothGattCharacteristic? = null
    private var chCal: BluetoothGattCharacteristic? = null
    private var chInfo: BluetoothGattCharacteristic? = null

    // ------------------------------------------------------------ scanning

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Chrono"
            val rest = found.value.filter { it.device.address != result.device.address }
            found.value = (rest + FoundDevice(result.device, name, result.rssi))
                .sortedByDescending { it.rssi }
        }
    }

    fun startScan() {
        val ad = adapter ?: return
        if (!ad.isEnabled) return
        found.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { ad.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCb) }
        connState.value = ConnState.SCANNING
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        if (connState.value == ConnState.SCANNING) connState.value = ConnState.DISCONNECTED
    }

    // ---------------------------------------------------------- connection

    fun connect(device: BluetoothDevice) {
        stopScan()
        prefs.edit().putString("lastDeviceAddress", device.address).apply()
        userDisconnected = false
        connState.value = ConnState.CONNECTING
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun reconnectLast(): Boolean {
        val address = prefs.getString("lastDeviceAddress", null) ?: return false
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        stopScan()
        userDisconnected = false
        connState.value = ConnState.CONNECTING
        gatt = device.connectGatt(context, true, gattCb, BluetoothDevice.TRANSPORT_LE)
        return true
    }

    fun disconnect() {
        if (isSimulation) {
            simJob?.cancel()
            isSimulation = false
            status.value = null
            hwInfo.value = null
            connState.value = ConnState.DISCONNECTED
            return
        }
        userDisconnected = true
        synchronized(opLock) { opQueue.clear(); opInFlight = false }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        status.value = null
        hwInfo.value = null
        connState.value = ConnState.DISCONNECTED
    }

    // ------------------------------------------------------------ simulation
    // A fake device so the whole UI can be exercised with no hardware. It
    // drives the same status/result flows the real GATT path does, so every
    // screen behaves identically — verify steps auto-acknowledge, ARM produces
    // a realistic shot, and signal loss can be triggered on demand.

    var isSimulation = false
        private set

    /** Gate spacing in meters, kept in sync by the ViewModel so simulated
     *  splits map to believable velocities regardless of the configured gap. */
    var simDistanceM: Double = 0.1524   // 6 in default

    private val simScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var simJob: Job? = null
    private var simState = Proto.ST_IDLE
    private var simPending = 0
    private var simTimeValid = false
    private var simNextId = 1
    private var simBarePhase = true

    fun connectSimulated() {
        stopScan()
        isSimulation = true
        simState = Proto.ST_IDLE
        simPending = 0
        simTimeValid = false
        simBarePhase = true
        hwInfo.value = HwInfo(1, 1, 4, 62_500, 30, 300)   // pretends to be rev 1
        pushSimStatus()
        connState.value = ConnState.CONNECTED
    }

    /** Toggle the link for testing: drop to RECONNECTING, tap again to restore. */
    fun simulateSignalLoss() {
        if (!isSimulation) return
        connState.value = if (connState.value == ConnState.RECONNECTING) ConnState.CONNECTED
        else ConnState.RECONNECTING
    }

    private fun pushSimStatus() {
        status.value = DeviceStatus(simState, simPending, simTimeValid)
    }

    private fun simCommand(cmd: Byte, arg: Int) {
        when (cmd) {
            Proto.CMD_VERIFY1 -> simVerify(1)
            Proto.CMD_VERIFY2 -> simVerify(2)
            Proto.CMD_ARM -> simArm()
            Proto.CMD_DISARM, Proto.CMD_CANCEL -> {
                simJob?.cancel()
                simState = Proto.ST_IDLE
                pushSimStatus()
            }
            Proto.CMD_ACK -> {
                if (simPending > 0) simPending--
                pushSimStatus()
            }
            Proto.CMD_FETCH -> Unit   // nothing buffered in simulation
            Proto.CMD_CALIBRATE -> simCalibrate(arg)
        }
    }

    /**
     * The app tells the sim whether the upcoming sweep is a bare-port baseline
     * or a loaded (sensor-attached) measurement, so the loaded value is always
     * higher than the baseline regardless of how many times setup is redone.
     */
    fun setSimCalPhase(bare: Boolean) { simBarePhase = bare }

    private fun simCalibrate(channel: Int) {
        if (channel !in 1..2) return
        val bare = simBarePhase
        simScope.launch {
            val prev = simState
            simState = Proto.ST_CALIBRATING
            pushSimStatus()
            delay(900)
            val base = if (bare) 430L + channel * 25L else 4600L + channel * 240L
            val median = base + (-25L..25L).random()
            cal.tryEmit(
                CalReading(
                    channel = channel, status = 0, samples = 64,
                    medianNs = median, meanNs = median + 4,
                    stddevNs = 35L + (0L..20L).random(), minNs = median - 60,
                )
            )
            simState = prev
            pushSimStatus()
        }
    }

    private fun simVerify(sensor: Int) {
        simJob?.cancel()
        simState = if (sensor == 1) Proto.ST_VERIFY1 else Proto.ST_VERIFY2
        pushSimStatus()
        // Deliberately does NOT auto-complete: the user presses the app's
        // "Simulate sensor tap" button, mirroring the real physical tap.
    }

    /** Sim stand-in for physically tapping the sensor under test. */
    fun simulateSensorTap() {
        if (!isSimulation) return
        when (simState) {
            Proto.ST_VERIFY1 -> { simState = Proto.ST_VERIFY1_OK; pushSimStatus() }
            Proto.ST_VERIFY2 -> { simState = Proto.ST_VERIFY2_OK; pushSimStatus() }
            else -> Unit
        }
    }

    private fun simArm() {
        simJob?.cancel()
        simState = Proto.ST_ARMED
        pushSimStatus()
        simJob = simScope.launch {
            delay(1800)                    // waiting for the shot
            simState = Proto.ST_RUNNING
            pushSimStatus()
            delay(350)                     // shot in flight
            val split = simSplitNs()
            val epoch = if (simTimeValid) System.currentTimeMillis() / 1000L else 0L
            simPending++
            pushSimStatus()
            results.tryEmit(RawResult(simNextId++, split, epoch))
            simState = Proto.ST_IDLE
            pushSimStatus()
        }
    }

    /** A split that yields a random, realistic muzzle velocity for the set gap. */
    private fun simSplitNs(): Long {
        val fps = (900..3200).random().toDouble()
        val mps = fps / 3.28084
        val d = if (simDistanceM > 0) simDistanceM else 0.1524
        val seconds = d / mps
        return (seconds * 1_000_000_000.0).toLong().coerceAtLeast(1)
    }

    // ---------------------------------------- serialized GATT operation queue

    private val opLock = Any()
    private val opQueue = ArrayDeque<() -> Boolean>()
    private var opInFlight = false

    /** Queue a GATT call. The op returns false if it failed to start; then we move on. */
    private fun enqueue(op: () -> Boolean) {
        synchronized(opLock) {
            opQueue.addLast(op)
            if (!opInFlight) pump()
        }
    }

    private fun pump() {
        while (true) {
            val op = opQueue.removeFirstOrNull() ?: run { opInFlight = false; return }
            opInFlight = true
            val startedOk = runCatching(op).getOrDefault(false)
            if (startedOk) return // wait for the matching onXxx callback -> opDone()
            // op failed to start; try the next one
        }
    }

    private fun opDone() {
        synchronized(opLock) {
            opInFlight = false
            pump()
        }
    }

    // ------------------------------------------------------------- commands

    @Suppress("DEPRECATION")
    fun sendCommand(cmd: Byte, arg: Int = 0) {
        if (isSimulation) { simCommand(cmd, arg); return }
        val payload = byteArrayOf(cmd, (arg and 0xFF).toByte(), ((arg shr 8) and 0xFF).toByte())
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chControl ?: return@enqueue false
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = payload
            g.writeCharacteristic(c)
        }
    }

    /** Push the phone's current time to the device (unix seconds, little-endian). */
    @Suppress("DEPRECATION")
    fun syncTime() {
        if (isSimulation) { simTimeValid = true; pushSimStatus(); return }
        val epoch = (System.currentTimeMillis() / 1000L).toInt()
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(epoch).array()
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chTime ?: return@enqueue false
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = payload
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(c: BluetoothGattCharacteristic) {
        enqueue {
            val g = gatt ?: return@enqueue false
            if (!g.setCharacteristicNotification(c, true)) return@enqueue false
            val d = c.getDescriptor(Proto.CCCD) ?: return@enqueue false
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
    }

    private fun readStatus() {
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chStatus ?: return@enqueue false
            g.readCharacteristic(c)
        }
    }

    private fun readInfo() {
        enqueue {
            val g = gatt ?: return@enqueue false
            val c = chInfo ?: return@enqueue false
            g.readCharacteristic(c)
        }
    }

    // ------------------------------------------------------- GATT callbacks

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestMtu(185)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(opLock) { opQueue.clear(); opInFlight = false }
                    if (userDisconnected) {
                        g.close()
                        gatt = null
                        connState.value = ConnState.DISCONNECTED
                    } else {
                        // Expected during test standby: reconnect automatically.
                        // autoConnect=true waits patiently until the device is
                        // back in range, then the whole init sequence reruns.
                        val device = g.device
                        g.close()
                        connState.value = ConnState.RECONNECTING
                        gatt = device.connectGatt(context, true, this, BluetoothDevice.TRANSPORT_LE)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(Proto.SERVICE)
            if (svc == null) {
                g.disconnect()
                return
            }
            chStatus = svc.getCharacteristic(Proto.STATUS)
            chControl = svc.getCharacteristic(Proto.CONTROL)
            chResult = svc.getCharacteristic(Proto.RESULT)
            chTime = svc.getCharacteristic(Proto.TIME)
            chCal = svc.getCharacteristic(Proto.CAL)    // optional (newer firmware)
            chInfo = svc.getCharacteristic(Proto.INFO)  // optional (newer firmware)
            if (chStatus == null || chControl == null || chResult == null || chTime == null) {
                g.disconnect()
                return
            }
            enableNotifications(chStatus!!)
            enableNotifications(chResult!!)
            chCal?.let { enableNotifications(it) }
            readStatus()
            readInfo()
            syncTime()                        // sync clock on every (re)connect
            sendCommand(Proto.CMD_FETCH)      // collect results recorded while disconnected
            enqueue {
                connState.value = ConnState.CONNECTED
                false // action-only op: report "didn't start a GATT call" so the queue moves on
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = opDone()

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = opDone()

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) c.value?.let { handleValue(c.uuid, it) }
            opDone()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { handleValue(c.uuid, it) }
        }
    }

    private fun handleValue(uuid: UUID, v: ByteArray) {
        when (uuid) {
            Proto.STATUS -> if (v.size >= 3) {
                status.value = DeviceStatus(
                    state = v[0].toInt() and 0xFF,
                    pendingCount = v[1].toInt() and 0xFF,
                    timeValid = v[2].toInt() != 0,
                )
            }
            Proto.RESULT -> if (v.size >= 11) {
                val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
                val id = b.short.toInt() and 0xFFFF
                val splitNs = b.int.toLong() and 0xFFFFFFFFL
                val epochSec = b.int.toLong() and 0xFFFFFFFFL
                results.tryEmit(RawResult(id, splitNs, epochSec))
            }
            Proto.CAL -> if (v.size >= 20) {
                val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
                val channel = b.get().toInt() and 0xFF
                val calStatus = b.get().toInt() and 0xFF
                val samples = b.short.toInt() and 0xFFFF
                val medianNs = b.int.toLong() and 0xFFFFFFFFL
                val meanNs = b.int.toLong() and 0xFFFFFFFFL
                val stddevNs = b.int.toLong() and 0xFFFFFFFFL
                val minNs = b.int.toLong() and 0xFFFFFFFFL
                cal.tryEmit(CalReading(channel, calStatus, samples, medianNs, meanNs, stddevNs, minNs))
            }
            Proto.INFO -> if (v.size >= 12) {
                val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
                val hwRev = b.get().toInt() and 0xFF
                val fwMajor = b.get().toInt() and 0xFF
                val fwMinor = b.get().toInt() and 0xFF
                b.get()   // reserved
                val tickPs = b.int.toLong() and 0xFFFFFFFFL
                val clockPpm = b.short.toInt() and 0xFFFF
                val edgeJitterNs = b.short.toInt() and 0xFFFF
                hwInfo.value = HwInfo(hwRev, fwMajor, fwMinor, tickPs, clockPpm, edgeJitterNs)
            }
        }
    }
}
