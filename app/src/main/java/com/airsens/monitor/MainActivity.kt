package com.airsens.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airsens.monitor.database.AppDatabase
import com.patrykandpatrick.vico.core.cartesian.CartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.views.cartesian.CartesianChartView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 2
        private const val MAX_CHART_ENTRIES = 50 // Max data points to show in history charts
    }

    private lateinit var database: AppDatabase

    // UI Components
    private lateinit var statusText: TextView
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

    // Chart views (Vico)
    private lateinit var gasResistanceChart: CartesianChartView
    private lateinit var particleMatterChart: CartesianChartView
    private lateinit var tempHumidityChart: CartesianChartView

    // Vico chart model producers
    private val gasResistanceModelProducer = CartesianChartModelProducer()
    private val particleMatterModelProducer = CartesianChartModelProducer()
    private val tempHumidityModelProducer = CartesianChartModelProducer()

    // Data storage for charts with timestamps
    private val gasResistanceData = mutableListOf<Pair<Long, Float>>() // timestamp -> gas resistance
    private val pm10Data = mutableListOf<Pair<Long, Float>>() // timestamp -> value
    private val pm25Data = mutableListOf<Pair<Long, Float>>()
    private val pm1Data = mutableListOf<Pair<Long, Float>>()
    private val temperatureData = mutableListOf<Pair<Long, Float>>() // timestamp -> value
    private val humidityData = mutableListOf<Pair<Long, Float>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(applicationContext)

        initializeViews()
        checkPermissions()
        loadHistoricalData()
        observeDatabaseChanges()
    }

    override fun onResume() {
        super.onResume()
        // Reload data when user returns to the app
        loadHistoricalData()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
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

    private fun observeDatabaseChanges() {
        lifecycleScope.launch {
            database.measurementDao().getAllFlow().collect { measurements ->
                if (measurements.isNotEmpty()) {
                    // Update UI with latest data
                    updateUIWithMeasurements(measurements.take(MAX_CHART_ENTRIES))
                }
            }
        }
    }

    private fun loadHistoricalData() {
        lifecycleScope.launch {
            try {
                // Load last 50 measurements from database
                val measurements = database.measurementDao().getLastN(MAX_CHART_ENTRIES)
                if (measurements.isNotEmpty()) {
                    updateUIWithMeasurements(measurements)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading historical data", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error loading historical data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateUIWithMeasurements(measurements: List<com.airsens.monitor.database.MeasurementEntity>) {
        // Clear existing data
        pm10Data.clear()
        pm25Data.clear()
        pm1Data.clear()
        temperatureData.clear()
        humidityData.clear()

        if (measurements.isEmpty()) {
            return
        }

        // Vico handles timestamps natively! Use actual measurement timestamps
        measurements.forEach { measurement ->
            val timestamp = measurement.timestamp

            pm10Data.add(Pair(timestamp, measurement.pm10))
            pm25Data.add(Pair(timestamp, measurement.pm25))
            pm1Data.add(Pair(timestamp, measurement.pm1))

            measurement.temperature?.let {
                temperatureData.add(Pair(timestamp, it))
            }

            measurement.humidity?.let {
                humidityData.add(Pair(timestamp, it))
            }
        }

        // Update charts on UI thread
        runOnUiThread {
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "Error updating UI with measurements", e)
                Toast.makeText(this@MainActivity, "Error displaying data", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initializeCharts() {
        // Date/time formatter for X-axis (timestamps)
        val dateTimeFormatter = object : CartesianValueFormatter {
            private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            private val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())

            override fun format(value: Float, chartValues: ExtraStore): CharSequence {
                val timestamp = value.toLong()
                val now = System.currentTimeMillis() / 1000
                val daysDiff = (now - timestamp) / (24 * 3600)

                // Show time if within last 24 hours, otherwise show date
                return if (daysDiff < 1) {
                    timeFormat.format(Date(timestamp * 1000))
                } else {
                    dateFormat.format(Date(timestamp * 1000))
                }
            }
        }

        // Initialize Gas Resistance Bar Chart (Column chart in Vico)
        gasResistanceChart.apply {
            modelProducer = gasResistanceModelProducer
            // Zoom and scroll enabled by default in Vico
        }

        // Initialize Particle Matter Line Chart with zoom/scroll
        particleMatterChart.apply {
            modelProducer = particleMatterModelProducer
            // Zoom and scroll enabled by default in Vico
        }

        // Initialize Temperature & Humidity Line Chart with zoom/scroll
        tempHumidityChart.apply {
            modelProducer = tempHumidityModelProducer
            // Zoom and scroll enabled by default in Vico
        }

        // Set initial empty data
        updateGasResistanceChart()
        updateParticleMatterChart()
        updateTempHumidityChart()
    }

    private fun updateGasResistanceChart() {
        if (gasResistanceData.isEmpty()) {
            gasResistanceModelProducer.runTransaction {
                columnSeries()
            }
            return
        }

        // Vico expects x,y pairs where x is the timestamp (in seconds)
        val sortedData = gasResistanceData.sortedBy { it.first }
        val xValues = sortedData.map { it.first.toFloat() }
        val yValues = sortedData.map { it.second }

        gasResistanceModelProducer.runTransaction {
            columnSeries(xValues, yValues)
        }
    }

    private fun updateParticleMatterChart() {
        // Limit data points
        while (pm10Data.size > MAX_CHART_ENTRIES) pm10Data.removeAt(0)
        while (pm25Data.size > MAX_CHART_ENTRIES) pm25Data.removeAt(0)
        while (pm1Data.size > MAX_CHART_ENTRIES) pm1Data.removeAt(0)

        if (pm10Data.isEmpty() && pm25Data.isEmpty() && pm1Data.isEmpty()) {
            particleMatterModelProducer.runTransaction {
                lineSeries()
            }
            return
        }

        particleMatterModelProducer.runTransaction {
            // Vico lineSeries with multiple series for PM10, PM2.5, PM1.0
            // Each series needs its own x and y values
            if (pm10Data.isNotEmpty() && pm25Data.isNotEmpty() && pm1Data.isNotEmpty()) {
                val pm10Sorted = pm10Data.sortedBy { it.first }
                val pm25Sorted = pm25Data.sortedBy { it.first }
                val pm1Sorted = pm1Data.sortedBy { it.first }

                lineSeries(
                    pm10Sorted.map { it.first.toFloat() },  // x values for PM10
                    pm10Sorted.map { it.second },           // y values for PM10
                    pm25Sorted.map { it.second },           // y values for PM2.5 (shares x)
                    pm1Sorted.map { it.second }             // y values for PM1.0 (shares x)
                )
            } else if (pm10Data.isNotEmpty()) {
                val sorted = pm10Data.sortedBy { it.first }
                lineSeries(sorted.map { it.first.toFloat() }, sorted.map { it.second })
            }
        }
    }

    private fun updateTempHumidityChart() {
        // Limit data points
        while (temperatureData.size > MAX_CHART_ENTRIES) temperatureData.removeAt(0)
        while (humidityData.size > MAX_CHART_ENTRIES) humidityData.removeAt(0)

        if (temperatureData.isEmpty() && humidityData.isEmpty()) {
            tempHumidityModelProducer.runTransaction {
                lineSeries()
            }
            return
        }

        tempHumidityModelProducer.runTransaction {
            // Vico lineSeries for Temperature and Humidity
            if (temperatureData.isNotEmpty() && humidityData.isNotEmpty()) {
                val tempSorted = temperatureData.sortedBy { it.first }
                val humSorted = humidityData.sortedBy { it.first }

                lineSeries(
                    tempSorted.map { it.first.toFloat() },  // x values
                    tempSorted.map { it.second },           // y values for temperature
                    humSorted.map { it.second }             // y values for humidity (shares x)
                )
            } else if (temperatureData.isNotEmpty()) {
                val sorted = temperatureData.sortedBy { it.first }
                lineSeries(sorted.map { it.first.toFloat() }, sorted.map { it.second })
            } else if (humidityData.isNotEmpty()) {
                val sorted = humidityData.sortedBy { it.first }
                lineSeries(sorted.map { it.first.toFloat() }, sorted.map { it.second })
            }
        }
    }

    private fun clearChartData() {
        gasResistanceData.clear()
        pm10Data.clear()
        pm25Data.clear()
        pm1Data.clear()
        temperatureData.clear()
        humidityData.clear()

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
        // Lifecycle observer handles service lifecycle automatically
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
