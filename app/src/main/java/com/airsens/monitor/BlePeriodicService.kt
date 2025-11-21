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

/**
 * Parser for simplified bulk data transfer
 * Each notification contains one complete measurement record (20 bytes)
 * No reassembly or fragmentation needed
 */
class BulkDataParser {
    private val TAG = "BulkDataParser"
    private var recordCount = 0

    /**
     * Parse a packet containing a complete measurement record
     * ESP32 sends raw measurement_record_t (69 bytes with gas profile data)
     * @return Pair of (recordIndex, totalRecords) - estimated based on packet count
     */
    fun parsePacket(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 40) {
            Log.e(TAG, "Packet too small: ${data.size} bytes (need at least 40 for measurement_record_t)")
            return null
        }

        try {
            // Since ESP32 doesn't send record index/total, we track them locally
            // The packet is just the raw measurement_record_t struct (40 or 69 bytes)
            val recordIndex = recordCount
            recordCount++

            Log.d(TAG, "Parsing record $recordIndex (packet size: ${data.size} bytes)")

            // Return dummy total_records (we won't know until transfer ends)
            return Pair(recordIndex, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing packet", e)
            return null
        }
    }

    /**
     * Parse a complete measurement from a packet
     * ESP32 measurement_record_t structure (89 bytes with extended gas profile):
     * [0-3]: timestamp (uint32_t)
     * [4-7]: pm10 (float)
     * [8-11]: pm25 (float)
     * [12-15]: pm1 (float)
     * [16]: obstructed (uint8_t)
     * [17]: time_valid (uint8_t)
     * [18]: iaq_accuracy (uint8_t)
     * [19]: reserved1 (uint8_t)
     * [20-23]: temperature (float)
     * [24-27]: humidity (float)
     * [28-31]: pressure (float)
     * [32-35]: iaq (float)
     * [36-39]: gas_resistance (float)
     * [40]: gas_resistance_profile (uint8_t)
     * [41]: reserved2 (uint8_t)
     * [42-81]: gas_resistance_array[10] (int32_t[10]) - 4 bytes per element
     */
    fun parseMeasurement(data: ByteArray): AirQualitySensorClient.MeasurementData? {
        if (data.size < 40) {
            Log.e(TAG, "Packet too small: ${data.size} bytes (need at least 40 for measurement_record_t)")
            return null
        }

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Parse core measurement fields (same as before)
            val timestamp = buffer.getInt(0).toLong() and 0xFFFFFFFFL
            val pm10 = buffer.getFloat(4)
            val pm25 = buffer.getFloat(8)
            val pm1 = buffer.getFloat(12)
            val obstructed = buffer.get(16).toInt() != 0
            val timeValid = buffer.get(17).toInt() != 0
            val iaqAccuracy = buffer.get(18).toInt() and 0xFF
            val temperature = buffer.getFloat(20)
            val humidity = buffer.getFloat(24)
            val pressure = buffer.getFloat(28)
            val iaq = buffer.getFloat(32)
            val gasResistance = buffer.getFloat(36)

            // Parse gas profile data if packet is large enough
            var gasResistanceProfile = 0
            var gasResistanceArray: IntArray? = null

            if (data.size >= 42) {
                gasResistanceProfile = buffer.get(40).toInt() and 0xFF

                // Parse gas resistance array if profile index > 0
                // Array is now 10 int32_t values (40 bytes total: 42 to 81)
                if (gasResistanceProfile > 0 && data.size >= 82) {
                    try {
                        gasResistanceArray = IntArray(10)

                        for (i in 0 until 10) {
                            val offset = 42 + (i * 4)  // 4 bytes per int32_t
                            if (offset + 3 < data.size) {
                                gasResistanceArray[i] = buffer.getInt(offset)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse gas resistance array", e)
                    }
                }
            }

            Log.d(TAG, "Parsed measurement: ts=$timestamp, PM1=$pm1, PM2.5=$pm25, PM10=$pm10, temp=$temperature°C" +
                (if (gasResistanceProfile > 0) ", gas_profile=$gasResistanceProfile" else ""))

            return AirQualitySensorClient.MeasurementData(
                timestamp = timestamp,
                pm1 = pm1,
                pm25 = pm25,
                pm10 = pm10,
                obstructed = obstructed,
                timeValid = timeValid,
                temperature = if (!temperature.isNaN() && temperature in -50f..100f) temperature else null,
                humidity = if (!humidity.isNaN() && humidity in 0f..100f) humidity else null,
                pressure = if (!pressure.isNaN() && pressure > 0) pressure else null,
                iaq = if (!iaq.isNaN() && iaq >= 0) iaq else null,
                gasResistance = if (!gasResistance.isNaN() && gasResistance > 0) gasResistance else null,
                iaqAccuracy = iaqAccuracy,
                gasResistanceProfile = gasResistanceProfile,
                gasResistanceArray = gasResistanceArray
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing measurement", e)
            return null
        }
    }

    /**
     * Reset parser state for next transfer
     */
    fun reset() {
        recordCount = 0
        Log.d(TAG, "Parser reset")
    }
}

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
        val TIME_REQUEST_UUID: UUID = UUID.fromString("0000AAA4-0000-1000-8000-00805F9B34FB")
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

    // Bulk data sync state (simplified single-packet format)
    private val bulkDataParser = BulkDataParser()
    private var expectedTotalRecords = 0
    private var receivedRecordCount = 0
    private val accumulatedMeasurements = mutableListOf<AirQualitySensorClient.MeasurementData>()
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

                // 7. Proactively send time sync to ESP32
                // Even though time sync is indication-based, we send it proactively during
                // periodic connections to ensure ESP32 stays synchronized
                Log.d(TAG, "Sending proactive time sync to ESP32")
                sendTimeSync(gatt)

            } finally {
                // 8. Disconnect and cleanup
                disconnectAndCleanup(gatt)
            }

        } catch (e: CancellationException) {
            // Connection was cancelled (device disconnected before service discovery)
            Log.w(TAG, "Connection cancelled: ${e.message}")
            updateNotification("Connection failed")
            currentGatt?.let { gatt ->
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    gatt.disconnect()
                }
                gatt.close()
            }
            currentGatt = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in periodic connection", e)
            updateNotification("Error: ${e.message}")
            currentGatt?.let { gatt ->
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    gatt.disconnect()
                }
                gatt.close()
            }
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

        // Declare callback and timeout runnable - need lateinit to avoid forward reference
        lateinit var callback: ScanCallback
        lateinit var timeoutRunnable: Runnable

        timeoutRunnable = Runnable {
            bluetoothLeScanner?.stopScan(callback)
            if (continuation.isActive) {
                Log.d(TAG, "BLE scan timeout reached")
                continuation.resume(null) {}
            }
        }

        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d(TAG, "Device found in scan: ${result.device.address}")
                // Filter already matched, so we can directly use this device
                handler.removeCallbacks(timeoutRunnable) // Cancel timeout
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(result.device) {}
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                handler.removeCallbacks(timeoutRunnable) // Cancel timeout
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(null) {}
                }
            }
        }

        // Start scan with filter - this tells Android we're looking for a specific device
        bluetoothLeScanner?.startScan(scanFilters, settings, callback)

        // Timeout after scanTimeout (varies based on screen state)
        handler.postDelayed(timeoutRunnable, scanTimeout)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
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

                    // Enable indications for time request characteristic (ESP32 will request time sync)
                    // Launch coroutine to call suspend function
                    serviceScope.launch {
                        val success = enableTimeRequestIndications(gatt)
                        if (!success) {
                            Log.w(TAG, "Failed to enable TIME_REQUEST indications, but continuing anyway")
                        }
                    }

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
                    TIME_REQUEST_UUID -> {
                        Log.i(TAG, "ESP32 requesting time sync via indication on TIME_REQUEST")
                        serviceScope.launch {
                            sendTimeSync(gatt)
                        }
                    }
                    DATA_RESPONSE_UUID -> {
                        // Bulk data notification received (no ACK required)
                        Log.d(TAG, ">>> NOTIFICATION RECEIVED: ${value.size} bytes")
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
                    TIME_REQUEST_UUID -> {
                        Log.i(TAG, "ESP32 requesting time sync via indication on TIME_REQUEST (deprecated)")
                        serviceScope.launch {
                            sendTimeSync(gatt)
                        }
                    }
                    DATA_RESPONSE_UUID -> {
                        // Bulk data notification received (no ACK required)
                        val value = characteristic.value
                        Log.d(TAG, ">>> NOTIFICATION RECEIVED (deprecated): ${value?.size ?: 0} bytes")
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
            Log.e(TAG, "connectGatt() returned null - BLE stack unavailable")
            if (continuation.isActive) {
                continuation.cancel()
            }
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

        // Create timeout runnable so we can cancel it when read completes
        val timeoutRunnable = Runnable {
            if (continuation.isActive) {
                onCharacteristicReadCallback = null
                continuation.resume(0) {}
            }
        }

        onCharacteristicReadCallback = { value, status ->
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            if (status == BluetoothGatt.GATT_SUCCESS && continuation.isActive && value != null) {
                val count = value.getOrNull(0)?.toInt() ?: 0
                continuation.resume(count and 0xFF) {}
            } else if (continuation.isActive) {
                continuation.resume(0) {}
            }
            onCharacteristicReadCallback = null
        }

        gatt.readCharacteristic(characteristic)

        // Timeout after 3 seconds
        handler.postDelayed(timeoutRunnable, 3000)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
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

        // Create timeout runnable so we can cancel it when read completes
        val timeoutRunnable = Runnable {
            if (continuation.isActive) {
                onCharacteristicReadCallback = null
                continuation.resume(null) {}
            }
        }

        onCharacteristicReadCallback = { value, status ->
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
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

        // Timeout after 3 seconds
        handler.postDelayed(timeoutRunnable, 3000)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
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
     * Enable notifications on DATA_RESPONSE characteristic (0xAAA6)
     * Must be called BEFORE requesting bulk data
     * Using notifications (not indications) for faster transfer without ACKs
     */
    private suspend fun enableDataResponseNotifications(gatt: BluetoothGatt): Boolean = suspendCancellableCoroutine { continuation ->
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

        // Create timeout runnable so we can cancel it when write completes
        val timeoutRunnable = Runnable {
            if (continuation.isActive) {
                Log.e(TAG, "DATA_RESPONSE CCCD descriptor write timeout (5s)")
                onDescriptorWriteCallback = null
                continuation.resume(false) {}
            }
        }

        // Set up callback for descriptor write completion
        onDescriptorWriteCallback = { success ->
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            if (success) {
                Log.i(TAG, "✓ DATA_RESPONSE CCCD write SUCCESS - Notifications enabled on ESP32")
                Log.i(TAG, "  ESP32 can now send notifications (no ACK required)")
            } else {
                Log.e(TAG, "✗ DATA_RESPONSE CCCD write FAILED - Notifications NOT enabled")
            }
            continuation.resume(success) {}
            onDescriptorWriteCallback = null // Clear callback
        }

        // Write to CCCD to enable notifications (not indications)
        // Notifications don't require ACKs, making them faster and simpler
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val writeSuccess = gatt.writeDescriptor(descriptor)

        if (!writeSuccess) {
            Log.e(TAG, "Failed to initiate DATA_RESPONSE CCCD descriptor write")
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            onDescriptorWriteCallback = null
            continuation.resume(false) {}
        } else {
            Log.d(TAG, "DATA_RESPONSE CCCD descriptor write initiated, waiting for callback...")
            // Timeout after 5 seconds
            handler.postDelayed(timeoutRunnable, 5000)
        }

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
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

        Log.i(TAG, ">>> REQUESTING BULK DATA from ESP32")
        Log.d(TAG, "    Range: startTime=$startTime, endTime=$endTime, maxRecords=$maxRecords")
        Log.d(TAG, "    Request bytes (hex): ${requestData.joinToString(" ") { "%02X".format(it) }}")
        Log.d(TAG, "    ESP32 should respond with notifications on DATA_RESPONSE characteristic")

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
     * Parse a data packet received via indication on 0xAAA6
     * New format: Each measurement = 3 packets × 20 bytes
     * Returns pair of (packetIndex, totalPackets) or null if parse error
     */
    private fun parseDataPacket(data: ByteArray): Pair<Int, Int>? {
        // Log raw packet data for debugging
        Log.d(TAG, "Raw packet (${data.size} bytes): ${data.joinToString(" ") { "%02X".format(it) }}")

        // Delegate to BulkDataParser
        return bulkDataParser.parsePacket(data)
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
     * Handle a bulk data packet received via notification
     * New simplified format: Each notification = one complete measurement record (20 bytes)
     * No reassembly needed - each packet is a complete measurement
     */
    private fun handleBulkDataChunk(data: ByteArray) {
        val parsed = bulkDataParser.parsePacket(data) ?: run {
            Log.e(TAG, "Failed to parse data packet")
            return
        }

        val (recordIndex, totalRecordsFromPacket) = parsed

        // Initialize on first record (recordIndex == 0)
        if (recordIndex == 0 && totalRecordsFromPacket > 0) {
            Log.i(TAG, "Starting bulk data transfer: expecting $totalRecordsFromPacket records")
            expectedTotalRecords = totalRecordsFromPacket
            receivedRecordCount = 0
            accumulatedMeasurements.clear()
        }

        // Parse the complete measurement from this single packet
        val measurement = bulkDataParser.parseMeasurement(data)
        if (measurement != null) {
            receivedRecordCount++
            accumulatedMeasurements.add(measurement)

            // Log progress
            if (expectedTotalRecords > 0) {
                val progress = (receivedRecordCount * 100) / expectedTotalRecords
                Log.d(TAG, "Progress: $progress% (record $receivedRecordCount/$expectedTotalRecords)")
            }
        } else {
            Log.e(TAG, "Failed to parse measurement from packet")
        }

        // Check if transfer is complete
        if (expectedTotalRecords > 0 && receivedRecordCount >= expectedTotalRecords) {
            Log.i(TAG, "Bulk transfer complete! Received all $expectedTotalRecords records")
            Log.d(TAG, "Total measurements received: ${accumulatedMeasurements.size}")

            // Trigger completion callback with all accumulated measurements
            val callback = bulkSyncCompletionCallback
            if (callback != null) {
                callback(accumulatedMeasurements.toList()) // Make a copy
                bulkSyncCompletionCallback = null
            } else {
                Log.w(TAG, "Bulk data transfer completed but no callback registered")
            }

            // Reset state for next transfer
            bulkDataParser.reset()
            accumulatedMeasurements.clear()
            expectedTotalRecords = 0
            receivedRecordCount = 0
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
                accumulatedMeasurements.clear()
                bulkDataParser.reset()
                expectedTotalRecords = 0
                receivedRecordCount = 0

                // Set up completion callback
                bulkSyncCompletionCallback = { measurements ->
                    Log.d(TAG, "Bulk sync callback triggered with ${measurements.size} measurements")
                    continuation.resume(measurements) {}
                }

                // Enable notifications
                serviceScope.launch {
                    val notificationsEnabled = enableDataResponseNotifications(gatt)
                    if (!notificationsEnabled) {
                        Log.e(TAG, "Failed to enable DATA_RESPONSE notifications")
                        bulkSyncCompletionCallback = null
                        continuation.resume(emptyList()) {}
                        return@launch
                    }

                    // Delay after CCCD write to ensure ESP32 processes the write before we request data
                    // ESP32 needs time to update its CCCD state before sending notifications
                    delay(500)

                    // Request bulk data
                    val requestSent = requestBulkData(gatt, startTime, endTime, maxRecords)
                    if (!requestSent) {
                        Log.e(TAG, "Failed to send bulk data request")
                        bulkSyncCompletionCallback = null
                        continuation.resume(emptyList()) {}
                        return@launch
                    }

                    Log.d(TAG, "Bulk data request sent, waiting for packets...")

                    // If no data available, ESP32 won't send any indications
                    // Set a timeout to detect this case
                    serviceScope.launch {
                        delay(5000) // 5 second timeout for first packet
                        if (expectedTotalRecords == 0 && accumulatedMeasurements.isEmpty()) {
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

    private suspend fun enableTimeRequestIndications(gatt: BluetoothGatt): Boolean = suspendCancellableCoroutine { continuation ->
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
        val timeRequestChar = service?.getCharacteristic(TIME_REQUEST_UUID)

        if (timeRequestChar == null) {
            Log.w(TAG, "Time request characteristic (0xAAA4) not found, cannot enable indications")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Enable local notifications/indications
        val success = gatt.setCharacteristicNotification(timeRequestChar, true)
        if (!success) {
            Log.e(TAG, "Failed to set characteristic notification for time request")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Enable indications on the remote device by writing to CCCD
        val descriptor = timeRequestChar.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "CCCD descriptor not found for TIME_REQUEST characteristic")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Create timeout runnable so we can cancel it when write completes
        val timeoutRunnable = Runnable {
            if (continuation.isActive) {
                Log.e(TAG, "TIME_REQUEST CCCD descriptor write timeout (5s)")
                onDescriptorWriteCallback = null
                continuation.resume(false) {}
            }
        }

        // Set up callback for descriptor write completion
        onDescriptorWriteCallback = { writeSuccess ->
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            if (writeSuccess) {
                Log.i(TAG, "✓ TIME_REQUEST CCCD write SUCCESS - Indications enabled on ESP32")
            } else {
                Log.e(TAG, "✗ TIME_REQUEST CCCD write FAILED - Indications NOT enabled")
            }
            continuation.resume(writeSuccess) {}
            onDescriptorWriteCallback = null // Clear callback
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val writeSuccess = gatt.writeDescriptor(descriptor)

        if (!writeSuccess) {
            Log.e(TAG, "Failed to initiate TIME_REQUEST CCCD descriptor write")
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            onDescriptorWriteCallback = null
            continuation.resume(false) {}
        } else {
            Log.i(TAG, "TIME_REQUEST CCCD descriptor write initiated, waiting for callback...")
            // Timeout after 5 seconds
            handler.postDelayed(timeoutRunnable, 5000)
        }

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
        }
    }

    private suspend fun sendTimeSync(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        // Write timestamp to TIME_SYNC characteristic (0xAAA2), not TIME_REQUEST
        val characteristic = service?.getCharacteristic(TIME_SYNC_UUID) ?: run {
            Log.w(TAG, "TIME_SYNC characteristic (0xAAA2) not found")
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val currentTime = System.currentTimeMillis() / 1000
        Log.i(TAG, "Sending time sync to TIME_SYNC (0xAAA2): $currentTime (${java.util.Date(currentTime * 1000)})")

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
