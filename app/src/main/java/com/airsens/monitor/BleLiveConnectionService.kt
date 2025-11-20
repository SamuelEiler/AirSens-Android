package com.airsens.monitor

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Foreground service for maintaining persistent BLE connection (industry standard)
 * - Always connected (app open or closed) with auto-reconnect
 * - Subscribes to MEASUREMENT characteristic (0xAAA1) for real-time data every 30s
 * - Subscribes to DATA_RESPONSE characteristic (0xAAA6) for bulk data sync
 * - Dynamic connection intervals (power-optimized by ESP32 firmware)
 * - Instant data access when app opens
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
        val DATA_REQUEST_UUID: UUID = UUID.fromString("0000AAA5-0000-1000-8000-00805F9B34FB")
        val DATA_RESPONSE_UUID: UUID = UUID.fromString("0000AAA6-0000-1000-8000-00805F9B34FB")
        val DELETE_REQUEST_UUID: UUID = UUID.fromString("0000AAA7-0000-1000-8000-00805F9B34FB")
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

    // AutoConnect state for persistent reconnection (industry standard for wearables)
    private var connectedDevice: BluetoothDevice? = null
    private var useAutoConnect = false  // Switch to true after first successful connection

    // Callbacks for GATT operations
    private var onDescriptorWriteCallback: ((Boolean) -> Unit)? = null

    // Bulk data sync state
    private val bulkDataParser = BulkDataParser()
    private var expectedTotalPackets = 0
    private val accumulatedMeasurements = mutableListOf<AirQualitySensorClient.MeasurementData>()

    // Connection health monitoring
    private var lastDataReceivedTime = 0L
    private val connectionHealthCheckInterval = 60000L // Check every 60 seconds
    private val connectionTimeoutMs = 120000L // 2 minutes without data = dead connection
    private var healthCheckJob: Job? = null

    // Bluetooth state receiver for handling BT on/off
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth turned ON - restarting connection")
                        if (shouldReconnect && connectedDevice != null) {
                            serviceScope.launch {
                                delay(500) // Give BT stack time to stabilize
                                reconnectToKnownDevice()
                            }
                        }
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.w(TAG, "Bluetooth turned OFF")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "========================================")
        Log.i(TAG, "📱 LIVE CONNECTION SERVICE CREATED")
        Log.i(TAG, "========================================")

        database = AppDatabase.getDatabase(applicationContext)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        // Register Bluetooth state receiver for auto-reconnect on BT toggle
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
        Log.d(TAG, "Registered Bluetooth state receiver")

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                    isRunning = true
                    shouldReconnect = true
                    Log.i(TAG, "========================================")
                    Log.i(TAG, "▶️ PERSISTENT CONNECTION ACTIVATED")
                    Log.i(TAG, "   Maintaining always-on BLE connection")
                    Log.i(TAG, "   Auto-reconnect: enabled")
                    Log.i(TAG, "   Health monitoring: enabled")
                    Log.i(TAG, "========================================")
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
        useAutoConnect = false
        stopConnectionHealthCheck()
        handler.removeCallbacksAndMessages(null)

        // Unregister Bluetooth state receiver
        try {
            unregisterReceiver(bluetoothStateReceiver)
            Log.d(TAG, "Unregistered Bluetooth state receiver")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver: ${e.message}")
        }

        currentGatt?.disconnect()
        currentGatt?.close()
        currentGatt = null
        connectedDevice = null
        serviceScope.cancel()
        Log.i(TAG, "========================================")
        Log.i(TAG, "⏹ LIVE CONNECTION SERVICE STOPPED")
        Log.i(TAG, "========================================")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Air Quality Sensor Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains persistent connection to air quality sensor"
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private suspend fun connectAndMaintain() {
        while (shouldReconnect && isRunning) {
            try {
                // Industry-standard two-phase connection strategy:
                // 1. First connection: Fast direct connect after aggressive scan
                // 2. Subsequent connections: autoConnect=true (Android handles reconnection forever)

                if (connectedDevice != null && useAutoConnect) {
                    // We've connected before - use autoConnect for automatic reconnection
                    Log.i(TAG, "Using autoConnect mode for known device: ${connectedDevice!!.address}")

                    // Call reconnectToKnownDevice() only once to create the autoConnect GATT
                    val gatt = reconnectToKnownDevice()
                    if (gatt != null) {
                        Log.i(TAG, "AutoConnect GATT established - will reconnect automatically on disconnect")

                        // Loop: Just wait for disconnects - Android auto-reconnects with same GATT
                        while (shouldReconnect && isRunning && useAutoConnect) {
                            waitForDisconnection()
                            Log.i(TAG, "AutoConnect: Disconnected, waiting for automatic reconnection...")
                            updateNotification("Disconnected, auto-reconnecting...")
                        }
                    } else {
                        // AutoConnect failed - retry
                        Log.w(TAG, "AutoConnect failed, will retry")
                        useAutoConnect = false
                        connectedDevice = null
                        delay(RECONNECT_DELAY)
                    }
                } else {
                    // First connection - use fast direct connect
                    updateNotification("Scanning for device...")
                    Log.d(TAG, "First connection - scanning for device...")

                    // 1. Scan aggressively for device
                    val device = scanForDevice()
                    if (device == null) {
                        Log.w(TAG, "Device not found, retrying in ${RECONNECT_DELAY}ms")
                        delay(RECONNECT_DELAY)
                        continue
                    }

                    updateNotification("Connecting...")
                    Log.i(TAG, "Found device: ${device.address}")

                    // 2. Direct connect (autoConnect=false) for fast initial connection
                    val gatt = connectToDevice(device, autoConnect = false)
                    if (gatt == null) {
                        Log.w(TAG, "Connection failed, retrying in ${RECONNECT_DELAY}ms")
                        delay(RECONNECT_DELAY)
                        continue
                    }

                    // First connection successful!
                    connectedDevice = device
                    useAutoConnect = true  // Switch to autoConnect mode for future reconnections

                    updateNotification("Connected - Live monitoring")
                    Log.i(TAG, "========================================")
                    Log.i(TAG, "✅ FIRST CONNECTION ESTABLISHED")
                    Log.i(TAG, "   Device: ${device.address}")
                    Log.i(TAG, "   Switched to autoConnect mode")
                    Log.i(TAG, "   Will auto-reconnect on disconnect")
                    Log.i(TAG, "========================================")

                    // Wait for disconnection
                    waitForDisconnection()
                }

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

    /**
     * Reconnect to known device using autoConnect=true (industry standard for wearables)
     * This enables automatic reconnection that survives disconnects and never times out
     * Returns the GATT object if successful, null otherwise
     */
    private suspend fun reconnectToKnownDevice(): BluetoothGatt? {
        val device = connectedDevice ?: return null

        Log.i(TAG, "Reconnecting with autoConnect=true (no timeout, auto-reconnect forever)")
        updateNotification("Auto-reconnecting...")

        // Use autoConnect=true - Android will automatically connect whenever device is in range
        // No scan needed, no timeout, survives disconnections
        val gatt = connectToDevice(device, autoConnect = true)

        if (gatt != null) {
            Log.i(TAG, "AutoConnect initiated - will reconnect automatically")
            return gatt
        } else {
            Log.w(TAG, "Failed to initiate autoConnect")
            return null
        }
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

        // Declare callback and timeout runnable - need lateinit to avoid forward reference
        lateinit var callback: ScanCallback
        lateinit var timeoutRunnable: Runnable

        timeoutRunnable = Runnable {
            bluetoothLeScanner?.stopScan(callback)
            if (continuation.isActive) {
                Log.d(TAG, "Scan timeout")
                continuation.resume(null) {}
            }
        }

        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d(TAG, "Device found: ${result.device.address}")
                handler.removeCallbacks(timeoutRunnable) // Cancel timeout
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(result.device) {}
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                handler.removeCallbacks(timeoutRunnable) // Cancel timeout
                bluetoothLeScanner?.stopScan(this)
                if (continuation.isActive) {
                    continuation.resume(null) {}
                }
            }
        }

        bluetoothLeScanner?.startScan(listOf(scanFilter), settings, callback)

        // Timeout
        handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            bluetoothLeScanner?.stopScan(callback)
        }
    }

    private var disconnectionLatch: CompletableDeferred<Unit>? = null

    private suspend fun connectToDevice(device: BluetoothDevice, autoConnect: Boolean = false): BluetoothGatt? = suspendCancellableCoroutine { continuation ->
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
                        Log.i(TAG, "BLE connected, requesting MTU 256...")
                        if (ActivityCompat.checkSelfPermission(
                                this@BleLiveConnectionService,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            // Request larger MTU to receive full 38-byte live measurements
                            gatt.requestMtu(256)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "BLE disconnected (status=$status)")

                        // Handle disconnection based on connection mode
                        if (continuation.isActive) {
                            if (autoConnect) {
                                // With autoConnect, Android will automatically reconnect
                                // Don't cancel - let it keep trying in the background
                                Log.i(TAG, "AutoConnect mode: Disconnect detected, will auto-reconnect when device available")
                            } else {
                                // Direct connect mode: Disconnect before service discovery = failed connection
                                Log.w(TAG, "Direct connect mode: Disconnected during connection/discovery - cancelling attempt")
                                continuation.cancel()
                            }
                        }

                        // Complete the latch to signal disconnection
                        // Use a small delay to ensure all callbacks finish
                        serviceScope.launch {
                            delay(100)
                            disconnectionLatch?.complete(Unit)
                        }
                        if (shouldReconnect) {
                            Log.i(TAG, "Connection lost, will attempt reconnect")
                            updateNotification("Disconnected, reconnecting...")
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "✓ MTU changed to $mtu bytes (payload: ${mtu - 3} bytes)")
                } else {
                    Log.w(TAG, "⚠️ MTU change failed (status=$status), using default MTU 23")
                }

                // Discover services after MTU negotiation
                if (ActivityCompat.checkSelfPermission(
                        this@BleLiveConnectionService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered, setting up persistent connection...")

                    // Setup all characteristics sequentially to avoid GATT queue conflicts
                    serviceScope.launch {
                        // Step 1: Enable time request indications
                        val timeRequestSuccess = enableTimeRequestIndications(gatt)
                        if (!timeRequestSuccess) {
                            Log.w(TAG, "Failed to enable TIME_REQUEST indications, but continuing anyway")
                        }

                        // Step 2: Send initial time sync (wait for TIME_REQUEST to complete)
                        delay(500)
                        sendTimeSync(gatt)

                        // Step 3: Subscribe to live MEASUREMENT notifications (wait longer for GATT queue to clear)
                        delay(1000)
                        val measurementSuccess = enableMeasurementNotifications(gatt)
                        if (!measurementSuccess) {
                            Log.e(TAG, "✗ Failed to subscribe to MEASUREMENT notifications")
                            gatt.disconnect()
                            if (continuation.isActive) {
                                continuation.resume(null) {}
                            }
                            return@launch
                        }
                        Log.i(TAG, "✓ Subscribed to live MEASUREMENT notifications")

                        // Step 4: Subscribe to DATA_RESPONSE notifications (wait for MEASUREMENT to complete)
                        delay(500)
                        val dataResponseSuccess = enableDataResponseNotifications(gatt)
                        if (!dataResponseSuccess) {
                            Log.e(TAG, "✗ Failed to subscribe to DATA_RESPONSE notifications")
                            gatt.disconnect()
                            if (continuation.isActive) {
                                continuation.resume(null) {}
                            }
                            return@launch
                        }
                        Log.i(TAG, "✓ Subscribed to DATA_RESPONSE notifications")

                        // Connection fully established
                        if (continuation.isActive) {
                            continuation.resume(gatt) {}
                        }

                        // Step 5: Request initial bulk data sync after connection established
                        delay(500)
                        requestInitialBulkData(gatt)
                    }
                } else {
                    Log.e(TAG, "Service discovery failed: $status")
                    gatt.disconnect() // Trigger disconnect to clean up properly
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
                    DATA_RESPONSE_UUID -> {
                        Log.d(TAG, "Bulk data packet received: ${value.size} bytes")
                        handleBulkDataPacket(value, gatt)
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
                    DATA_RESPONSE_UUID -> {
                        val value = characteristic.value
                        if (value != null) {
                            Log.d(TAG, "Bulk data packet received (deprecated): ${value.size} bytes")
                            handleBulkDataPacket(value, gatt)
                        }
                    }
                }
            }

            // Handle descriptor write (for enabling notifications/indications)
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                Log.d(TAG, "onDescriptorWrite: descriptor=${descriptor.uuid}, status=$status")
                val success = status == BluetoothGatt.GATT_SUCCESS
                onDescriptorWriteCallback?.invoke(success)
            }
        }

        Log.i(TAG, "Connecting to device with autoConnect=$autoConnect")
        if (autoConnect) {
            Log.i(TAG, "  autoConnect=true: No timeout, will reconnect forever")
        } else {
            Log.i(TAG, "  autoConnect=false: 30s timeout, direct connect")
        }

        val gatt = device.connectGatt(this, autoConnect, callback)
        if (gatt == null) {
            Log.e(TAG, "connectGatt() returned null - BLE stack unavailable")
            disconnectionLatch = null // Clean up latch since connection failed
            if (continuation.isActive) {
                continuation.cancel()
            }
            return@suspendCancellableCoroutine
        }
        currentGatt = gatt

        continuation.invokeOnCancellation {
            Log.d(TAG, "Connection cancelled")
            disconnectionLatch?.complete(Unit)
            disconnectionLatch = null
            gatt.close()
            currentGatt = null
        }
    }

    private suspend fun waitForDisconnection() {
        // Create new latch for this disconnection wait
        disconnectionLatch = CompletableDeferred()

        try {
            // Start connection health monitoring
            startConnectionHealthCheck()

            // Wait for disconnection with timeout (if no disconnect event after 5 minutes of inactivity, force reconnect)
            withTimeoutOrNull(connectionTimeoutMs) {
                disconnectionLatch?.await()
            }
        } finally {
            // Stop health monitoring
            stopConnectionHealthCheck()

            disconnectionLatch = null

            // CRITICAL: When using autoConnect, DON'T close OR disconnect GATT!
            // Closing/disconnecting GATT breaks auto-reconnection.
            // Android will automatically reconnect the GATT object when device is in range.
            if (useAutoConnect) {
                Log.i(TAG, "AutoConnect mode: Waiting for Android to auto-reconnect (not calling disconnect)")
                // Do nothing - just wait for the next disconnect event
                // Android handles reconnection automatically with the same GATT object
                // DON'T set currentGatt = null - Android needs this object to reconnect
            } else {
                // Direct connect mode: Clean up GATT completely
                Log.d(TAG, "Direct connect mode: Disconnecting and closing GATT")
                currentGatt?.let { gatt ->
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        gatt.disconnect()
                        delay(200) // Give time for disconnect to complete
                    }
                    gatt.close()
                }
                currentGatt = null
            }
            Log.d(TAG, "Cleaned up GATT connection")
        }
    }

    /**
     * Start monitoring connection health
     * Forces reconnect if no data received within timeout
     */
    private fun startConnectionHealthCheck() {
        lastDataReceivedTime = System.currentTimeMillis()

        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            while (isActive && shouldReconnect) {
                delay(connectionHealthCheckInterval)

                val timeSinceLastData = System.currentTimeMillis() - lastDataReceivedTime

                if (timeSinceLastData > connectionTimeoutMs) {
                    Log.w(TAG, "⚠️ Connection appears dead (no data for ${timeSinceLastData}ms)")
                    Log.w(TAG, "   Forcing disconnect and reconnect...")

                    // Force disconnect to trigger reconnection
                    currentGatt?.let { gatt ->
                        if (ActivityCompat.checkSelfPermission(
                                this@BleLiveConnectionService,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            gatt.disconnect()
                        }
                    }

                    // Complete the latch to unblock waitForDisconnection
                    disconnectionLatch?.complete(Unit)
                    break
                }
            }
        }
    }

    /**
     * Stop connection health monitoring
     */
    private fun stopConnectionHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
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

        // Create timeout runnable so we can cancel it when write completes
        val timeoutRunnable = Runnable {
            if (continuation.isActive) {
                Log.e(TAG, "MEASUREMENT CCCD descriptor write timeout (5s)")
                onDescriptorWriteCallback = null
                continuation.resume(false) {}
            }
        }

        // Set up callback for descriptor write completion
        onDescriptorWriteCallback = { writeSuccess ->
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            if (writeSuccess) {
                Log.i(TAG, "✓ MEASUREMENT CCCD write SUCCESS - Notifications enabled")
            } else {
                Log.e(TAG, "✗ MEASUREMENT CCCD write FAILED - Notifications NOT enabled")
            }
            continuation.resume(writeSuccess) {}
            onDescriptorWriteCallback = null // Clear callback
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val writeSuccess = gatt.writeDescriptor(descriptor)

        if (!writeSuccess) {
            Log.e(TAG, "Failed to initiate MEASUREMENT CCCD descriptor write (GATT queue busy?)")
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            onDescriptorWriteCallback = null
            continuation.resume(false) {}
        } else {
            Log.i(TAG, "MEASUREMENT CCCD descriptor write initiated, waiting for callback...")
            // Timeout after 5 seconds
            handler.postDelayed(timeoutRunnable, 5000)
        }

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable) // Cancel timeout
        }
    }

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
            Log.w(TAG, "TIME_REQUEST characteristic not found")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        val success = gatt.setCharacteristicNotification(timeRequestChar, true)
        if (!success) {
            Log.e(TAG, "Failed to set characteristic notification for TIME_REQUEST")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

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
                Log.i(TAG, "✓ TIME_REQUEST CCCD write SUCCESS - Indications enabled")
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
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // Patient write with response
        gatt.writeCharacteristic(characteristic)
        Log.i(TAG, "Sent time sync: $currentTime")
    }

    private fun handleLiveMeasurement(data: ByteArray) {
        // Update connection health timestamp
        lastDataReceivedTime = System.currentTimeMillis()

        val measurement = parseMeasurementData(data) ?: run {
            Log.w(TAG, "Failed to parse live measurement")
            return
        }

        Log.i(TAG, "📊 Live measurement: PM2.5=${String.format("%.1f", measurement.pm25)} µg/m³, Temp=${measurement.temperature}°C")

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

            Log.d(TAG, "🔍 Parsing measurement: dataSize=${data.size}, sensorMask=0x${sensorMask.toString(16)}, hasBME690=$hasBME690")

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
                val rawTemp = buffer.getFloat(18)
                val rawHumid = buffer.getFloat(22)
                val rawPress = buffer.getFloat(26)
                val rawIaq = buffer.getFloat(30)
                val rawGasRes = buffer.getFloat(34)

                Log.d(TAG, "🌡️ Raw BME690 data: temp=$rawTemp, humid=$rawHumid, press=$rawPress, iaq=$rawIaq, gasRes=$rawGasRes")

                temperature = rawTemp
                humidity = rawHumid
                pressure = rawPress
                iaq = rawIaq
                gasResistance = rawGasRes
            } else {
                Log.w(TAG, "⚠️ BME690 data not available: hasBME690=$hasBME690, dataSize=${data.size} (need >=38)")
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

    /**
     * Enable DATA_RESPONSE notifications for bulk data transfer
     */
    private suspend fun enableDataResponseNotifications(gatt: BluetoothGatt): Boolean = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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

        // Enable local notifications
        val success = gatt.setCharacteristicNotification(dataResponseChar, true)
        if (!success) {
            Log.e(TAG, "Failed to set characteristic notification for DATA_RESPONSE")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        // Enable notifications on remote device via CCCD
        val descriptor = dataResponseChar.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "CCCD descriptor not found for DATA_RESPONSE characteristic")
            continuation.resume(false) {}
            return@suspendCancellableCoroutine
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val writeSuccess = gatt.writeDescriptor(descriptor)

        if (writeSuccess) {
            Log.i(TAG, "DATA_RESPONSE CCCD write initiated")
            // Create timeout runnable so we can cancel it on cancellation
            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    continuation.resume(true) {}
                }
            }
            // Assume success - we'll know if notifications don't arrive
            handler.postDelayed(timeoutRunnable, 500)

            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable) // Cancel timeout
            }
        } else {
            Log.e(TAG, "Failed to write DATA_RESPONSE CCCD")
            continuation.resume(false) {}
        }
    }

    /**
     * Request initial bulk data sync after connection
     * Gets all data since last sync from database
     */
    private suspend fun requestInitialBulkData(gatt: BluetoothGatt) {
        try {
            val lastSyncedTimestamp = database.measurementDao().getLatestTimestamp() ?: 0L
            val currentTime = System.currentTimeMillis() / 1000

            Log.i(TAG, "📥 Requesting initial bulk data sync")
            Log.d(TAG, "   Last synced: $lastSyncedTimestamp, Current: $currentTime")

            requestBulkData(gatt, lastSyncedTimestamp, currentTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting initial bulk data", e)
        }
    }

    /**
     * Request bulk data from ESP32
     */
    private fun requestBulkData(gatt: BluetoothGatt, startTime: Long, endTime: Long, maxRecords: Int = 500) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        val dataRequestChar = service?.getCharacteristic(DATA_REQUEST_UUID)

        if (dataRequestChar == null) {
            Log.w(TAG, "DATA_REQUEST characteristic not found")
            return
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

        dataRequestChar.value = requestData
        dataRequestChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // Patient write with response
        gatt.writeCharacteristic(dataRequestChar)
    }

    /**
     * Handle bulk data packet received via DATA_RESPONSE notification
     * Uses 3-packet format parser
     */
    private fun handleBulkDataPacket(data: ByteArray, gatt: BluetoothGatt) {
        // Update connection health timestamp
        lastDataReceivedTime = System.currentTimeMillis()

        val parsed = bulkDataParser.parsePacket(data) ?: run {
            Log.e(TAG, "Failed to parse bulk data packet")
            return
        }

        val (packetIndex, totalPackets) = parsed

        // Initialize on first packet
        if (packetIndex == 0 && totalPackets > 0) {
            expectedTotalPackets = totalPackets
            accumulatedMeasurements.clear()
            Log.i(TAG, "Starting bulk transfer: $totalPackets packets")
        }

        // Check for completed measurements after each packet
        val completedMeasurements = bulkDataParser.getCompletedMeasurements()
        if (completedMeasurements.isNotEmpty()) {
            Log.d(TAG, "Completed measurements: ${accumulatedMeasurements.size + completedMeasurements.size}")
            accumulatedMeasurements.addAll(completedMeasurements)
        }

        // Check if transfer is complete
        if (expectedTotalPackets > 0 && packetIndex + 1 >= expectedTotalPackets) {
            Log.i(TAG, "Transfer complete: ${accumulatedMeasurements.size} measurements")

            // Get any remaining completed measurements
            val finalMeasurements = bulkDataParser.getCompletedMeasurements()
            accumulatedMeasurements.addAll(finalMeasurements)

            // Save to database
            serviceScope.launch {
                saveBulkMeasurements(gatt, accumulatedMeasurements.toList())
            }

            // Reset for next transfer
            bulkDataParser.reset()
            accumulatedMeasurements.clear()
            expectedTotalPackets = 0
        }
    }

    /**
     * Save bulk measurements to database and acknowledge to ESP32
     */
    private suspend fun saveBulkMeasurements(gatt: BluetoothGatt, measurements: List<AirQualitySensorClient.MeasurementData>) {
        if (measurements.isEmpty()) {
            Log.d(TAG, "No measurements to save")
            return
        }

        try {
            Log.i(TAG, "💾 Saving ${measurements.size} measurements to database")

            val entities = measurements.map { m ->
                MeasurementEntity(
                    timestamp = m.timestamp,
                    receivedAt = System.currentTimeMillis(),
                    pm1 = m.pm1,
                    pm25 = m.pm25,
                    pm10 = m.pm10,
                    obstructed = m.obstructed,
                    timeValid = m.timeValid,
                    temperature = m.temperature,
                    humidity = m.humidity,
                    pressure = m.pressure,
                    iaq = m.iaq,
                    gasResistance = m.gasResistance,
                    iaqAccuracy = m.iaqAccuracy
                )
            }

            database.measurementDao().insertAll(entities)
            database.measurementDao().keepOnlyLast(500)

            val latest = measurements.last()
            Log.i(TAG, "✓ Saved ${measurements.size} measurements")
            Log.i(TAG, "   Latest: PM2.5=${String.format("%.1f", latest.pm25)} µg/m³, Temp=${latest.temperature}°C")

            // Acknowledge to ESP32
            val maxTimestamp = measurements.maxOf { it.timestamp }
            acknowledgeBulkData(gatt, maxTimestamp)

            updateNotification("Connected - ${measurements.size} measurements synced")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bulk measurements", e)
        }
    }

    /**
     * Acknowledge bulk data transfer to ESP32
     * Tells ESP32 to delete all measurements up to maxTimestamp
     */
    private fun acknowledgeBulkData(gatt: BluetoothGatt, maxTimestamp: Long) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        val deleteRequestChar = service?.getCharacteristic(DELETE_REQUEST_UUID)

        if (deleteRequestChar == null) {
            Log.w(TAG, "DELETE_REQUEST characteristic not found")
            return
        }

        // Build 4-byte acknowledgment: [max_timestamp(4)]
        val ackData = ByteBuffer.allocate(4).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(maxTimestamp.toInt())
        }.array()

        Log.i(TAG, "🗑 Acknowledging bulk data up to timestamp $maxTimestamp")

        deleteRequestChar.value = ackData
        deleteRequestChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // Patient write with response
        gatt.writeCharacteristic(deleteRequestChar)
    }
}
