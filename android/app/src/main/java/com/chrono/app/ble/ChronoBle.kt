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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_VERIFY1: Byte = 1
    const val CMD_VERIFY2: Byte = 2
    const val CMD_ARM: Byte = 3
    const val CMD_DISARM: Byte = 4
    const val CMD_ACK: Byte = 5
    const val CMD_CANCEL: Byte = 6
    const val CMD_FETCH: Byte = 7

    const val ST_IDLE = 0
    const val ST_VERIFY1 = 1
    const val ST_VERIFY1_OK = 2
    const val ST_VERIFY2 = 3
    const val ST_VERIFY2_OK = 4
    const val ST_ARMED = 5
    const val ST_RUNNING = 6
}

data class DeviceStatus(val state: Int, val pendingCount: Int, val timeValid: Boolean)

data class RawResult(val id: Int, val splitNs: Long, val epochSec: Long)

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
    val found = MutableStateFlow<List<FoundDevice>>(emptyList())

    val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var userDisconnected = false

    private var chControl: BluetoothGattCharacteristic? = null
    private var chStatus: BluetoothGattCharacteristic? = null
    private var chResult: BluetoothGattCharacteristic? = null
    private var chTime: BluetoothGattCharacteristic? = null

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
        userDisconnected = false
        connState.value = ConnState.CONNECTING
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userDisconnected = true
        synchronized(opLock) { opQueue.clear(); opInFlight = false }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        status.value = null
        connState.value = ConnState.DISCONNECTED
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
            if (chStatus == null || chControl == null || chResult == null || chTime == null) {
                g.disconnect()
                return
            }
            enableNotifications(chStatus!!)
            enableNotifications(chResult!!)
            readStatus()
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
        }
    }
}
