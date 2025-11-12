package com.airsens.monitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), AirQualitySensorClient.SensorDataListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_PERMISSIONS = 2
        private const val SCAN_PERIOD: Long = 10000 // 10 seconds
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var sensorClient: AirQualitySensorClient? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var deviceListView: ListView
    private lateinit var dataContainer: ScrollView

    // Data display views
    private lateinit var pm10Text: TextView
    private lateinit var pm25Text: TextView
    private lateinit var pm1Text: TextView
    private lateinit var temperatureText: TextView
    private lateinit var humidityText: TextView
    private lateinit var pressureText: TextView
    private lateinit var iaqText: TextView
    private lateinit var gasResistanceText: TextView
    private lateinit var timestampText: TextView
    private lateinit var obstructedText: TextView
    private lateinit var iaqAccuracyText: TextView

    // Gas profile views
    private lateinit var heaterTempText: TextView
    private lateinit var gasProfileResistanceText: TextView

    private val deviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        initializeBluetooth()
        checkPermissions()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        deviceListView = findViewById(R.id.deviceListView)
        dataContainer = findViewById(R.id.dataContainer)

        // Measurement data views
        pm10Text = findViewById(R.id.pm10Text)
        pm25Text = findViewById(R.id.pm25Text)
        pm1Text = findViewById(R.id.pm1Text)
        temperatureText = findViewById(R.id.temperatureText)
        humidityText = findViewById(R.id.humidityText)
        pressureText = findViewById(R.id.pressureText)
        iaqText = findViewById(R.id.iaqText)
        gasResistanceText = findViewById(R.id.gasResistanceText)
        timestampText = findViewById(R.id.timestampText)
        obstructedText = findViewById(R.id.obstructedText)
        iaqAccuracyText = findViewById(R.id.iaqAccuracyText)

        // Gas profile views
        heaterTempText = findViewById(R.id.heaterTempText)
        gasProfileResistanceText = findViewById(R.id.gasProfileResistanceText)

        // Setup buttons
        scanButton.setOnClickListener {
            if (!isScanning) {
                startScan()
            } else {
                stopScan()
            }
        }

        disconnectButton.setOnClickListener {
            disconnect()
        }

        // Setup device list
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        deviceListView.adapter = deviceAdapter
        deviceListView.setOnItemClickListener { _, _, position, _ ->
            if (position < deviceList.size) {
                connectToDevice(deviceList[position])
            }
        }

        updateUIState(false)
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // Android 11 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val deviceName = device.name ?: "Unknown"
                Log.d(TAG, "Device found: $deviceName (${device.address})")

                // Filter for our sensor device
                if (deviceName.contains("BMV080", ignoreCase = true) ||
                    deviceName.contains("AirSens", ignoreCase = true)
                ) {
                    if (!deviceList.contains(device)) {
                        deviceList.add(device)
                        runOnUiThread {
                            deviceAdapter.add("$deviceName\n${device.address}")
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            runOnUiThread {
                statusText.text = "Scan failed: $errorCode"
                stopScan()
            }
        }
    }

    private fun startScan() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }

        deviceList.clear()
        deviceAdapter.clear()
        deviceAdapter.notifyDataSetChanged()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner.startScan(null, settings, scanCallback)
        isScanning = true

        statusText.text = "Scanning for devices..."
        scanButton.text = "Stop Scan"

        // Stop scan after period
        handler.postDelayed({
            stopScan()
        }, SCAN_PERIOD)
    }

    private fun stopScan() {
        if (!isScanning) return

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothLeScanner.stopScan(scanCallback)
        }

        isScanning = false
        scanButton.text = "Scan for Devices"

        if (deviceList.isEmpty()) {
            statusText.text = "No devices found"
        } else {
            statusText.text = "Found ${deviceList.size} device(s)"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        stopScan()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }

        statusText.text = "Connecting to ${device.name ?: device.address}..."

        sensorClient = AirQualitySensorClient(this, this)
        sensorClient?.connect(device)
    }

    private fun disconnect() {
        sensorClient?.disconnect()
        sensorClient = null
        updateUIState(false)
        statusText.text = "Disconnected"
    }

    private fun updateUIState(connected: Boolean) {
        scanButton.isEnabled = !connected
        disconnectButton.isEnabled = connected
        deviceListView.visibility = if (connected) View.GONE else View.VISIBLE
        dataContainer.visibility = if (connected) View.VISIBLE else View.GONE
    }

    // SensorDataListener implementation
    override fun onMeasurementReceived(data: AirQualitySensorClient.MeasurementData) {
        runOnUiThread {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestamp = Date(data.timestamp * 1000)

            pm10Text.text = "PM10: %.2f µg/m³".format(data.pm10)
            pm25Text.text = "PM2.5: %.2f µg/m³".format(data.pm25)
            pm1Text.text = "PM1.0: %.2f µg/m³".format(data.pm1)

            data.temperature?.let {
                temperatureText.text = "Temperature: %.1f°C".format(it)
                temperatureText.visibility = View.VISIBLE
            } ?: run {
                temperatureText.visibility = View.GONE
            }

            data.humidity?.let {
                humidityText.text = "Humidity: %.1f%%".format(it)
                humidityText.visibility = View.VISIBLE
            } ?: run {
                humidityText.visibility = View.GONE
            }

            data.pressure?.let {
                pressureText.text = "Pressure: %.1f hPa".format(it)
                pressureText.visibility = View.VISIBLE
            } ?: run {
                pressureText.visibility = View.GONE
            }

            data.iaq?.let {
                iaqText.text = "IAQ: %.1f".format(it)
                iaqText.visibility = View.VISIBLE
            } ?: run {
                iaqText.visibility = View.GONE
            }

            data.gasResistance?.let {
                gasResistanceText.text = "Gas Resistance: %.0f Ω".format(it)
                gasResistanceText.visibility = View.VISIBLE
            } ?: run {
                gasResistanceText.visibility = View.GONE
            }

            timestampText.text = "Last Update: ${dateFormat.format(timestamp)}"
            obstructedText.text = if (data.obstructed) "⚠ Sensor Obstructed" else "✓ Sensor Clear"
            obstructedText.setTextColor(
                if (data.obstructed)
                    ContextCompat.getColor(this, android.R.color.holo_red_dark)
                else
                    ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )

            iaqAccuracyText.text = "IAQ Accuracy: ${getIAQAccuracyString(data.iaqAccuracy)}"
        }
    }

    override fun onGasProfileReceived(data: AirQualitySensorClient.GasProfileData) {
        runOnUiThread {
            heaterTempText.text = "Heater Temp: ${data.heaterTemp}°C"
            gasProfileResistanceText.text = "Gas Resistance: %.0f Ω".format(data.gasResistance)
        }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                statusText.text = "Connected - Receiving data..."
                updateUIState(true)
            } else {
                statusText.text = "Disconnected"
                updateUIState(false)
            }
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            statusText.text = "Error: $error"
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getIAQAccuracyString(accuracy: Int): String {
        return when (accuracy) {
            0 -> "Stabilizing"
            1 -> "Low"
            2 -> "Medium"
            3 -> "High"
            else -> "Unknown"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorClient?.disconnect()
        if (isScanning) {
            stopScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Permissions required for BLE scanning",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
