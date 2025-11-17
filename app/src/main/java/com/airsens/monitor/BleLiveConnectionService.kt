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
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.airsens.monitor.database.AppDatabase
import com.airsens.monitor.database.MeasurementEntity
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * Foreground service for maintaining persistent BLE connection when app is open
 * Subscribes to MEASUREMENT characteristic (0xAAA1) for real-time data
 */
class BleLiveConnectionService : Service() {

    companion object {
        private const val TAG = "BleLiveConnectionService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "ble_live_channel"

        private const val SCAN_TIMEOUT = 10000L
        private const val RECONNECT_DELAY = 5000L

        val SERVICE_UUID: UUID = UUID.fromString("0000AAAA-0000-1000-8000-00805F9B34FB")
        val MEASUREMENT_UUID: UUID = UUID.fromString("0000AAA1-0000-1000-8000-00805F9B34FB")
        val TIME_SYNC_UUID: UUID = UUID.fromString("0000AAA2-0000-1000-8000-00805F9B34FB")
        val TIME_REQUEST_UUID: UUID = UUID.fromString("0000AAA4-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEVICE_NAME = "BMV080"

        const val ACTION_START = "com.airsens.monitor.START_LIVE_SERVICE"
        const val ACTION_STOP = "com.airsens.monitor.STOP_LIVE_SERVICE"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var database: AppDatabase

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var currentGatt: BluetoothGatt? = null

    private var isRunning = false
    private var shouldReconnect = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Live connection service created")

        database = AppDatabase.getDatabase(applicationContext)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                    isRunning = true
                    shouldReconnect = true
                    Log.i(TAG, "▶ Live connection service started")
                    serviceScope.launch {
                        connectAndMaintain()
                    }
                }
            }
            ACTION_STOP -> {
                shouldReconnect = false
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        currentGatt?.disconnect()
        currentGatt?.close()
        currentGatt = null
        serviceScope.cancel()
        Log.i(TAG, "⏹ Live connection service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Air Quality Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent BLE connection for real-time air quality data"
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
            .setContentTitle("AirSens Monitor (Live)")
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

    private suspend fun connectAndMaintain() {
        while (shouldReconnect && isRunning) {
            try {
                updateNotification("Scanning for device...")
                Log.d(TAG, "Scanning for device...")

                // 1. Scan for device
                val device = scanForDevice()
                if (device == null) {
                    Log.w(TAG, "Device not found, retrying in ${RECONNECT_DELAY}ms")
                    delay(RECONNECT_DELAY)
                    continue
                }

                updateNotification("Connecting...")
                Log.i(TAG, "Found device: ${device.address}")

                // 2. Connect and subscribe to live measurements
                val gatt = connectToDevice(device)
                if (gatt == null) {
                    Log.w(TAG, "Connection failed, retrying in ${RECONNECT_DELAY}ms")
                    delay(RECONNECT_DELAY)
                    continue
                }

                // Connection successful - stay connected until disconnected
                updateNotification("Connected - Live monitoring")
                Log.i(TAG, "✓ Connected successfully - monitoring live data")

                // Wait for disconnection (connection is maintained in GATT callback)
                // This coroutine will continue running until shouldReconnect becomes false
                // or the connection is lost
                waitForDisconnection()

            } catch (e: CancellationException) {
                Log.i(TAG, "Connection cancelled")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in live connection", e)
                updateNotification("Connection error, retrying...")
                delay(RECONNECT_DELAY)
            }
        }

        Log.i(TAG, "Live connection loop ended")
    }

    private suspend fun scanForDevice(): BluetoothDevice? = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No BLUETOOTH_SCAN permission")
            continuation.resume(null) {}
            return@suspendCancellableCoroutine
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Fast scanning for live mode
            .build()

        val scanFilter = ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d(TAG, "Device found: ${result.device.address}")
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(result.device) {}
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(null) {}
                }
            }
        }

        bluetoothLeScanner?.startScan(listOf(scanFilter), settings, callback)

        // Timeout
        handler.postDelayed({
            bluetoothLeScanner?.stopScan(callback)
            if (continuation.isActive) {
                Log.d(TAG, "Scan timeout")
                continuation.resume(null) {}
            }
        }, SCAN_TIMEOUT)

        continuation.invokeOnCancellation {
            bluetoothLeScanner?.stopScan(callback)
        }
    }

    private var disconnectionLatch: CompletableDeferred<Unit>? = null

    private suspend fun connectToDevice(device: BluetoothDevice): BluetoothGatt? = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(null) {}
            return@suspendCancellableCoroutine
        }

        disconnectionLatch = CompletableDeferred()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "BLE connected, discovering services...")
                        if (ActivityCompat.checkSelfPermission(
                                this@BleLiveConnectionService,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            gatt.discoverServices()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "BLE disconnected")
                        disconnectionLatch?.complete(Unit)
                        if (shouldReconnect) {
                            Log.i(TAG, "Connection lost, will attempt reconnect")
                            updateNotification("Disconnected, reconnecting...")
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered, subscribing to live measurements...")

                    // Enable time request indications
                    enableTimeRequestIndications(gatt)

                    // Send initial time sync
                    serviceScope.launch {
                        delay(300)
                        sendTimeSync(gatt)
                    }

                    // Subscribe to live measurements
                    serviceScope.launch {
                        delay(500)
                        val success = enableMeasurementNotifications(gatt)
                        if (success) {
                            Log.i(TAG, "✓ Subscribed to live MEASUREMENT notifications")
                            if (continuation.isActive) {
                                continuation.resume(gatt) {}
                            }
                        } else {
                            Log.e(TAG, "✗ Failed to subscribe to MEASUREMENT notifications")
                            gatt.disconnect()
                            if (continuation.isActive) {
                                continuation.resume(null) {}
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Service discovery failed: $status")
                    if (continuation.isActive) {
                        continuation.resume(null) {}
                    }
                }
            }

            // Handle notifications from ESP32
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                when (characteristic.uuid) {
                    TIME_REQUEST_UUID -> {
                        Log.i(TAG, "ESP32 requesting time sync")
                        serviceScope.launch {
                            sendTimeSync(gatt)
                        }
                    }
                    MEASUREMENT_UUID -> {
                        Log.d(TAG, "Live measurement received: ${value.size} bytes")
                        handleLiveMeasurement(value)
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                when (characteristic.uuid) {
                    TIME_REQUEST_UUID -> {
                        Log.i(TAG, "ESP32 requesting time sync (deprecated)")
                        serviceScope.launch {
                            sendTimeSync(gatt)
                        }
                    }
                    MEASUREMENT_UUID -> {
                        val value = characteristic.value
                        if (value != null) {
                            Log.d(TAG, "Live measurement received (deprecated): ${value.size} bytes")
                            handleLiveMeasurement(value)
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Connecting to device...")
        val gatt = device.connectGatt(this, false, callback)
        currentGatt = gatt

        continuation.invokeOnCancellation {
            Log.d(TAG, "Connection cancelled")
            gatt.close()
            currentGatt = null
        }
    }

    private suspend fun waitForDisconnection() {
        disconnectionLatch?.await()
        disconnectionLatch = null
        currentGatt?.close()
        currentGatt = null
    }

    private suspend fun enableMeasurementNotifications(gatt: BluetoothGatt): Boolean = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        val service = gatt.getService(SERVICE_UUID)
        val measurementChar = service?.getCharacteristic(MEASUREMENT_UUID)

        if (measurementChar == null) {
            Log.w(TAG, "MEASUREMENT characteristic not found")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Enable local notifications
        val success = gatt.setCharacteristicNotification(measurementChar, true)
        if (!success) {
            Log.e(TAG, "Failed to set characteristic notification for MEASUREMENT")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Enable notifications on remote device via CCCD
        val descriptor = measurementChar.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "CCCD descriptor not found for MEASUREMENT characteristic")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val writeSuccess = gatt.writeDescriptor(descriptor)

        if (writeSuccess) {
            Log.i(TAG, "MEASUREMENT CCCD write initiated")
            // Assume success - we'll know if notifications don't arrive
            handler.postDelayed({
                if (continuation.isActive) {
                    continuation.resume(true) {}
                }
            }, 500)
        } else {
            Log.e(TAG, "Failed to write MEASUREMENT CCCD")
            continuation.resume(false) {}
        }
    }

    private fun enableTimeRequestIndications(gatt: BluetoothGatt) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        val timeRequestChar = service?.getCharacteristic(TIME_REQUEST_UUID)

        if (timeRequestChar == null) {
            Log.w(TAG, "TIME_REQUEST characteristic not found")
            return
        }

        gatt.setCharacteristicNotification(timeRequestChar, true)

        val descriptor = timeRequestChar.getDescriptor(CCCD_UUID)
        descriptor?.let {
            it.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gatt.writeDescriptor(it)
            Log.i(TAG, "TIME_REQUEST indications enabled")
        }
    }

    private suspend fun sendTimeSync(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(TIME_SYNC_UUID)

        if (characteristic == null) {
            Log.w(TAG, "TIME_SYNC characteristic not found")
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
        val timeBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(currentTime.toInt())
            .array()

        characteristic.value = timeBytes
        gatt.writeCharacteristic(characteristic)
        Log.i(TAG, "Sent time sync: $currentTime")
    }

    private fun handleLiveMeasurement(data: ByteArray) {
        val measurement = parseMeasurementData(data) ?: run {
            Log.w(TAG, "Failed to parse live measurement")
            return
        }

        Log.i(TAG, "Live measurement: PM2.5=${measurement.pm25}, Temp=${measurement.temperature}")

        // Save to database
        serviceScope.launch {
            try {
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
                database.measurementDao().keepOnlyLast(500)

                updateNotification("Connected - Last PM2.5: ${String.format("%.1f", measurement.pm25)} µg/m³")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving measurement", e)
            }
        }
    }

    private fun parseMeasurementData(data: ByteArray): AirQualitySensorClient.MeasurementData? {
        if (data.size < 18) {
            Log.w(TAG, "Measurement data too short: ${data.size} bytes")
            return null
        }

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Read sensor mask
            val sensorMask = buffer.get(0).toInt() and 0xFF
            val hasBME690 = (sensorMask and 0x02) != 0

            // Read timestamp
            val timestamp = buffer.getInt(1).toLong() and 0xFFFFFFFFL

            // Read PM values
            val pm10 = buffer.getFloat(5)
            val pm25 = buffer.getFloat(9)
            val pm1 = buffer.getFloat(13)

            // Read flags
            val flags = buffer.get(17).toInt() and 0xFF
            val obstructed = (flags and 0x01) != 0
            val timeValid = (flags and 0x02) != 0
            val iaqAccuracy = (flags shr 2) and 0x03

            // Read environmental data if available
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
}
