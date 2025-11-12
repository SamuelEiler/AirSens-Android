package com.airsens.monitor

import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class AirQualitySensorClient(
    private val context: Context,
    private val listener: SensorDataListener
) {
    companion object {
        private const val TAG = "AirQualitySensor"

        val SERVICE_UUID: UUID = UUID.fromString("0000AAAA-0000-1000-8000-00805F9B34FB")
        val MEASUREMENT_UUID: UUID = UUID.fromString("0000AAA1-0000-1000-8000-00805F9B34FB")
        val TIME_SYNC_UUID: UUID = UUID.fromString("0000AAA2-0000-1000-8000-00805F9B34FB")
        val GAS_PROFILE_UUID: UUID = UUID.fromString("0000AAA8-0000-1000-8000-00805F9B34FB")
        val STATUS_UUID: UUID = UUID.fromString("0000AAA3-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEVICE_NAME = "BMV080"
    }

    interface SensorDataListener {
        fun onMeasurementReceived(data: MeasurementData)
        fun onGasProfileReceived(data: GasProfileData)
        fun onConnectionStateChanged(connected: Boolean)
        fun onError(error: String)
    }

    data class MeasurementData(
        val timestamp: Long,
        val pm10: Float,
        val pm25: Float,
        val pm1: Float,
        val obstructed: Boolean,
        val timeValid: Boolean,
        val temperature: Float? = null,
        val humidity: Float? = null,
        val pressure: Float? = null,
        val iaq: Float? = null,
        val gasResistance: Float? = null,
        val iaqAccuracy: Int = 0
    )

    data class GasProfileData(
        val heaterTemp: Int,
        val gasResistance: Float,
        val humidity: Float,
        val pressure: Float
    )

    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    listener.onConnectionStateChanged(true)
                    Log.d(TAG, "Connected to GATT server, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    listener.onConnectionStateChanged(false)
                    Log.d(TAG, "Disconnected from GATT server")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "Services discovered: status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Send time sync first
                sendTimeSync(gatt)

                // Enable notifications for measurements and gas profile
                enableNotification(gatt, MEASUREMENT_UUID)

                // Small delay before enabling second notification
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    enableNotification(gatt, GAS_PROFILE_UUID)
                }, 500)
            } else {
                listener.onError("Service discovery failed: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Characteristic changed: ${characteristic.uuid}")

            when (characteristic.uuid) {
                MEASUREMENT_UUID -> {
                    val data = parseMeasurement(characteristic.value)
                    if (data != null) {
                        listener.onMeasurementReceived(data)
                    } else {
                        Log.e(TAG, "Failed to parse measurement data")
                    }
                }
                GAS_PROFILE_UUID -> {
                    val data = parseGasProfile(characteristic.value)
                    if (data != null) {
                        listener.onGasProfileReceived(data)
                    } else {
                        Log.e(TAG, "Failed to parse gas profile data")
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "Characteristic write: ${characteristic.uuid}, status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write successful for ${characteristic.uuid}")
            } else {
                Log.e(TAG, "Write failed for ${characteristic.uuid}: $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "Descriptor write: ${descriptor.uuid}, status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed: $status")
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
    }

    fun isConnected(): Boolean = isConnected

    private fun sendTimeSync(gatt: BluetoothGatt) {
        try {
            val currentTime = System.currentTimeMillis() / 1000
            val timeBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(currentTime.toInt())
                .array()

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Service not found: $SERVICE_UUID")
                listener.onError("Service not found")
                return
            }

            val characteristic = service.getCharacteristic(TIME_SYNC_UUID)
            if (characteristic == null) {
                Log.e(TAG, "Time sync characteristic not found")
                listener.onError("Time sync characteristic not found")
                return
            }

            characteristic.value = timeBytes
            gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "Time sync sent: $currentTime")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending time sync", e)
            listener.onError("Time sync failed: ${e.message}")
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, uuid: UUID) {
        try {
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Service not found for notification: $SERVICE_UUID")
                return
            }

            val characteristic = service.getCharacteristic(uuid)
            if (characteristic == null) {
                Log.e(TAG, "Characteristic not found for notification: $uuid")
                return
            }

            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                Log.e(TAG, "CCCD descriptor not found")
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            Log.d(TAG, "Notification enabled for: $uuid")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling notification", e)
            listener.onError("Failed to enable notification: ${e.message}")
        }
    }

    private fun parseMeasurement(data: ByteArray): MeasurementData? {
        try {
            if (data.size < 22) {
                Log.e(TAG, "Measurement data too short: ${data.size} bytes")
                return null
            }

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Parse sensor mask
            val sensorMask = buffer.get().toInt() and 0xFF
            val hasBMV080 = (sensorMask and 0x01) != 0
            val hasBME690 = (sensorMask and 0x02) != 0

            Log.d(TAG, "Sensor mask: 0x${sensorMask.toString(16)}, BMV080=$hasBMV080, BME690=$hasBME690")

            // Parse common data
            val timestamp = buffer.int.toLong()
            val pm10 = buffer.float
            val pm25 = buffer.float
            val pm1 = buffer.float

            val obstructed = buffer.get() != 0.toByte()
            val timeValid = buffer.get() != 0.toByte()
            val iaqAccuracy = buffer.get().toInt() and 0xFF
            buffer.get() // Skip padding
            buffer.get() // Skip padding

            // Parse BME690 data if present
            var temperature: Float? = null
            var humidity: Float? = null
            var pressure: Float? = null
            var iaq: Float? = null
            var gasResistance: Float? = null

            if (hasBME690 && buffer.remaining() >= 20) {
                temperature = buffer.float
                humidity = buffer.float
                pressure = buffer.float
                iaq = buffer.float
                gasResistance = buffer.float
            }

            return MeasurementData(
                timestamp, pm10, pm25, pm1, obstructed, timeValid,
                temperature, humidity, pressure, iaq, gasResistance, iaqAccuracy
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing measurement", e)
            return null
        }
    }

    private fun parseGasProfile(data: ByteArray): GasProfileData? {
        try {
            if (data.size < 14) {
                Log.e(TAG, "Gas profile data too short: ${data.size} bytes")
                return null
            }

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val heaterTemp = buffer.short.toInt() and 0xFFFF
            val gasResistance = buffer.float
            val humidity = buffer.float
            val pressure = buffer.float

            return GasProfileData(heaterTemp, gasResistance, humidity, pressure)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing gas profile", e)
            return null
        }
    }
}
