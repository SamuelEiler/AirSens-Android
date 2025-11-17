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
import androidx.lifecycle.ProcessLifecycleOwner
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(applicationContext)

        // Register lifecycle observer for automatic background/live mode switching
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            AppLifecycleObserver(applicationContext)
        )

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
        pm10History.clear()
        pm25History.clear()
        pm1History.clear()
        temperatureHistory.clear()
        humidityHistory.clear()

        if (measurements.isEmpty()) {
            return
        }

        // Use index-based X values to avoid chart rendering issues with timestamps
        // The actual timestamp will be shown in labels
        measurements.forEachIndexed { index, measurement ->
            val xValue = index.toFloat()

            pm10History.add(Entry(xValue, measurement.pm10))
            pm25History.add(Entry(xValue, measurement.pm25))
            pm1History.add(Entry(xValue, measurement.pm1))

            measurement.temperature?.let {
                temperatureHistory.add(Entry(xValue, it))
            }

            measurement.humidity?.let {
                humidityHistory.add(Entry(xValue, it))
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
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        // Show measurement index (starting from 1 for display)
                        return "#${value.toInt() + 1}"
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
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        // Show measurement index (starting from 1 for display)
                        return "#${value.toInt() + 1}"
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
