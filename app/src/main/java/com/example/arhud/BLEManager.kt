package com.example.arhud

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BleManager
 *
 * Responsible for the wireless data link between the phone and the ESP32.
 * Responsibilities:
 *  1. Scan for the ESP32 device by its advertised name / service UUID.
 *  2. Connect to the ESP32's GATT server using a known Service UUID.
 *  3. Pack turn/lane data computed by the solver into a compact ByteArray
 *     and write it to the target Characteristic.
 */
class BleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"

        @Volatile
        private var INSTANCE: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // TODO: Replace with the actual UUIDs defined on the ESP32 firmware side
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

        // Standard CCCD UUID, needed if we ever enable notifications from ESP32
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Filter devices by advertised local name (set on the ESP32 side)
        private const val TARGET_DEVICE_NAME = "ESP32_NAV"

        private const val SCAN_TIMEOUT_MS = 10_000L
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private var bleScanner: BluetoothLeScanner? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private val _status = MutableStateFlow("Disconnected")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _errors = MutableSharedFlow<String>()
    val errors = _errors.asSharedFlow()

    // Simple write queue to avoid overlapping GATT write operations
    // (BLE only allows one outstanding GATT operation at a time)
    private val writeQueue = ArrayDeque<ByteArray>()
    private var isWriting = false

    // Callback interface for the UI / upper layer to observe connection state
    interface BleCallback {
        fun onScanFound(device: BluetoothDevice)
        fun onConnected()
        fun onDisconnected()
        fun onDataSent(success: Boolean)
        fun onError(message: String)
    }

    var callback: BleCallback? = null

    private fun reportError(message: String) {
        Log.e(TAG, message)
        _status.value = "Error: $message"
        callback?.onError(message)
    }

    // ---------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------

    /**
     * Checks whether the permissions required to scan/connect are currently
     * granted. Called defensively inside startScan()/connect() so that this
     * class never crashes the app even if the caller (Activity) forgot to
     * check permissions first — e.g. a "Retry" button wired straight to
     * startScan() without re-checking permission state.
     */
    private fun hasScanPermission(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) return

        if (!hasScanPermission()) {
            _status.value = "Missing BLE permission"
            reportError("Missing BLE permission — request it before scanning")
            return
        }

        val adapter = bluetoothAdapter ?: run {
            _status.value = "Bluetooth not supported"
            reportError("Bluetooth is not supported on this device")
            return
        }
        if (!adapter.isEnabled) {
            _status.value = "Bluetooth disabled"
            reportError("Bluetooth is disabled")
            return
        }

        bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            _status.value = "No BLE scanner"
            reportError("No BLE scanner available on this device")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            _status.value = "Scanning..."
            bleScanner?.startScan(filters, settings, scanCallback)
            // Auto-stop scan after timeout to save battery
            mainHandler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            isScanning = false
            _status.value = "Permission denied"
            reportError("Permission denied at runtime: ${e.message}")
        } catch (e: IllegalStateException) {
            // Thrown when the Bluetooth stack itself isn't ready — common on emulators
            isScanning = false
            _status.value = "Bluetooth stack error"
            reportError("Bluetooth stack not ready: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        bleScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Optional extra filter by advertised name, in case multiple
            // devices expose the same service UUID
            if (device.name == TARGET_DEVICE_NAME) {
                _status.value = "Device found: ${device.name ?: "Unknown"}. Connecting..."
                callback?.onScanFound(device)
                stopScan()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            reportError("Scan failed, error code: $errorCode")
        }
    }

    // ---------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect() // ensure any previous connection is closed first
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        targetCharacteristic = null
        _status.value = "Disconnected"
        _isConnected.value = false
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server, discovering services...")
                    _status.value = "Discovering services..."
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    _status.value = "Disconnected"
                    _isConnected.value = false
                    callback?.onDisconnected()
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _status.value = "Service discovery failed"
                reportError("Service discovery failed, status: $status")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            targetCharacteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

            if (targetCharacteristic == null) {
                _status.value = "Target characteristic not found"
                reportError("Target characteristic not found")
                return
            }
            _status.value = "Connected"
            _isConnected.value = true
            callback?.onConnected()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            callback?.onDataSent(success)
            isWriting = false
            processNextWrite() // continue draining the queue
        }
    }

    // ---------------------------------------------------------------------
    // Data packing & sending
    // ---------------------------------------------------------------------

    /**
     * Sends a fixed, minimal test frame purely to verify the BLE write path
     * works end-to-end (phone -> GATT -> ESP32). No navigation logic involved.
     * Frame: [0xAA header][0x00 test command][0x01 payload byte][checksum]
     */
    fun sendTestPacket() {
        val payload = byteArrayOf(
            0xAA.toByte(), // header
            0x00,          // command type: test / ping
            0x01           // arbitrary test payload byte
        )
        val checksum = computeChecksum(payload, startIndex = 1)
        val frame = payload + checksum
        enqueueWrite(frame)
    }

    /**
     * Pack turn/lane navigation data into a compact binary frame and send it
     * over BLE.
     *
     * Example frame layout (customize to match ESP32 firmware's parser):
     * [0]      : Header byte, fixed 0xAA for frame sync
     * [1]      : Command / packet type (e.g. 0x01 = navigation update)
     * [2]      : Turn direction code (0=straight, 1=left, 2=right, 3=U-turn)
     * [3]      : Lane index (0-based, which lane to take)
     * [4]      : Total lane count at this segment
     * [5-6]    : Distance to the turn in meters, big-endian UInt16
     * [7]      : Checksum (simple XOR of bytes 1..6)
     */
    fun sendTurnAndLaneData(
        turnDirection: Int,
        laneIndex: Int,
        totalLanes: Int,
        distanceToTurnMeters: Int
    ) {
        val distance = distanceToTurnMeters.coerceIn(0, 0xFFFF)

        val payload = byteArrayOf(
            0xAA.toByte(),                       // header
            0x01,                                // command type: nav update
            turnDirection.toByte(),
            laneIndex.toByte(),
            totalLanes.toByte(),
            ((distance shr 8) and 0xFF).toByte(), // distance high byte
            (distance and 0xFF).toByte()          // distance low byte
        )

        val checksum = computeChecksum(payload, startIndex = 1)
        val frame = payload + checksum

        enqueueWrite(frame)
    }

    /**
     * Sends heading (angle) and distance to the current maneuver using the 
     * firmware-compatible 8-byte Command 0x01 structure.
     * 
     * Heading (0-3600) is split across turnDirection and laneIndex fields.
     * Distance is sent as a 16-bit value in the distanceToTurn field.
     */
    fun sendNavigationData(heading: Float, distanceMeters: Int) {
        val scaledHeading = (heading * 10).toInt().coerceIn(0, 3600)
        val distance = distanceMeters.coerceIn(0, 0xFFFF)

        // Packet format: [0xAA][0x01][H_MSB][H_LSB][0x00][D_MSB][D_LSB][CRC]
        val payload = byteArrayOf(
            0xAA.toByte(),
            0x01, // Command 0x01 as expected by firmware
            ((scaledHeading shr 8) and 0xFF).toByte(), // H_MSB -> turn_direction
            (scaledHeading and 0xFF).toByte(),        // H_LSB -> lane_index
            0x00,                                      // placeholder -> total_lanes
            ((distance shr 8) and 0xFF).toByte(),      // D_MSB -> distance_to_turn
            (distance and 0xFF).toByte()               // D_LSB -> distance_to_turn
        )

        val checksum = computeChecksum(payload, startIndex = 1)
        val frame = payload + checksum

        enqueueWrite(frame)
    }

    private fun computeChecksum(data: ByteArray, startIndex: Int): Byte {
        var xor = 0
        for (i in startIndex until data.size) {
            xor = xor xor data[i].toInt()
        }
        return xor.toByte()
    }

    // ---------------------------------------------------------------------
    // Write queue (GATT allows only one pending operation at a time)
    // ---------------------------------------------------------------------

    private fun enqueueWrite(data: ByteArray) {
        writeQueue.addLast(data)
        if (!isWriting) {
            processNextWrite()
        }
    }

    @SuppressLint("MissingPermission")
    private fun processNextWrite() {
        if (writeQueue.isEmpty()) return
        val gatt = bluetoothGatt ?: return
        val characteristic = targetCharacteristic ?: run {
            reportError("Characteristic not ready, cannot write")
            return
        }

        val data = writeQueue.removeFirst()
        isWriting = true

        // WRITE_TYPE_NO_RESPONSE is typically used for high-frequency
        // navigation updates to minimize latency; switch to
        // WRITE_TYPE_DEFAULT if the ESP32 side requires acknowledgment.
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = data

        val success = gatt.writeCharacteristic(characteristic)
        if (!success) {
            isWriting = false
            reportError("writeCharacteristic() returned false")
        }
    }

    // ---------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------

    fun release() {
        stopScan()
        disconnect()
        writeQueue.clear()
        callback = null
    }
}