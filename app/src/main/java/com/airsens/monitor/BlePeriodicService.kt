package com.airsens.monitor

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.airsens.monitor.database.AppDatabase
import com.airsens.monitor.database.MeasurementEntity
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.CancellationException

class BlePeriodicService : Service() {

    companion object {
        private const val TAG = "BlePeriodicService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ble_periodic_channel"

        private const val SCAN_TIMEOUT_SCREEN_ON = 2000L    // 2 seconds when screen is on
        private const val SCAN_TIMEOUT_SCREEN_OFF = 10000L  // 10 seconds when screen is off
        private const val CONNECTION_INTERVAL = 60000L   // 60 seconds between connections
        private const val CONNECTION_TIMEOUT = 10000L    // 10 seconds max connection time
        private const val DISCONNECT_DELAY = 1000L       // 1 second delay after disconnect before cleanup

        val SERVICE_UUID: UUID = UUID.fromString("0000AAAA-0000-1000-8000-00805F9B34FB")
        val MEASUREMENT_UUID: UUID = UUID.fromString("0000AAA1-0000-1000-8000-00805F9B34FB")
        val TIME_SYNC_UUID: UUID = UUID.fromString("0000AAA2-0000-1000-8000-00805F9B34FB")
        val MEASUREMENT_COUNT_UUID: UUID = UUID.fromString("0000AAA9-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEVICE_NAME = "BMV080"

        const val ACTION_START = "com.airsens.monitor.START_SERVICE"
        const val ACTION_STOP = "com.airsens.monitor.STOP_SERVICE"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var database: AppDatabase
    private lateinit var powerManager: PowerManager

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var currentGatt: BluetoothGatt? = null

    private var isRunning = false

    // Callbacks for GATT operations
    private var onCharacteristicReadCallback: ((ByteArray?, Int) -> Unit)? = null
    private var onCharacteristicChangedCallback: ((ByteArray?) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        database = AppDatabase.getDatabase(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForeground(NOTIFICATION_ID, createNotification("Starting..."))
                    isRunning = true
                    schedulePeriodicTask()
                    Log.d(TAG, "Service started")
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        currentGatt?.close()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Air Quality Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Periodic BLE connection to air quality sensor"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirSens Monitor")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun schedulePeriodicTask() {
        val periodicTask = object : Runnable {
            override fun run() {
                if (isRunning) {
                    serviceScope.launch {
                        connectAndReadData()
                    }
                    handler.postDelayed(this, CONNECTION_INTERVAL)
                }
            }
        }

        // Run immediately, then repeat
        handler.post(periodicTask)
    }

    private suspend fun connectAndReadData() {
        try {
            updateNotification("Scanning for device...")
            Log.d(TAG, "Starting periodic connection cycle")

            // 1. Scan for device
            val device = scanForDevice() ?: run {
                updateNotification("Device not found")
                Log.w(TAG, "Device not found during scan")
                return
            }

            updateNotification("Connecting...")
            Log.d(TAG, "Found device: ${device.address}")

            // 2. Connect with timeout
            val gatt = withTimeoutOrNull(CONNECTION_TIMEOUT) {
                connectToDevice(device)
            } ?: run {
                updateNotification("Connection timeout")
                Log.w(TAG, "Connection timeout")
                return
            }

            try {
                // 3. Read measurement count (optional - for monitoring)
                val count = readMeasurementCount(gatt)
                Log.d(TAG, "Measurement count: $count (for monitoring)")

                // 4. Read latest measurement (REQUIRED - always read, even if count is 0)
                updateNotification("Reading data...")
                val measurement = readMeasurement(gatt)

                if (measurement != null) {
                    // 5. Store in database
                    val entity = MeasurementEntity(
                        timestamp = measurement.timestamp,
                        receivedAt = System.currentTimeMillis(),
                        pm1 = measurement.pm1,
                        pm25 = measurement.pm25,
                        pm10 = measurement.pm10,
                        obstructed = measurement.obstructed,
                        timeValid = measurement.timeValid,
                        temperature = measurement.temperature,
                        humidity = measurement.humidity,
                        pressure = measurement.pressure,
                        iaq = measurement.iaq,
                        gasResistance = measurement.gasResistance,
                        iaqAccuracy = measurement.iaqAccuracy
                    )

                    database.measurementDao().insert(entity)
                    database.measurementDao().keepOnlyLast(500) // Keep last 500 measurements

                    Log.d(TAG, "Stored measurement: PM2.5=${measurement.pm25}, Temp=${measurement.temperature}")
                    updateNotification("Last update: ${Date()}")
                } else {
                    Log.w(TAG, "No valid measurement data received")
                    updateNotification("No data available")
                }

                // Note: Time sync is now indication-based (ESP32 will request it via indication)
                // We no longer proactively send time sync every 2 connections

            } finally {
                // 7. Disconnect and cleanup
                disconnectAndCleanup(gatt)
            }

        } catch (e: CancellationException) {
            // Connection was cancelled (device disconnected before service discovery)
            Log.w(TAG, "Connection cancelled: ${e.message}")
            updateNotification("Connection failed")
            currentGatt?.close()
            currentGatt = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in periodic connection", e)
            updateNotification("Error: ${e.message}")
            currentGatt?.close()
            currentGatt = null
        }
    }

    private suspend fun scanForDevice(): BluetoothDevice? = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(null) {}
            return@suspendCancellableCoroutine
        }

        // Check if screen is on and adjust scan mode accordingly
        val isScreenOn = powerManager.isInteractive
        val scanMode = if (isScreenOn) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_LOW_POWER
        }
        val scanTimeout = if (isScreenOn) {
            SCAN_TIMEOUT_SCREEN_ON
        } else {
            SCAN_TIMEOUT_SCREEN_OFF
        }

        Log.d(TAG, "Starting BLE scan - Screen: ${if (isScreenOn) "ON" else "OFF"}, Mode: ${if (isScreenOn) "LOW_LATENCY" else "LOW_POWER"}, Timeout: ${scanTimeout}ms")

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        // Use scan filter to find device by name - this gets higher priority even when screen is off
        val scanFilter = ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
            .build()
        val scanFilters = listOf(scanFilter)

        Log.d(TAG, "Using scan filter for device name: $DEVICE_NAME")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d(TAG, "Device found in scan: ${result.device.address}")
                // Filter already matched, so we can directly use this device
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(result.device) {}
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(null) {}
                }
            }
        }

        // Start scan with filter - this tells Android we're looking for a specific device
        bluetoothLeScanner?.startScan(scanFilters, settings, callback)

        // Timeout after scanTimeout (varies based on screen state)
        handler.postDelayed({
            bluetoothLeScanner?.stopScan(callback)
            if (continuation.isActive) {
                Log.d(TAG, "BLE scan timeout reached")
                continuation.resume(null) {}
            }
        }, scanTimeout)

        continuation.invokeOnCancellation {
            bluetoothLeScanner?.stopScan(callback)
        }
    }

    private suspend fun connectToDevice(device: BluetoothDevice): BluetoothGatt = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.cancel()
            return@suspendCancellableCoroutine
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState (${getConnectionStateString(newState)})")

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "BLE connected, discovering services...")
                        if (ActivityCompat.checkSelfPermission(
                                this@BlePeriodicService,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            gatt.discoverServices()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "BLE disconnected with status=$status (${getGattStatusString(status)})")
                        if (continuation.isActive) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                Log.e(TAG, "Connection failed with error status: $status")
                            }
                            continuation.cancel()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "onServicesDiscovered: status=$status (${getGattStatusString(status)})")
                if (status == BluetoothGatt.GATT_SUCCESS && continuation.isActive) {
                    Log.d(TAG, "Service discovery successful")

                    // Enable indications for time sync characteristic (ESP32 will request time sync)
                    enableTimeSyncIndications(gatt)

                    continuation.resume(gatt) {}
                } else if (continuation.isActive) {
                    Log.e(TAG, "Service discovery failed with status: $status")
                    continuation.cancel()
                }
            }

            // For Android API 33+
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                Log.d(TAG, "onCharacteristicRead (API 33+) called: ${value.size} bytes, status=$status")
                onCharacteristicReadCallback?.invoke(value, status)
            }

            // For Android API < 33 (deprecated but still needed)
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                Log.d(TAG, "onCharacteristicRead (deprecated) called: ${characteristic.value?.size ?: 0} bytes, status=$status")
                onCharacteristicReadCallback?.invoke(characteristic.value, status)
            }

            // Handle indications from ESP32 (e.g., time sync request)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                Log.d(TAG, "onCharacteristicChanged (API 33+): UUID=${characteristic.uuid}, ${value.size} bytes")

                when (characteristic.uuid) {
                    TIME_SYNC_UUID -> {
                        Log.d(TAG, "ESP32 requesting time sync via indication")
                        serviceScope.launch {
                            sendTimeSync(gatt)
                        }
                    }
                    else -> {
                        Log.d(TAG, "Unhandled characteristic changed: ${characteristic.uuid}")
                    }
                }
            }

            // For Android API < 33 (deprecated but still needed)
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                Log.d(TAG, "onCharacteristicChanged (deprecated): UUID=${characteristic.uuid}")

                when (characteristic.uuid) {
                    TIME_SYNC_UUID -> {
                        Log.d(TAG, "ESP32 requesting time sync via indication")
                        serviceScope.launch {
                            sendTimeSync(gatt)
                        }
                    }
                    else -> {
                        Log.d(TAG, "Unhandled characteristic changed: ${characteristic.uuid}")
                    }
                }
            }
        }

        Log.d(TAG, "Calling device.connectGatt()")
        val gatt = device.connectGatt(this, false, callback)
        currentGatt = gatt

        continuation.invokeOnCancellation {
            Log.d(TAG, "Connection cancelled, closing GATT")
            gatt.close()
        }
    }

    private suspend fun readMeasurementCount(gatt: BluetoothGatt): Int = suspendCancellableCoroutine { continuation ->
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Service $SERVICE_UUID not found on device!")
            continuation.resume(0) {}
            return@suspendCancellableCoroutine
        }

        val characteristic = service.getCharacteristic(MEASUREMENT_COUNT_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic $MEASUREMENT_COUNT_UUID not found in service!")
            continuation.resume(0) {}
            return@suspendCancellableCoroutine
        }

        Log.d(TAG, "Reading measurement count characteristic...")

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(0) {}
            return@suspendCancellableCoroutine
        }

        onCharacteristicReadCallback = { value, status ->
            if (status == BluetoothGatt.GATT_SUCCESS && continuation.isActive && value != null) {
                val count = value.getOrNull(0)?.toInt() ?: 0
                continuation.resume(count and 0xFF) {}
            } else if (continuation.isActive) {
                continuation.resume(0) {}
            }
            onCharacteristicReadCallback = null
        }

        gatt.readCharacteristic(characteristic)

        // Timeout
        handler.postDelayed({
            if (continuation.isActive) {
                onCharacteristicReadCallback = null
                continuation.resume(0) {}
            }
        }, 3000)
    }

    private suspend fun readMeasurement(gatt: BluetoothGatt): AirQualitySensorClient.MeasurementData? = suspendCancellableCoroutine { continuation ->
        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(MEASUREMENT_UUID)

        if (characteristic == null) {
            continuation.resume(null) {}
            return@suspendCancellableCoroutine
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(null) {}
            return@suspendCancellableCoroutine
        }

        onCharacteristicReadCallback = { value, status ->
            if (status == BluetoothGatt.GATT_SUCCESS && continuation.isActive && value != null) {
                Log.d(TAG, "Measurement data received: ${value.size} bytes")
                Log.d(TAG, "First 20 bytes (hex): ${value.take(20).joinToString(" ") { "%02X".format(it) }}")
                val measurement = parseMeasurementData(value)
                continuation.resume(measurement) {}
            } else if (continuation.isActive) {
                Log.w(TAG, "Measurement read failed: status=$status, value=${value?.size ?: "null"}")
                continuation.resume(null) {}
            }
            onCharacteristicReadCallback = null
        }

        gatt.readCharacteristic(characteristic)

        // Timeout
        handler.postDelayed({
            if (continuation.isActive) {
                onCharacteristicReadCallback = null
                continuation.resume(null) {}
            }
        }, 3000)
    }

    private fun parseMeasurementData(data: ByteArray): AirQualitySensorClient.MeasurementData? {
        Log.d(TAG, "Parsing ${data.size} bytes of data")

        if (data.size < 38) {  // Minimum size needed
            Log.w(TAG, "Measurement data too short: ${data.size} bytes (need at least 38)")
            return null
        }

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Read sensor mask (1 byte at offset 0)
            val sensorMask = buffer.get(0).toInt() and 0xFF
            Log.d(TAG, "Sensor mask: 0x${"%02X".format(sensorMask)}")

            // Read timestamp (4 bytes at offset 1-4, uint32)
            val timestamp = buffer.getInt(1).toLong() and 0xFFFFFFFFL
            Log.d(TAG, "Timestamp: $timestamp")

            // Read PM values as floats (4 bytes each)
            // PM10 at offset 5, PM2.5 at offset 9, PM1 at offset 13
            val pm10 = buffer.getFloat(5)
            val pm25 = buffer.getFloat(9)
            val pm1 = buffer.getFloat(13)
            Log.d(TAG, "PM values - PM1.0: $pm1, PM2.5: $pm25, PM10: $pm10")

            // Read flags (1 byte at offset 17)
            val flags = buffer.get(17).toInt() and 0xFF
            val obstructed = (flags and 0x01) != 0
            val timeValid = (flags and 0x02) != 0
            val iaqAccuracy = (flags shr 2) and 0x03  // Bits 2-3
            Log.d(TAG, "Flags: 0x${"%02X".format(flags)} - obstructed=$obstructed, timeValid=$timeValid, iaqAccuracy=$iaqAccuracy")

            // Environmental data starts at offset 18 (if sensor_mask & 0x02)
            // Order: Temperature, Humidity, Pressure, IAQ, Gas Resistance
            val temperature = buffer.getFloat(18)
            val humidity = buffer.getFloat(22)
            val pressure = buffer.getFloat(26)
            val iaq = buffer.getFloat(30)
            val gasResistance = buffer.getFloat(34)

            Log.d(TAG, "Temperature: $temperature°C")
            Log.d(TAG, "Humidity: $humidity%")
            Log.d(TAG, "Pressure: $pressure Pa (${pressure/100} hPa)")
            Log.d(TAG, "IAQ: $iaq")
            Log.d(TAG, "Gas Resistance: $gasResistance Ω")

            return AirQualitySensorClient.MeasurementData(
                timestamp = timestamp,
                pm1 = pm1,
                pm25 = pm25,
                pm10 = pm10,
                obstructed = obstructed,
                timeValid = timeValid,
                temperature = if (temperature.isNaN() || temperature < -50 || temperature > 100) null else temperature,
                humidity = if (humidity.isNaN() || humidity < 0 || humidity > 100) null else humidity,
                pressure = if (pressure.isNaN() || pressure < 30000 || pressure > 120000) null else (pressure / 100), // Convert Pa to hPa
                iaq = if (iaq.isNaN() || iaq < 0 || iaq > 500) null else iaq,
                gasResistance = if (gasResistance.isNaN() || gasResistance < 0) null else gasResistance,
                iaqAccuracy = iaqAccuracy
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing measurement data", e)
            return null
        }
    }

    private fun enableTimeSyncIndications(gatt: BluetoothGatt) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No BLUETOOTH_CONNECT permission, cannot enable indications")
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        val timeSyncChar = service?.getCharacteristic(TIME_SYNC_UUID)

        if (timeSyncChar == null) {
            Log.w(TAG, "Time sync characteristic not found, cannot enable indications")
            return
        }

        // Enable local notifications/indications
        val success = gatt.setCharacteristicNotification(timeSyncChar, true)
        if (!success) {
            Log.e(TAG, "Failed to set characteristic notification")
            return
        }

        // Enable indications on the remote device by writing to CCCD
        val descriptor = timeSyncChar.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val writeSuccess = gatt.writeDescriptor(descriptor)
            Log.d(TAG, "Enabling time sync indications: ${if (writeSuccess) "success" else "failed"}")
        } else {
            Log.w(TAG, "CCCD descriptor not found for time sync characteristic")
        }
    }

    private suspend fun sendTimeSync(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(TIME_SYNC_UUID) ?: return

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val currentTime = System.currentTimeMillis() / 1000
        Log.d(TAG, "Sending time sync: $currentTime (${java.util.Date(currentTime * 1000)})")

        val timeBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(currentTime.toInt())
            .array()

        Log.d(TAG, "Time sync bytes (hex): ${timeBytes.joinToString(" ") { "%02X".format(it) }}")
        characteristic.value = timeBytes
        gatt.writeCharacteristic(characteristic)
    }

    private suspend fun disconnectAndCleanup(gatt: BluetoothGatt) {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                gatt.close()
                currentGatt = null
                return
            }

            Log.d(TAG, "Initiating disconnect...")
            gatt.disconnect()

            // Give BLE stack time to complete the disconnection
            delay(DISCONNECT_DELAY)

            gatt.close()
            currentGatt = null
            Log.d(TAG, "Disconnected and cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
            gatt.close()
            currentGatt = null
        }
    }

    private fun getConnectionStateString(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN($state)"
        }
    }

    private fun getGattStatusString(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH"
            BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
            133 -> "GATT_ERROR (133 - Generic error, often connection timeout)"
            8 -> "GATT_CONN_TIMEOUT (Connection timeout)"
            19 -> "GATT_CONN_TERMINATE_PEER_USER (Remote device terminated)"
            22 -> "GATT_CONN_TIMEOUT (Connection supervision timeout)"
            else -> "UNKNOWN($status)"
        }
    }
}
