package com.airsens.monitor

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
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

class BlePeriodicService : Service() {

    companion object {
        private const val TAG = "BlePeriodicService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ble_periodic_channel"

        private const val SCAN_TIMEOUT = 2000L           // 2 seconds max scan
        private const val CONNECTION_INTERVAL = 60000L   // 60 seconds between connections
        private const val CONNECTION_TIMEOUT = 5000L     // 5 seconds max connection time

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

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var currentGatt: BluetoothGatt? = null

    private var timeSyncCounter = 0
    private var isRunning = false

    // Callbacks for GATT operations
    private var onCharacteristicReadCallback: ((ByteArray?, Int) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

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
                // 3. Read measurement count
                val count = readMeasurementCount(gatt)
                Log.d(TAG, "Measurement count: $count")

                if (count > 0) {
                    // 4. Read measurement data
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
                    }
                }

                // 6. Time sync every 2nd connection (120 seconds)
                timeSyncCounter++
                if (timeSyncCounter >= 2) {
                    sendTimeSync(gatt)
                    timeSyncCounter = 0
                    Log.d(TAG, "Time sync sent")
                }

            } finally {
                // 7. Disconnect
                gatt.disconnect()
                gatt.close()
                currentGatt = null
                Log.d(TAG, "Disconnected")
            }

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

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (ActivityCompat.checkSelfPermission(
                        this@BlePeriodicService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val name = device.name ?: ""
                    if (name.contains(DEVICE_NAME, ignoreCase = true)) {
                        bluetoothLeScanner?.stopScan(this)
                        if (continuation.isActive) {
                            continuation.resume(device) {}
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(null) {}
                }
            }
        }

        bluetoothLeScanner?.startScan(null, settings, callback)

        // Timeout after SCAN_TIMEOUT
        handler.postDelayed({
            bluetoothLeScanner?.stopScan(callback)
            if (continuation.isActive) {
                continuation.resume(null) {}
            }
        }, SCAN_TIMEOUT)

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
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (ActivityCompat.checkSelfPermission(
                            this@BlePeriodicService,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (continuation.isActive) {
                        continuation.cancel()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && continuation.isActive) {
                    continuation.resume(gatt) {}
                } else if (continuation.isActive) {
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
        }

        val gatt = device.connectGatt(this, false, callback)
        currentGatt = gatt

        continuation.invokeOnCancellation {
            gatt.close()
        }
    }

    private suspend fun readMeasurementCount(gatt: BluetoothGatt): Int = suspendCancellableCoroutine { continuation ->
        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(MEASUREMENT_COUNT_UUID)

        if (characteristic == null) {
            continuation.resume(0) {}
            return@suspendCancellableCoroutine
        }

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

        if (data.size < 42) {  // Minimum size needed
            Log.w(TAG, "Measurement data too short: ${data.size} bytes (need at least 42)")
            return null
        }

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Read timestamp (4 bytes, uint32)
            val timestamp = buffer.getInt(0).toLong() and 0xFFFFFFFFL
            Log.d(TAG, "Timestamp: $timestamp")

            // Read PM values as uint16 (2 bytes each, not floats!)
            // Positions: 4-5, 8-9, 12-13
            val pm1 = (buffer.getShort(4).toInt() and 0xFFFF).toFloat()
            val pm25 = (buffer.getShort(8).toInt() and 0xFFFF).toFloat()
            val pm10 = (buffer.getShort(12).toInt() and 0xFFFF).toFloat()
            Log.d(TAG, "PM values - PM1.0: $pm1, PM2.5: $pm25, PM10: $pm10")

            // Read flags (1 byte at offset 16)
            val flags = buffer.get(16).toInt() and 0xFF
            val obstructed = (flags and 0x01) != 0
            val timeValid = (flags and 0x02) != 0
            Log.d(TAG, "Flags: 0x${"%02X".format(flags)} - obstructed=$obstructed, timeValid=$timeValid")

            // Environmental data starts at offset 22 (not 17!)
            // Order: Temperature, Humidity, Pressure, IAQ, Gas Resistance
            val temperature = buffer.getFloat(22)
            val humidity = buffer.getFloat(26)
            val pressure = buffer.getFloat(30)
            val iaq = buffer.getFloat(34)
            val gasResistance = buffer.getFloat(38)

            Log.d(TAG, "Temperature: $temperature°C")
            Log.d(TAG, "Humidity: $humidity%")
            Log.d(TAG, "Pressure: $pressure Pa (${pressure/100} hPa)")
            Log.d(TAG, "IAQ: $iaq")
            Log.d(TAG, "Gas Resistance: $gasResistance Ω")

            // IAQ accuracy might be at offset 42 if available
            val iaqAccuracy = if (data.size > 42) buffer.get(42).toInt() and 0xFF else 0
            Log.d(TAG, "IAQ Accuracy: $iaqAccuracy")

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
        val timeBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(currentTime.toInt())
            .array()

        characteristic.value = timeBytes
        gatt.writeCharacteristic(characteristic)
    }
}
