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
 * Data class representing IMU angles in degrees.
 */
data class ImuData(val pitch: Int, val roll: Int, val yaw: Int)

/**
 * Data class representing real-time HUD telemetry for UI debug widgets.
 */
data class HudDebugData(
    val arrowBearing: Float = 65535f,
    val nextTurnAngle: Float = 65535f,
    val carFacing: Float = 0f,
    val distanceMeters: Int = 0,
    val speedKmH: Int = 0,
    val speedLimitKmH: Int = 0,
    val signIndex: Int = 0,
    val imuYaw: Float = 0f,
    val imuOffset: Float = 0f
)

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

        // Standard CCCD UUID
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Filter devices by advertised local name (set on the ESP32 side)
        private const val TARGET_DEVICE_NAME = "ARHUD"

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

    private val _imuData = MutableStateFlow<ImuData?>(null)
    val imuData = _imuData.asStateFlow()

    private var rawYaw: Float? = null
    private val _imuYawOffset = MutableStateFlow(0f)
    val imuYawOffset = _imuYawOffset.asStateFlow()
    private var pendingTargetHeading: Float? = null

    private val _hudDebugData = MutableStateFlow(HudDebugData())
    val hudDebugData = _hudDebugData.asStateFlow()

    fun updateHudDebugData(
        arrowBearing: Float,
        nextTurnAngle: Float,
        carFacing: Float,
        distanceMeters: Int,
        speedKmH: Int = 0,
        speedLimitKmH: Int = 0,
        signIndex: Int = 0,
        imuYaw: Float = 0f,
        imuOffset: Float = 0f
    ) {
        _hudDebugData.value = HudDebugData(
            arrowBearing = arrowBearing,
            nextTurnAngle = nextTurnAngle,
            carFacing = carFacing,
            distanceMeters = distanceMeters,
            speedKmH = speedKmH,
            speedLimitKmH = speedLimitKmH,
            signIndex = signIndex,
            imuYaw = imuYaw,
            imuOffset = imuOffset
        )
    }

    /**
     * Offsets the incoming IMU yaw so that the current/calibrated IMU yaw matches the target heading (0..360° CW).
     */
    fun setImuOffsetToTargetHeading(targetHeadingCw: Float) {
        val normalizedTarget = ((targetHeadingCw % 360f) + 360f) % 360f
        val currentRaw = rawYaw
        if (currentRaw != null) {
            var offset = (normalizedTarget - currentRaw) % 360f
            if (offset < 0) offset += 360f
            _imuYawOffset.value = offset

            val currentImu = _imuData.value
            val pitch = currentImu?.pitch ?: 0
            val roll = currentImu?.roll ?: 0
            var calibratedYaw = (currentRaw + offset) % 360f
            if (calibratedYaw < 0) calibratedYaw += 360f
            _imuData.value = ImuData(pitch, roll, calibratedYaw.toInt())
            Log.i(TAG, "setImuOffsetToTargetHeading: target=$normalizedTarget°, raw=$currentRaw°, offset=$offset°, calYaw=$calibratedYaw°")
        } else {
            pendingTargetHeading = normalizedTarget
            Log.i(TAG, "setImuOffsetToTargetHeading: No raw IMU yet, pending target=$normalizedTarget°")
        }
    }

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

        // Cancel previous scan if still active
        if (isScanning) {
            try {
                bleScanner?.stopScan(scanCallback)
            } catch (ignored: Exception) {}
            isScanning = false
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
                .build(),
            ScanFilter.Builder()
                .setDeviceName("ARHUD")
                .build(),
            ScanFilter.Builder()
                .setDeviceName("ESP32_NAV")
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
            mainHandler.removeCallbacksAndMessages(null)
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
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (ignored: Exception) {}
        if (!_isConnected.value && _status.value.startsWith("Scanning")) {
            _status.value = "Disconnected"
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val scanRecord = result.scanRecord
            val devName = device.name ?: scanRecord?.deviceName

            val matchesName = devName != null && (
                devName.equals("ARHUD", ignoreCase = true) ||
                devName.equals("ESP32_NAV", ignoreCase = true) ||
                devName.contains("HUD", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            )

            val matchesService = scanRecord?.serviceUuids?.any {
                it.uuid == SERVICE_UUID
            } == true

            if (matchesName || matchesService || devName == TARGET_DEVICE_NAME) {
                val displayName = devName ?: "ARHUD"
                _status.value = "Device found: $displayName. Connecting..."
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

            // Enable notifications for this characteristic
            enableNotifications(gatt, targetCharacteristic!!)

            _status.value = "Connected"
            _isConnected.value = true
            callback?.onConnected()
        }

        /**
         * Core GATT callback for receiving asynchronous updates from the ESP32
         * when notifications are enabled.
         */
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleIncomingData(characteristic.value)
        }

        // Android 13+ (API 33) version of the callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingData(value)
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

    /**
     * Configures the GATT client to receive asynchronous updates (notifications)
     * from the ESP32 for the target characteristic.
     */
    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (!success) {
            Log.e(TAG, "Failed to enable local notification for characteristic")
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeSuccess = gatt.writeDescriptor(descriptor)
            if (!writeSuccess) {
                Log.e(TAG, "Failed to write CCCD descriptor to enable notifications")
            }
        } else {
            Log.e(TAG, "CCCD descriptor not found for characteristic")
        }
    }

    /**
     * Handles incoming BLE notification frames from the ESP32.
     * Supports:
     * 1. 8-byte Telemetry Frame: [0xAA][0xBD][Heading_MSB][Heading_LSB][Reserved/Speed][Reserved][Reserved][0x55]
     *    where nav_heading = (int)yaw * 10
     * 2. Legacy 30-bit packed IMU Frame: [0xAA][P_MSB][P_LSB/R_MSB][R_LSB/Y_MSB][Y_LSB][0x00][0x00][0x55]
     */
    private fun handleIncomingData(data: ByteArray?) {
        if (data == null || data.size < 8) return

        // Verify start and end frame markers
        val header = data[0].toInt() and 0xFF
        val footer = data[7].toInt() and 0xFF
        if (header != 0xAA || footer != 0x55) {
            Log.w(TAG, "Received invalid frame sync: Header=0x${Integer.toHexString(header)}, Footer=0x${Integer.toHexString(footer)}")
            return
        }
        // ESP32 Telemetry Frame (0xBD):
        // data[2] = Heading MSB
        // data[3] = Heading LSB (nav_heading in tenths of degree: (int)yaw * 10)
        // data[4] = Reserved / Speed OBD
        val headingMsb = data[2].toInt() and 0xFF
        val headingLsb = data[3].toInt() and 0xFF
        val navHeading = (headingMsb shl 8) or headingLsb
        val incomingYaw = (navHeading / 10f) % 360f
        rawYaw = incomingYaw

        val pending = pendingTargetHeading
        if (pending != null) {
            pendingTargetHeading = null
            setImuOffsetToTargetHeading(pending)
        }

        val offset = _imuYawOffset.value
        var calibratedYaw = (incomingYaw + offset) % 360f
        if (calibratedYaw < 0) calibratedYaw += 360f
        val yaw = calibratedYaw.toInt()
        val speedObd = data[1].toInt() and 0xFF

        Log.i(TAG, "ESP32 Frame 0xBD: RawYaw=$incomingYaw°, Offset=$offset°, CalYaw=$calibratedYaw°, SpeedOBD=$speedObd")

        _imuData.value = ImuData(pitch = 0, roll = 0, yaw = yaw)

        if (speedObd > 0) {
            val currentDebug = _hudDebugData.value
            _hudDebugData.value = currentDebug.copy(speedKmH = speedObd)
        }
    }

    // ---------------------------------------------------------------------
    // Data packing & sending
    // ---------------------------------------------------------------------
    /**
     * Upload data to device using the 11-byte frame structure:
     * ID1(1B) + Speed_APP(1B) + ArrowAngle(2B) + SignIdx(1B) + SignDist(2B) + SpeedLimit_APP(1B) + LineData(3B)
     */
    fun sendDeviceDataFlow(
        id1: Int = 0x01,
        speedApp: Int = 0,
        arrowAngle: Int = 0,
        signIdx: Int = 0,
        signDist: Int = 0,
        speedLimitApp: Int = 0,
        lineData: ByteArray = ByteArray(3)
    ) {
        val angle = arrowAngle.coerceIn(0, 0xFFFF)
        val dist = signDist.coerceIn(0, 0xFFFF)
        val lines = ByteArray(3)
        if (lineData.isNotEmpty()) {
            val copyLen = minOf(3, lineData.size)
            System.arraycopy(lineData, 0, lines, 0, copyLen)
        }
        val frame = byteArrayOf(
            (id1 and 0xFF).toByte(),
            (speedApp.coerceIn(0, 255)).toByte(),
            ((angle shr 8) and 0xFF).toByte(),
            (angle and 0xFF).toByte(),
            (signIdx.coerceIn(0, 255)).toByte(),
            ((dist shr 8) and 0xFF).toByte(),
            (dist and 0xFF).toByte(),
            (speedLimitApp.coerceIn(0, 255)).toByte(),
            lines[0],
            lines[1],
            lines[2]
        )

        enqueueWrite(frame)
    }

    /**
     * Helper method for navigation updates using the 11-byte upload to device mechanism.
     */
    fun sendNavigationData(
        heading: Float,
        distanceMeters: Int,
        speedKmH: Int = 0,
        signIndex: Int = 0,
        speedLimitKmH: Int = 0,
        laneData: ByteArray = ByteArray(3)
    ) {
        val scaledHeading = if (heading >= 65535f || heading < 0f) {
            65535 // Out of bounds indicator (0xFFFF)
        } else {
            (heading * 10).toInt().coerceIn(0, 3600)
        }
        sendDeviceDataFlow(
            id1 = 0x01,
            speedApp = speedKmH,
            arrowAngle = scaledHeading,
            signIdx = signIndex,
            signDist = distanceMeters,
            speedLimitApp = speedLimitKmH,
            lineData = laneData
        )
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