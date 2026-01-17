package com.airsens.monitor

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.airsens.monitor.database.AppDatabase
import com.airsens.monitor.database.MeasurementEntity
import kotlinx.coroutines.*
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * WorkManager worker for background periodic sync
 * Connects to ESP32, syncs bulk data, then disconnects
 */
class SensorSyncWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SensorSyncWorker"
        private const val SCAN_TIMEOUT = 10000L
        private const val CONNECTION_TIMEOUT = 30000L

        val SERVICE_UUID: UUID = UUID.fromString("0000AAAA-0000-1000-8000-00805F9B34FB")
        val TIME_SYNC_UUID: UUID = UUID.fromString("0000AAA2-0000-1000-8000-00805F9B34FB")
        val TIME_REQUEST_UUID: UUID = UUID.fromString("0000AAA4-0000-1000-8000-00805F9B34FB")
        val DATA_REQUEST_UUID: UUID = UUID.fromString("0000AAA5-0000-1000-8000-00805F9B34FB")
        val DATA_RESPONSE_UUID: UUID = UUID.fromString("0000AAA6-0000-1000-8000-00805F9B34FB")
        val DELETE_REQUEST_UUID: UUID = UUID.fromString("0000AAA7-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME = "BMV080"
    }

    private lateinit var database: AppDatabase
    private var currentGatt: BluetoothGatt? = null
    private val bulkDataParser = BulkDataParser()
    private var expectedTotalRecords = 0
    private var receivedRecordCount = 0
    private val accumulatedMeasurements = mutableListOf<AirQualitySensorClient.MeasurementData>()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val startTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        Log.i(TAG, "========================================")
        Log.i(TAG, "🔄 BACKGROUND SYNC STARTED")
        Log.i(TAG, "   Time: $startTime")
        Log.i(TAG, "========================================")

        database = AppDatabase.getDatabase(context)

        // Acquire wake lock for BLE operations
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AirQuality::SyncWakeLock"
        )

        try {
            wakeLock.acquire(60000) // 60 second max

            // 1. Scan for device
            val device = scanForDevice()
            if (device == null) {
                Log.w(TAG, "Device not found during scan")
                return@withContext Result.retry()
            }

            // 2. Connect and sync
            val success = connectAndSync(device)

            if (success) {
                val endTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                Log.i(TAG, "========================================")
                Log.i(TAG, "✅ BACKGROUND SYNC SUCCESSFUL")
                Log.i(TAG, "   Completed: $endTime")
                Log.i(TAG, "   Next sync: ~15 minutes")
                Log.i(TAG, "========================================")
                Result.success()
            } else {
                Log.w(TAG, "========================================")
                Log.w(TAG, "⚠️ BACKGROUND SYNC FAILED - Will retry")
                Log.w(TAG, "========================================")
                Result.retry()
            }

        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "❌ BACKGROUND SYNC ERROR")
            Log.e(TAG, "   Error: ${e.message}")
            Log.e(TAG, "   Will retry automatically")
            Log.e(TAG, "========================================", e)
            Result.retry()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            currentGatt?.close()
            currentGatt = null
        }
    }

    private suspend fun scanForDevice(): BluetoothDevice? = suspendCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No BLUETOOTH_SCAN permission")
            continuation.resume(null)
            return@suspendCoroutine
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val scanner = bluetoothAdapter?.bluetoothLeScanner

        if (scanner == null) {
            Log.w(TAG, "BLE scanner not available")
            continuation.resume(null)
            return@suspendCoroutine
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val scanFilter = ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
            .build()

        var resumed = false
        var timeoutJob: Job? = null

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (resumed) return
                resumed = true
                timeoutJob?.cancel()
                Log.i(TAG, "Device found: ${result.device.address}")
                scanner.stopScan(this)
                continuation.resume(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (resumed) return
                resumed = true
                timeoutJob?.cancel()
                Log.e(TAG, "Scan failed: $errorCode")
                scanner.stopScan(this)
                continuation.resume(null)
            }
        }

        scanner.startScan(listOf(scanFilter), settings, callback)
        Log.d(TAG, "Scanning for device...")

        // Timeout
        timeoutJob = CoroutineScope(Dispatchers.IO).launch {
            delay(SCAN_TIMEOUT)
            if (!resumed) {
                resumed = true
                scanner.stopScan(callback)
                continuation.resume(null)
            }
        }
    }

    private suspend fun connectAndSync(device: BluetoothDevice): Boolean = withTimeoutOrNull(CONNECTION_TIMEOUT) {
        suspendCoroutine { continuation ->
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                continuation.resume(false)
                return@suspendCoroutine
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Connected, discovering services...")
                            if (ActivityCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                gatt.discoverServices()
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.i(TAG, "Disconnected")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Services discovered, starting sync...")
                        GlobalScope.launch {
                            try {
                                performSync(gatt)
                                continuation.resume(true)
                            } catch (e: Exception) {
                                Log.e(TAG, "Sync failed", e)
                                continuation.resume(false)
                            } finally {
                                delay(1000)
                                gatt.disconnect()
                            }
                        }
                    } else {
                        Log.e(TAG, "Service discovery failed: $status")
                        continuation.resume(false)
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    if (characteristic.uuid == DATA_RESPONSE_UUID) {
                        handleDataPacket(value)
                    }
                }

                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    if (characteristic.uuid == DATA_RESPONSE_UUID) {
                        characteristic.value?.let { handleDataPacket(it) }
                    }
                }
            }

            Log.i(TAG, "Connecting to device...")
            currentGatt = device.connectGatt(context, false, callback)
        }
    } ?: false

    private suspend fun performSync(gatt: BluetoothGatt) {
        // 1. Send time sync
        sendTimeSync(gatt)
        delay(300)

        // 2. Subscribe to DATA_RESPONSE
        enableDataResponseNotifications(gatt)
        delay(500)

        // 3. Request bulk data
        val lastSyncedTimestamp = database.measurementDao().getLatestTimestamp() ?: 0L
        val currentTime = System.currentTimeMillis() / 1000
        requestBulkData(gatt, lastSyncedTimestamp, currentTime)

        // 4. Wait for all packets
        delay(20000) // Wait up to 20 seconds for transfer

        // 5. Save measurements
        if (accumulatedMeasurements.isNotEmpty()) {
            Log.i(TAG, "Saving ${accumulatedMeasurements.size} measurements")

            val now = System.currentTimeMillis() / 1000
            val validMeasurements = accumulatedMeasurements.filter { it.timestamp <= now }
            val futureMeasurements = accumulatedMeasurements.size - validMeasurements.size
            if (futureMeasurements > 0) {
                Log.w(TAG, "$futureMeasurements measurements from the future, ignoring")
            }

            val entities = validMeasurements.map { m ->
                val gasResistanceArrayJson = if (m.gasResistanceArray != null) {
                    JSONArray(m.gasResistanceArray.toList()).toString()
                } else {
                    null
                }

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
                    iaqAccuracy = m.iaqAccuracy,
                    gasResistanceProfile = m.gasResistanceProfile,
                    gasResistanceArray = gasResistanceArrayJson
                )
            }
            val results = database.measurementDao().insertAll(entities)
            val insertedCount = results.count { it != -1L }
            val duplicateCount = entities.size - insertedCount
            if (duplicateCount > 0) {
                Log.w(TAG, "$duplicateCount measurements with existing timestamps, ignoring.")
            }
            database.measurementDao().keepOnlyLast(500)

            if (insertedCount > 0) {
                val latest = validMeasurements.last()
                Log.i(TAG, "💾 Saved $insertedCount measurements to database")
                Log.i(
                    TAG,
                    "   Latest: PM2.5=${String.format("%.1f", latest.pm25)} µg/m³, Temp=${latest.temperature}°C"
                )

                // 6. Acknowledge
                val maxTimestamp = validMeasurements.maxOf { it.timestamp }
                acknowledgeBulkData(gatt, maxTimestamp)
                Log.i(TAG, "✓ Acknowledged deletion up to timestamp $maxTimestamp")
            }
        }
    }

    private fun sendTimeSync(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(TIME_SYNC_UUID) ?: return

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val currentTime = System.currentTimeMillis() / 1000
        val timeBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(currentTime.toInt())
            .array()

        characteristic.value = timeBytes
        gatt.writeCharacteristic(characteristic)
        Log.i(TAG, "Sent time sync: $currentTime")
    }

    private fun enableDataResponseNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(DATA_RESPONSE_UUID) ?: return

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        descriptor?.let {
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(it)
        }
        Log.i(TAG, "Subscribed to DATA_RESPONSE notifications")
    }

    private fun requestBulkData(gatt: BluetoothGatt, startTime: Long, endTime: Long) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(DATA_REQUEST_UUID) ?: return

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val requestData = ByteBuffer.allocate(10).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(startTime.toInt())
            putInt(endTime.toInt())
            putShort(100) // max 100 records
        }.array()

        characteristic.value = requestData
        gatt.writeCharacteristic(characteristic)
        Log.i(TAG, "Requested bulk data: $startTime to $endTime")
    }

    private fun acknowledgeBulkData(gatt: BluetoothGatt, maxTimestamp: Long) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(DELETE_REQUEST_UUID) ?: return

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val ackData = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(maxTimestamp.toInt())
            .array()

        characteristic.value = ackData
        gatt.writeCharacteristic(characteristic)
        Log.i(TAG, "Acknowledged data up to: $maxTimestamp")
    }

    private fun handleDataPacket(data: ByteArray) {
        val parsed = bulkDataParser.parsePacket(data) ?: return
        val (recordIndex, totalRecords) = parsed

        if (recordIndex == 0 && totalRecords > 0) {
            expectedTotalRecords = totalRecords
            receivedRecordCount = 0
            accumulatedMeasurements.clear()
            Log.i(TAG, "Starting transfer: $totalRecords records")
        }

        // Parse the complete measurement from this single packet
        val measurement = bulkDataParser.parseMeasurement(data)
        if (measurement != null) {
            receivedRecordCount++
            accumulatedMeasurements.add(measurement)
            Log.d(TAG, "Received measurement $receivedRecordCount/$expectedTotalRecords")
        } else {
            Log.e(TAG, "Failed to parse measurement from packet")
        }

        if (expectedTotalRecords > 0 && receivedRecordCount >= expectedTotalRecords) {
            Log.i(TAG, "Transfer complete: ${accumulatedMeasurements.size} measurements")
            bulkDataParser.reset()
        }
    }
}
