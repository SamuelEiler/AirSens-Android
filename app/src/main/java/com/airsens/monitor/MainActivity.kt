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
import android.graphics.Color
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
import androidx.lifecycle.lifecycleScope
import com.airsens.monitor.database.AppDatabase
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), AirQualitySensorClient.SensorDataListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_PERMISSIONS = 2
        private const val SCAN_PERIOD: Long = 10000 // 10 seconds
        private const val MAX_CHART_ENTRIES = 50 // Max data points to show in history charts
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var sensorClient: AirQualitySensorClient? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var database: AppDatabase
    private var isServiceRunning = false

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var serviceToggleButton: Button
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

    // Chart views
    private lateinit var gasResistanceChart: BarChart
    private lateinit var particleMatterChart: LineChart
    private lateinit var tempHumidityChart: LineChart

    // Data storage for charts
    private val gasResistanceMap = mutableMapOf<Int, Float>() // Temperature -> Gas Resistance
    private val pm10History = mutableListOf<Entry>()
    private val pm25History = mutableListOf<Entry>()
    private val pm1History = mutableListOf<Entry>()
    private val temperatureHistory = mutableListOf<Entry>()
    private val humidityHistory = mutableListOf<Entry>()

    private val deviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(applicationContext)

        initializeViews()
        initializeBluetooth()
        checkPermissions()
        loadHistoricalData()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        serviceToggleButton = findViewById(R.id.serviceToggleButton)
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

        // Chart views
        gasResistanceChart = findViewById(R.id.gasResistanceChart)
        particleMatterChart = findViewById(R.id.particleMatterChart)
        tempHumidityChart = findViewById(R.id.tempHumidityChart)

        // Initialize charts
        initializeCharts()

        // Setup buttons
        serviceToggleButton.setOnClickListener {
            toggleBackgroundService()
        }

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

        // Android 13+ requires POST_NOTIFICATIONS for foreground service notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun toggleBackgroundService() {
        if (isServiceRunning) {
            stopBackgroundService()
        } else {
            startBackgroundService()
        }
    }

    private fun startBackgroundService() {
        val intent = Intent(this, BlePeriodicService::class.java).apply {
            action = BlePeriodicService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isServiceRunning = true
        serviceToggleButton.text = "Stop Background Monitoring"
        serviceToggleButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        Toast.makeText(this, "Background monitoring started", Toast.LENGTH_SHORT).show()
    }

    private fun stopBackgroundService() {
        val intent = Intent(this, BlePeriodicService::class.java).apply {
            action = BlePeriodicService.ACTION_STOP
        }
        startService(intent)

        isServiceRunning = false
        serviceToggleButton.text = "Start Background Monitoring"
        serviceToggleButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
        Toast.makeText(this, "Background monitoring stopped", Toast.LENGTH_SHORT).show()
    }

    private fun loadHistoricalData() {
        lifecycleScope.launch {
            try {
                // Load last 50 measurements from database
                val measurements = database.measurementDao().getLastN(MAX_CHART_ENTRIES)

                if (measurements.isNotEmpty()) {
                    // Clear existing data
                    pm10History.clear()
                    pm25History.clear()
                    pm1History.clear()
                    temperatureHistory.clear()
                    humidityHistory.clear()

                    // Populate charts with historical data
                    measurements.forEach { measurement ->
                        val timestampMs = measurement.timestamp * 1000f

                        pm10History.add(Entry(timestampMs, measurement.pm10))
                        pm25History.add(Entry(timestampMs, measurement.pm25))
                        pm1History.add(Entry(timestampMs, measurement.pm1))

                        measurement.temperature?.let {
                            temperatureHistory.add(Entry(timestampMs, it))
                        }

                        measurement.humidity?.let {
                            humidityHistory.add(Entry(timestampMs, it))
                        }
                    }

                    // Update charts on UI thread
                    runOnUiThread {
                        updateParticleMatterChart()
                        updateTempHumidityChart()

                        // Display the latest measurement data
                        val latest = measurements.first()
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        val timestamp = Date(latest.timestamp * 1000)

                        pm10Text.text = "PM10: %.2f µg/m³".format(latest.pm10)
                        pm25Text.text = "PM2.5: %.2f µg/m³".format(latest.pm25)
                        pm1Text.text = "PM1.0: %.2f µg/m³".format(latest.pm1)
                        timestampText.text = "Last Update: ${dateFormat.format(timestamp)}"

                        latest.temperature?.let {
                            temperatureText.text = "Temperature: %.1f°C".format(it)
                            temperatureText.visibility = View.VISIBLE
                        }

                        latest.humidity?.let {
                            humidityText.text = "Humidity: %.1f%%".format(it)
                            humidityText.visibility = View.VISIBLE
                        }

                        latest.pressure?.let {
                            pressureText.text = "Pressure: %.1f hPa".format(it)
                            pressureText.visibility = View.VISIBLE
                        }

                        latest.iaq?.let {
                            iaqText.text = "IAQ: %.1f".format(it)
                            iaqText.visibility = View.VISIBLE
                        }

                        latest.gasResistance?.let {
                            gasResistanceText.text = "Gas Resistance: %.0f Ω".format(it)
                            gasResistanceText.visibility = View.VISIBLE
                        }

                        obstructedText.text = if (latest.obstructed) "⚠ Sensor Obstructed" else "✓ Sensor Clear"
                        obstructedText.setTextColor(
                            if (latest.obstructed)
                                ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark)
                            else
                                ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark)
                        )

                        iaqAccuracyText.text = "IAQ Accuracy: ${getIAQAccuracyString(latest.iaqAccuracy)}"

                        // Show data container if we have data
                        dataContainer.visibility = View.VISIBLE
                        statusText.text = "Loaded ${measurements.size} historical measurements"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading historical data", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error loading historical data", Toast.LENGTH_SHORT).show()
                }
            }
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
        clearChartData()
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

            // Add particle matter data to charts using timestamp as X value (in milliseconds)
            val timestampMs = data.timestamp * 1000f
            pm10History.add(Entry(timestampMs, data.pm10))
            pm25History.add(Entry(timestampMs, data.pm25))
            pm1History.add(Entry(timestampMs, data.pm1))

            data.temperature?.let {
                temperatureText.text = "Temperature: %.1f°C".format(it)
                temperatureText.visibility = View.VISIBLE
                // Add to chart
                temperatureHistory.add(Entry(timestampMs, it))
            } ?: run {
                temperatureText.visibility = View.GONE
            }

            data.humidity?.let {
                humidityText.text = "Humidity: %.1f%%".format(it)
                humidityText.visibility = View.VISIBLE
                // Add to chart
                humidityHistory.add(Entry(timestampMs, it))
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

            // Update charts
            updateParticleMatterChart()
            updateTempHumidityChart()
        }
    }

    override fun onGasProfileReceived(data: AirQualitySensorClient.GasProfileData) {
        runOnUiThread {
            // Add to gas resistance chart
            gasResistanceMap[data.heaterTemp] = data.gasResistance
            updateGasResistanceChart()
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

    private fun initializeCharts() {
        // Initialize Gas Resistance Bar Chart
        gasResistanceChart.apply {
            description.isEnabled = false
            setDrawBarShadow(false)
            setDrawValueAboveBar(true)
            setPinchZoom(false)
            setScaleEnabled(false)
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}°C"
                    }
                }
            }

            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
            }

            axisRight.isEnabled = false
        }

        // Initialize Particle Matter Line Chart
        particleMatterChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleXEnabled(true)  // Enable X-axis zooming
            setScaleYEnabled(false) // Disable Y-axis zooming (auto-scale)
            setPinchZoom(false)     // Disable pinch zoom since we only want X-axis zoom
            setDrawGridBackground(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                granularity = 1000f // 1 second minimum
                valueFormatter = object : ValueFormatter() {
                    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    override fun getFormattedValue(value: Float): String {
                        return dateFormat.format(Date(value.toLong()))
                    }
                }
            }

            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
            }

            axisRight.isEnabled = false

            legend.isEnabled = true
            setAutoScaleMinMaxEnabled(true) // Enable auto-scaling for Y-axis
        }

        // Initialize Temperature & Humidity Line Chart
        tempHumidityChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleXEnabled(true)  // Enable X-axis zooming
            setScaleYEnabled(false) // Disable Y-axis zooming (auto-scale)
            setPinchZoom(false)     // Disable pinch zoom since we only want X-axis zoom
            setDrawGridBackground(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                granularity = 1000f // 1 second minimum
                valueFormatter = object : ValueFormatter() {
                    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    override fun getFormattedValue(value: Float): String {
                        return dateFormat.format(Date(value.toLong()))
                    }
                }
            }

            axisLeft.apply {
                setDrawGridLines(true)
            }

            axisRight.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
            }

            legend.isEnabled = true
            setAutoScaleMinMaxEnabled(true) // Enable auto-scaling for Y-axis
        }

        // Set initial empty data
        updateGasResistanceChart()
        updateParticleMatterChart()
        updateTempHumidityChart()
    }

    private fun updateGasResistanceChart() {
        val entries = gasResistanceMap.map { (temp, resistance) ->
            BarEntry(temp.toFloat(), resistance)
        }.sortedBy { it.x }

        if (entries.isEmpty()) {
            gasResistanceChart.clear()
            gasResistanceChart.invalidate()
            return
        }

        val dataSet = BarDataSet(entries, "Gas Resistance").apply {
            color = Color.parseColor("#9B59B6")
            valueTextSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return "%.0f".format(value)
                }
            }
        }

        gasResistanceChart.data = BarData(dataSet)
        gasResistanceChart.invalidate()
    }

    private fun updateParticleMatterChart() {
        // Limit data points
        while (pm10History.size > MAX_CHART_ENTRIES) pm10History.removeAt(0)
        while (pm25History.size > MAX_CHART_ENTRIES) pm25History.removeAt(0)
        while (pm1History.size > MAX_CHART_ENTRIES) pm1History.removeAt(0)

        val dataSets = mutableListOf<LineDataSet>()

        if (pm10History.isNotEmpty()) {
            dataSets.add(LineDataSet(pm10History, "PM10").apply {
                color = Color.RED
                setCircleColor(Color.RED)
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                valueTextSize = 0f
                mode = LineDataSet.Mode.CUBIC_BEZIER
            })
        }

        if (pm25History.isNotEmpty()) {
            dataSets.add(LineDataSet(pm25History, "PM2.5").apply {
                color = Color.parseColor("#FF9800")
                setCircleColor(Color.parseColor("#FF9800"))
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                valueTextSize = 0f
                mode = LineDataSet.Mode.CUBIC_BEZIER
            })
        }

        if (pm1History.isNotEmpty()) {
            dataSets.add(LineDataSet(pm1History, "PM1.0").apply {
                color = Color.parseColor("#4CAF50")
                setCircleColor(Color.parseColor("#4CAF50"))
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                valueTextSize = 0f
                mode = LineDataSet.Mode.CUBIC_BEZIER
            })
        }

        if (dataSets.isEmpty()) {
            particleMatterChart.clear()
            particleMatterChart.invalidate()
            return
        }

        particleMatterChart.data = LineData(dataSets as List<ILineDataSet>)
        particleMatterChart.invalidate()
    }

    private fun updateTempHumidityChart() {
        // Limit data points
        while (temperatureHistory.size > MAX_CHART_ENTRIES) temperatureHistory.removeAt(0)
        while (humidityHistory.size > MAX_CHART_ENTRIES) humidityHistory.removeAt(0)

        val dataSets = mutableListOf<LineDataSet>()

        if (temperatureHistory.isNotEmpty()) {
            dataSets.add(LineDataSet(temperatureHistory, "Temperature (°C)").apply {
                color = Color.parseColor("#E74C3C")
                setCircleColor(Color.parseColor("#E74C3C"))
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                valueTextSize = 0f
                mode = LineDataSet.Mode.CUBIC_BEZIER
                axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
            })
        }

        if (humidityHistory.isNotEmpty()) {
            dataSets.add(LineDataSet(humidityHistory, "Humidity (%)").apply {
                color = Color.parseColor("#3498DB")
                setCircleColor(Color.parseColor("#3498DB"))
                lineWidth = 2f
                circleRadius = 3f
                setDrawCircleHole(false)
                valueTextSize = 0f
                mode = LineDataSet.Mode.CUBIC_BEZIER
                axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
            })
        }

        if (dataSets.isEmpty()) {
            tempHumidityChart.clear()
            tempHumidityChart.invalidate()
            return
        }

        tempHumidityChart.data = LineData(dataSets as List<ILineDataSet>)
        tempHumidityChart.invalidate()
    }

    private fun clearChartData() {
        gasResistanceMap.clear()
        pm10History.clear()
        pm25History.clear()
        pm1History.clear()
        temperatureHistory.clear()
        humidityHistory.clear()

        updateGasResistanceChart()
        updateParticleMatterChart()
        updateTempHumidityChart()
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
