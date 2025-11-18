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

        private const val SCAN_TIMEOUT_SCREEN_ON = 2000L       // 2 seconds when screen is on
        private const val SCAN_TIMEOUT_SCREEN_OFF = 10000L    // 10 seconds when screen is off
        private const val CONNECTION_INTERVAL_FOREGROUND = 30000L   // 30 seconds when app in foreground
        private const val CONNECTION_INTERVAL_BACKGROUND = 300000L  // 5 minutes when app in background
        private const val CONNECTION_TIMEOUT = 10000L          // 10 seconds max connection time
        private const val DISCONNECT_DELAY = 1000L             // 1 second delay after disconnect before cleanup

        val SERVICE_UUID: UUID = UUID.fromString("0000AAAA-0000-1000-8000-00805F9B34FB")
        val MEASUREMENT_UUID: UUID = UUID.fromString("0000AAA1-0000-1000-8000-00805F9B34FB")
        val TIME_SYNC_UUID: UUID = UUID.fromString("0000AAA2-0000-1000-8000-00805F9B34FB")
        val DATA_REQUEST_UUID: UUID = UUID.fromString("0000AAA5-0000-1000-8000-00805F9B34FB")
        val DATA_RESPONSE_UUID: UUID = UUID.fromString("0000AAA6-0000-1000-8000-00805F9B34FB")
        val DELETE_REQUEST_UUID: UUID = UUID.fromString("0000AAA7-0000-1000-8000-00805F9B34FB")
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
    private var onDescriptorWriteCallback: ((Boolean) -> Unit)? = null

    // Bulk data sync state
    private val receivedChunks = mutableListOf<AirQualitySensorClient.MeasurementData>()
    private var expectedTotalChunks = 0
    private var bulkSyncCompletionCallback: ((List<AirQualitySensorClient.MeasurementData>) -> Unit)? = null

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
                    // Use adaptive intervals based on screen state
                    val isScreenOn = powerManager.isInteractive
                    val interval = if (isScreenOn) {
                        CONNECTION_INTERVAL_FOREGROUND  // 30 seconds when screen is on
                    } else {
                        CONNECTION_INTERVAL_BACKGROUND  // 5 minutes when screen is off
                    }
                    Log.d(TAG, "Scheduling next connection in ${interval/1000}s (screen ${if (isScreenOn) "ON" else "OFF"})")
                    handler.postDelayed(this, interval)
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
                Log.d(TAG, "Measurement count on ESP32: $count")

                // 4. Perform bulk data sync to get all buffered measurements
                updateNotification("Syncing data...")

                // Get last synced timestamp from database
                val lastSyncedTimestamp = database.measurementDao().getLatestTimestamp() ?: 0L
                val currentTime = System.currentTimeMillis() / 1000  // Unix timestamp in seconds

                Log.d(TAG, "Last synced timestamp: $lastSyncedTimestamp, current time: $currentTime")

                // Request all measurements since last sync
                val measurements = performBulkDataSync(
                    gatt = gatt,
                    startTime = lastSyncedTimestamp,
                    endTime = currentTime,
                    maxRecords = 100
                )

                if (measurements.isNotEmpty()) {
                    Log.d(TAG, "Received ${measurements.size} measurements from bulk sync")

                    // 5. Store all measurements in database
                    val entities = measurements.map { measurement ->
                        MeasurementEntity(
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
                    }

                    database.measurementDao().insertAll(entities)
                    database.measurementDao().keepOnlyLast(500) // Keep last 500 measurements

                    // 6. Acknowledge data to ESP32 (tells it to delete synced data from flash)
                    val maxTimestamp = measurements.maxOf { it.timestamp }
                    val ackSuccess = acknowledgeBulkData(gatt, maxTimestamp)

                    if (ackSuccess) {
                        Log.d(TAG, "Successfully acknowledged ${measurements.size} measurements (up to timestamp $maxTimestamp)")
                        updateNotification("Synced ${measurements.size} measurements")
                    } else {
                        Log.w(TAG, "Failed to acknowledge bulk data")
                        updateNotification("Sync incomplete")
                    }

                    val latest = measurements.last()
                    Log.d(TAG, "Latest measurement: PM2.5=${latest.pm25}, Temp=${latest.temperature}")
                } else {
                    Log.d(TAG, "No new measurements to sync (ESP32 buffer empty or no data in range)")
                    updateNotification("No new data")
                }

                // Note: Time sync is indication-based (ESP32 requests it via indication when needed)
                // We no longer proactively send time sync

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
        val timeoutCallback = Runnable {
            bluetoothLeScanner?.stopScan(callback)
            if (continuation.isActive) {
                Log.d(TAG, "BLE scan timeout reached")
                continuation.resume(null) {}
            }
        }
        handler.postDelayed(timeoutCallback, scanTimeout)

        continuation.invokeOnCancellation {
            bluetoothLeScanner?.stopScan(callback)
            handler.removeCallbacks(timeoutCallback)
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
                    DATA_RESPONSE_UUID -> {
                        // Bulk data chunk received
                        Log.d(TAG, "Received bulk data chunk: ${value.size} bytes")
                        handleBulkDataChunk(value)
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
                    DATA_RESPONSE_UUID -> {
                        // Bulk data chunk received (use deprecated value property)
                        val value = characteristic.value
                        Log.d(TAG, "Received bulk data chunk: ${value?.size ?: 0} bytes")
                        if (value != null) {
                            handleBulkDataChunk(value)
                        }
                    }
                    else -> {
                        Log.d(TAG, "Unhandled characteristic changed: ${characteristic.uuid}")
                    }
                }
            }

            // Handle descriptor write (for enabling indications)
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                Log.d(TAG, "onDescriptorWrite: descriptor=${descriptor.uuid}, status=$status (${getGattStatusString(status)})")
                val success = status == BluetoothGatt.GATT_SUCCESS
                onDescriptorWriteCallback?.invoke(success)
            }
        }

        Log.d(TAG, "Calling device.connectGatt()")
        val gatt = device.connectGatt(this, false, callback)

        if (gatt == null) {
            Log.e(TAG, "connectGatt() returned null - connection failed")
            continuation.cancel(CancellationException("connectGatt() returned null"))
            return@suspendCancellableCoroutine
        }

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
        val timeoutCallback = Runnable {
            if (continuation.isActive) {
                onCharacteristicReadCallback = null
                continuation.resume(0) {}
            }
        }
        handler.postDelayed(timeoutCallback, 3000)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutCallback)
        }
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
        val timeoutCallback = Runnable {
            if (continuation.isActive) {
                onCharacteristicReadCallback = null
                continuation.resume(null) {}
            }
        }
        handler.postDelayed(timeoutCallback, 3000)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutCallback)
        }
    }

    private fun parseMeasurementData(data: ByteArray): AirQualitySensorClient.MeasurementData? {
        Log.d(TAG, "Parsing ${data.size} bytes of data")

        // CORRECTED FORMAT: Variable size - 22 bytes (PM only) OR 42 bytes (PM + environmental)
        // [0]:     sensor_mask (uint8_t)
        // [1-4]:   timestamp (uint32_t)
        // [5-8]:   pm10 (float)
        // [9-12]:  pm25 (float)
        // [13-16]: pm1 (float)
        // [17]:    flags (uint8_t) - bit-packed
        // [18-41]: environmental data (if sensor_mask & 0x02)
        if (data.size < 18) {
            Log.w(TAG, "Measurement data too short: ${data.size} bytes (need at least 18)")
            return null
        }

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Read sensor mask (1 byte at offset 0)
            val sensorMask = buffer.get(0).toInt() and 0xFF
            val hasBME690 = (sensorMask and 0x02) != 0
            Log.d(TAG, "Sensor mask: 0x${"%02X".format(sensorMask)}, hasBME690=$hasBME690")

            // Read timestamp (4 bytes at offset 1-4, uint32)
            val timestamp = buffer.getInt(1).toLong() and 0xFFFFFFFFL
            Log.d(TAG, "Timestamp: $timestamp")

            // Read PM values as floats (4 bytes each)
            val pm10 = buffer.getFloat(5)
            val pm25 = buffer.getFloat(9)
            val pm1 = buffer.getFloat(13)
            Log.d(TAG, "PM values - PM1.0: $pm1, PM2.5: $pm25, PM10: $pm10")

            // Read flags (1 byte at offset 17) - BIT-PACKED
            val flags = buffer.get(17).toInt() and 0xFF
            val obstructed = (flags and 0x01) != 0          // Bit 0
            val timeValid = (flags and 0x02) != 0           // Bit 1
            val iaqAccuracy = (flags shr 2) and 0x03        // Bits 2-3
            Log.d(TAG, "Flags: 0x${"%02X".format(flags)} - obstructed=$obstructed, timeValid=$timeValid, iaqAccuracy=$iaqAccuracy")

            // Read environmental data (if sensor_mask & 0x02)
            var temperature: Float? = null
            var humidity: Float? = null
            var pressure: Float? = null
            var iaq: Float? = null
            var gasResistance: Float? = null

            if (hasBME690 && data.size >= 38) {
                temperature = buffer.getFloat(18)
                humidity = buffer.getFloat(22)
                pressure = buffer.getFloat(26)
                iaq = buffer.getFloat(30)
                gasResistance = buffer.getFloat(34)

                Log.d(TAG, "Temperature: $temperature°C")
                Log.d(TAG, "Humidity: $humidity%")
                Log.d(TAG, "Pressure: $pressure Pa (${pressure/100} hPa)")
                Log.d(TAG, "IAQ: $iaq")
                Log.d(TAG, "Gas Resistance: $gasResistance Ω")
            } else {
                Log.d(TAG, "No environmental data in this measurement (PM only, size=${data.size})")
            }

            return AirQualitySensorClient.MeasurementData(
                timestamp = timestamp,
                pm1 = pm1,
                pm25 = pm25,
                pm10 = pm10,
                obstructed = obstructed,
                timeValid = timeValid,
                temperature = if (temperature != null && !temperature.isNaN() && temperature in -50f..100f) temperature else null,
                humidity = if (humidity != null && !humidity.isNaN() && humidity in 0f..100f) humidity else null,
                pressure = if (pressure != null && !pressure.isNaN() && pressure in 30000f..120000f) (pressure / 100) else null,
                iaq = if (iaq != null && !iaq.isNaN() && iaq in 0f..500f) iaq else null,
                gasResistance = if (gasResistance != null && !gasResistance.isNaN() && gasResistance > 0) gasResistance else null,
                iaqAccuracy = iaqAccuracy
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing measurement data", e)
            return null
        }
    }

    // ===== Bulk Data Sync Functions =====

    /**
     * Enable indications on DATA_RESPONSE characteristic (0xAAA6)
     * Must be called BEFORE requesting bulk data
     */
    private suspend fun enableDataResponseIndications(gatt: BluetoothGatt): Boolean = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No BLUETOOTH_CONNECT permission, cannot enable indications")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        val service = gatt.getService(SERVICE_UUID)
        val dataResponseChar = service?.getCharacteristic(DATA_RESPONSE_UUID)

        if (dataResponseChar == null) {
            Log.w(TAG, "DATA_RESPONSE characteristic not found")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Enable local notifications/indications
        val success = gatt.setCharacteristicNotification(dataResponseChar, true)
        if (!success) {
            Log.e(TAG, "Failed to set characteristic notification for DATA_RESPONSE")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Enable indications on the remote device by writing to CCCD
        val descriptor = dataResponseChar.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "CCCD descriptor not found for DATA_RESPONSE characteristic")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Set up callback for descriptor write completion
        onDescriptorWriteCallback = { success ->
            Log.d(TAG, "DATA_RESPONSE CCCD write completed: ${if (success) "SUCCESS" else "FAILED"}")
            if (continuation.isActive) {
                continuation.resume(success) {}
            }
            onDescriptorWriteCallback = null // Clear callback
        }

        // Timeout for descriptor write
        val timeoutCallback = Runnable {
            if (continuation.isActive) {
                Log.e(TAG, "Descriptor write timeout - callback never received")
                onDescriptorWriteCallback = null
                continuation.resume(false) {}
            }
        }

        // Write to CCCD to enable indications
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val writeSuccess = gatt.writeDescriptor(descriptor)

        if (!writeSuccess) {
            Log.e(TAG, "Failed to initiate DATA_RESPONSE CCCD descriptor write")
            onDescriptorWriteCallback = null
            continuation.resume(false) {}
        } else {
            Log.d(TAG, "DATA_RESPONSE CCCD descriptor write initiated, waiting for callback...")
            handler.postDelayed(timeoutCallback, 5000) // 5 second timeout
        }

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutCallback)
            onDescriptorWriteCallback = null
        }
    }

    /**
     * Request bulk data from ESP32 (write to 0xAAA5)
     * @param gatt GATT connection
     * @param startTime Unix timestamp in seconds (start of range)
     * @param endTime Unix timestamp in seconds (end of range)
     * @param maxRecords Maximum number of records to return (0-500)
     */
    private suspend fun requestBulkData(
        gatt: BluetoothGatt,
        startTime: Long,
        endTime: Long,
        maxRecords: Int = 100
    ): Boolean = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        val service = gatt.getService(SERVICE_UUID)
        val dataRequestChar = service?.getCharacteristic(DATA_REQUEST_UUID)

        if (dataRequestChar == null) {
            Log.w(TAG, "DATA_REQUEST characteristic not found")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Build 10-byte request: [start_time(4), end_time(4), max_records(2)]
        val requestData = ByteBuffer.allocate(10).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(startTime.toInt())
            putInt(endTime.toInt())
            putShort(maxRecords.toShort())
        }.array()

        Log.d(TAG, "Requesting bulk data: startTime=$startTime, endTime=$endTime, maxRecords=$maxRecords")
        Log.d(TAG, "Request bytes (hex): ${requestData.joinToString(" ") { "%02X".format(it) }}")

        dataRequestChar.value = requestData
        dataRequestChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val writeSuccess = gatt.writeCharacteristic(dataRequestChar)

        if (writeSuccess) {
            Log.d(TAG, "Bulk data request sent successfully")
            continuation.resume(true) {}
        } else {
            Log.e(TAG, "Failed to send bulk data request")
            continuation.resume(false) {}
        }
    }

    /**
     * Parse a data chunk received via indication on 0xAAA6
     * Returns triple of (chunkIndex, totalChunks, measurementData)
     */
    private fun parseDataChunk(data: ByteArray): Triple<Int, Int, AirQualitySensorClient.MeasurementData?>? {
        try {
            if (data.size < 22) {
                Log.w(TAG, "Data chunk too small: ${data.size} bytes (need at least 22)")
                return null
            }

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Parse chunk header (first 4 bytes)
            val chunkIndex = buffer.getShort(0).toInt() and 0xFFFF
            val totalChunks = buffer.getShort(2).toInt() and 0xFFFF

            Log.d(TAG, "Parsing chunk $chunkIndex/$totalChunks (${data.size} bytes)")

            // Parse measurement data (starts at offset 4)
            // Format is same as regular measurement but without the chunk header
            val sensorMask = buffer.get(4).toInt() and 0xFF
            val hasBME690 = (sensorMask and 0x02) != 0

            // Validate size
            val expectedSize = if (hasBME690) 42 else 22
            if (data.size < expectedSize) {
                Log.w(TAG, "Chunk size mismatch: got ${data.size}, expected $expectedSize (hasBME690=$hasBME690)")
                return null
            }

            // Read timestamp (4 bytes at offset 5-8)
            val timestamp = buffer.getInt(5).toLong() and 0xFFFFFFFFL

            // Read PM values as floats (4 bytes each at offsets 9, 13, 17)
            val pm10 = buffer.getFloat(9)
            val pm25 = buffer.getFloat(13)
            val pm1 = buffer.getFloat(17)

            // Read flags (1 byte at offset 21)
            val flags = buffer.get(21).toInt() and 0xFF
            val obstructed = (flags and 0x01) != 0
            val timeValid = (flags and 0x02) != 0
            val iaqAccuracy = (flags shr 2) and 0x03

            // Read environmental data if present (offsets 22-41)
            var temperature: Float? = null
            var humidity: Float? = null
            var pressure: Float? = null
            var iaq: Float? = null
            var gasResistance: Float? = null

            if (hasBME690 && data.size >= 42) {
                temperature = buffer.getFloat(22)
                humidity = buffer.getFloat(26)
                pressure = buffer.getFloat(30)
                iaq = buffer.getFloat(34)
                gasResistance = buffer.getFloat(38)

                Log.d(TAG, "Chunk $chunkIndex: PM2.5=$pm25, Temp=$temperature, IAQ=$iaq")
            } else {
                Log.d(TAG, "Chunk $chunkIndex: PM2.5=$pm25 (PM only)")
            }

            val measurement = AirQualitySensorClient.MeasurementData(
                timestamp = timestamp,
                pm1 = pm1,
                pm25 = pm25,
                pm10 = pm10,
                obstructed = obstructed,
                timeValid = timeValid,
                temperature = temperature,
                humidity = humidity,
                pressure = pressure,
                iaq = iaq,
                gasResistance = gasResistance,
                iaqAccuracy = iaqAccuracy
            )

            return Triple(chunkIndex, totalChunks, measurement)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing data chunk", e)
            return null
        }
    }

    /**
     * Acknowledge bulk data transfer (write to 0xAAA7)
     * Tells ESP32 to delete all measurements up to maxTimestamp
     */
    private suspend fun acknowledgeBulkData(gatt: BluetoothGatt, maxTimestamp: Long): Boolean = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        val service = gatt.getService(SERVICE_UUID)
        val deleteRequestChar = service?.getCharacteristic(DELETE_REQUEST_UUID)

        if (deleteRequestChar == null) {
            Log.w(TAG, "DELETE_REQUEST characteristic not found")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Build 4-byte acknowledgment: [max_timestamp(4)]
        val ackData = ByteBuffer.allocate(4).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(maxTimestamp.toInt())
        }.array()

        Log.d(TAG, "Acknowledging bulk data up to timestamp $maxTimestamp")
        Log.d(TAG, "Acknowledgment bytes (hex): ${ackData.joinToString(" ") { "%02X".format(it) }}")

        deleteRequestChar.value = ackData
        deleteRequestChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val writeSuccess = gatt.writeCharacteristic(deleteRequestChar)

        if (writeSuccess) {
            Log.d(TAG, "Bulk data acknowledgment sent successfully")
            continuation.resume(true) {}
        } else {
            Log.e(TAG, "Failed to send bulk data acknowledgment")
            continuation.resume(false) {}
        }
    }

    /**
     * Handle a bulk data chunk received via indication
     * Collects chunks and triggers completion callback when all received
     */
    private fun handleBulkDataChunk(data: ByteArray) {
        val parsed = parseDataChunk(data) ?: run {
            Log.e(TAG, "Failed to parse data chunk")
            return
        }

        val (chunkIndex, totalChunks, measurement) = parsed

        if (chunkIndex == 0) {
            // First chunk - initialize collection
            Log.d(TAG, "Starting bulk data transfer: expecting $totalChunks chunks")
            expectedTotalChunks = totalChunks
            receivedChunks.clear()
        }

        // Add measurement if valid
        if (measurement != null) {
            receivedChunks.add(measurement)
            Log.d(TAG, "Collected chunk ${receivedChunks.size}/$expectedTotalChunks")
        } else {
            Log.w(TAG, "Chunk $chunkIndex had no valid measurement data")
        }

        // Check if transfer is complete
        if (receivedChunks.size >= expectedTotalChunks) {
            Log.d(TAG, "Bulk data transfer complete: ${receivedChunks.size} measurements received")

            // Trigger completion callback
            val callback = bulkSyncCompletionCallback
            if (callback != null) {
                callback(receivedChunks.toList()) // Make a copy
                bulkSyncCompletionCallback = null // Clear callback
            } else {
                Log.w(TAG, "Bulk data transfer completed but no callback registered")
            }

            // Clear state for next transfer
            receivedChunks.clear()
            expectedTotalChunks = 0
        }
    }

    /**
     * Perform bulk data sync with ESP32
     * Returns list of measurements received, or empty list if no data/error
     */
    private suspend fun performBulkDataSync(
        gatt: BluetoothGatt,
        startTime: Long,
        endTime: Long,
        maxRecords: Int = 100
    ): List<AirQualitySensorClient.MeasurementData> = withTimeoutOrNull(30000) {
        suspendCancellableCoroutine { continuation ->
            try {
                // Clear any previous state
                receivedChunks.clear()
                expectedTotalChunks = 0

                // Set up completion callback
                bulkSyncCompletionCallback = { measurements ->
                    Log.d(TAG, "Bulk sync callback triggered with ${measurements.size} measurements")
                    continuation.resume(measurements) {}
                }

                // Enable indications
                serviceScope.launch {
                    val indicationsEnabled = enableDataResponseIndications(gatt)
                    if (!indicationsEnabled) {
                        Log.e(TAG, "Failed to enable DATA_RESPONSE indications")
                        bulkSyncCompletionCallback = null
                        continuation.resume(emptyList()) {}
                        return@launch
                    }

                    // Small delay after CCCD write to ensure indication handling is fully set up
                    delay(300)

                    // Request bulk data
                    val requestSent = requestBulkData(gatt, startTime, endTime, maxRecords)
                    if (!requestSent) {
                        Log.e(TAG, "Failed to send bulk data request")
                        bulkSyncCompletionCallback = null
                        continuation.resume(emptyList()) {}
                        return@launch
                    }

                    Log.d(TAG, "Bulk data request sent, waiting for chunks...")

                    // If no data available, ESP32 won't send any indications
                    // Set a timeout to detect this case
                    serviceScope.launch {
                        delay(5000) // 5 second timeout for first chunk
                        if (expectedTotalChunks == 0 && receivedChunks.isEmpty()) {
                            Log.d(TAG, "No bulk data received (timeout) - ESP32 may have no data in range")
                            val callback = bulkSyncCompletionCallback
                            if (callback != null) {
                                callback(emptyList())
                                bulkSyncCompletionCallback = null
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in performBulkDataSync", e)
                bulkSyncCompletionCallback = null
                continuation.resume(emptyList()) {}
            }
        }
    } ?: run {
        Log.e(TAG, "Bulk data sync timeout")
        bulkSyncCompletionCallback = null
        emptyList()
    }

    // ===== End Bulk Data Sync Functions =====

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
