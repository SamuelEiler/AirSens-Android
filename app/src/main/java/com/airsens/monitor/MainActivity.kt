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
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 2
        private const val MAX_CHART_ENTRIES = 500 // Max data points to show in history charts
    }

    private lateinit var database: AppDatabase

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var dataContainer: ScrollView
    private lateinit var lastHourButton: Button
    private lateinit var allDataButton: Button
    private lateinit var bleConnectionStatus: TextView
    private lateinit var lastDataReceived: TextView

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

    // Chart views (MPAndroidChart)
    private lateinit var iaqChart: LineChart
    private lateinit var gasProfileChart: GasProfileChart
    private lateinit var particleMatterChart: LineChart
    private lateinit var tempHumidityChart: LineChart

    // Full data storage (unfiltered)
    private val fullIaqData = mutableListOf<Pair<Long, Float>>()
    private val fullPm10Data = mutableListOf<Pair<Long, Float>>()
    private val fullPm25Data = mutableListOf<Pair<Long, Float>>()
    private val fullPm1Data = mutableListOf<Pair<Long, Float>>()
    private val fullTemperatureData = mutableListOf<Pair<Long, Float>>()
    private val fullHumidityData = mutableListOf<Pair<Long, Float>>()

    // Filtered data storage for charts (what's currently displayed)
    private val iaqData = mutableListOf<Pair<Long, Float>>()
    private val pm10Data = mutableListOf<Pair<Long, Float>>()
    private val pm25Data = mutableListOf<Pair<Long, Float>>()
    private val pm1Data = mutableListOf<Pair<Long, Float>>()
    private val temperatureData = mutableListOf<Pair<Long, Float>>()
    private val humidityData = mutableListOf<Pair<Long, Float>>()

    // All measurements for profile chart
    private val allMeasurements = mutableListOf<com.airsens.monitor.database.MeasurementEntity>()

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
        bleConnectionStatus = findViewById(R.id.bleConnectionStatus)
        lastDataReceived = findViewById(R.id.lastDataReceived)

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
        iaqChart = findViewById(R.id.iaqChart)
        gasProfileChart = findViewById(R.id.gasProfileChart)
        particleMatterChart = findViewById(R.id.particleMatterChart)
        tempHumidityChart = findViewById(R.id.tempHumidityChart)

        // Chart control buttons
        lastHourButton = findViewById(R.id.lastHourButton)
        allDataButton = findViewById(R.id.allDataButton)

        // Set up button click listeners
        lastHourButton.setOnClickListener {
            filterChartsToLastHour()
        }

        allDataButton.setOnClickListener {
            showAllChartData()
        }

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
                    // Update debug status
                    runOnUiThread {
                        bleConnectionStatus.text = "BLE: Connected & Receiving Data"
                        bleConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))

                        val latest = measurements.first()
                        val now = System.currentTimeMillis()
                        val dataAge = (now / 1000) - latest.timestamp

                        lastDataReceived.text = when {
                            dataAge < 5 -> "Last data: Just now"
                            dataAge < 60 -> "Last data: ${dataAge}s ago"
                            dataAge < 3600 -> "Last data: ${dataAge / 60}m ago"
                            else -> "Last data: ${dataAge / 3600}h ago"
                        }

                        Log.d(TAG, "📊 Data received! PM10=${latest.pm10}, Age=${dataAge}s")
                    }

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
                Log.d(TAG, "🔍 Database check: Found ${measurements.size} measurements")

                if (measurements.isEmpty()) {
                    Log.w(TAG, "⚠️ No data in database - charts will be empty")
                    runOnUiThread {
                        bleConnectionStatus.text = "BLE: Waiting for data..."
                        bleConnectionStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                    }
                    return@launch
                }

                val latest = measurements.first()
                Log.d(TAG, "📊 Latest data: PM10=${latest.pm10}, PM2.5=${latest.pm25}, " +
                        "Temp=${latest.temperature}, Humidity=${latest.humidity}, " +
                        "GasRes=${latest.gasResistance}, Timestamp=${latest.timestamp}")

                updateUIWithMeasurements(measurements)
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
        fullPm10Data.clear()
        fullPm25Data.clear()
        fullPm1Data.clear()
        fullTemperatureData.clear()
        fullHumidityData.clear()
        fullIaqData.clear()
        allMeasurements.clear()
        allMeasurements.addAll(measurements)

        if (measurements.isEmpty()) {
            return
        }

        // Store all data in full data lists
        measurements.forEach { measurement ->
            val timestamp = measurement.timestamp

            fullPm10Data.add(Pair(timestamp, measurement.pm10))
            fullPm25Data.add(Pair(timestamp, measurement.pm25))
            fullPm1Data.add(Pair(timestamp, measurement.pm1))

            measurement.temperature?.let {
                fullTemperatureData.add(Pair(timestamp, it))
            }

            measurement.humidity?.let {
                fullHumidityData.add(Pair(timestamp, it))
            }

            measurement.iaq?.let {
                fullIaqData.add(Pair(timestamp, it))
            }
        }

        // Log chart data counts
        Log.d(TAG, "📈 Chart data loaded: IAQ=${fullIaqData.size}, " +
                "PM10=${fullPm10Data.size}, PM2.5=${fullPm25Data.size}, " +
                "Temp=${fullTemperatureData.size}, Humidity=${fullHumidityData.size}")

        // By default, show all data (this updates the charts)
        showAllChartData()

        // Update UI text displays on UI thread
        runOnUiThread {
            try {
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
        // Configure IAQ Chart (Line Chart)
        configureLineChart(iaqChart, "IAQ (Indoor Air Quality Index)")

        // Configure Particle Matter Chart (Line Chart with 3 series)
        configureLineChart(particleMatterChart, "Particle Matter (µg/m³)")

        // Configure Temperature/Humidity Chart (Line Chart with 2 series)
        configureLineChart(tempHumidityChart, "Temperature (°C) / Humidity (%)")

        // Set initial empty data
        updateIaqChart()
        updateGasProfileChart()
        updateParticleMatterChart()
        updateTempHumidityChart()
    }

    private fun configureBarChart(chart: BarChart, description: String) {
        chart.description.text = description
        chart.description.textSize = 12f
        chart.setTouchEnabled(true)
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(true)
        chart.setScaleEnabled(true)

        // Enable scrolling/dragging
        chart.isDragEnabled = true
        chart.setScaleXEnabled(true)   // Enable X-axis scaling (time)
        chart.setScaleYEnabled(false)  // Disable Y-axis scaling (auto-scale instead)

        // Auto-scale Y-axis to fit visible data
        chart.isAutoScaleMinMaxEnabled = true

        // Set visible range (show ~50 data points initially, can scroll to see more)
        chart.setVisibleXRangeMaximum(50f * 30f)  // ~25 minutes visible (50 points * 30 seconds)
        chart.setVisibleXRangeMinimum(10f * 30f)  // Min zoom shows 10 points

        // X-axis configuration
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val date = Date(value.toLong() * 1000)
                return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            }
        }

        // Y-axis configuration
        chart.axisLeft.setDrawGridLines(true)
        chart.axisLeft.granularity = 1f
        chart.axisRight.isEnabled = false

        // Legend
        chart.legend.isEnabled = false

        // Interactive marker
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null) {
                    val timestamp = e.x.toLong()
                    val date = Date(timestamp * 1000)
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
                    Toast.makeText(
                        this@MainActivity,
                        "$time\n${String.format("%.0f Ω", e.y)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onNothingSelected() {}
        })
    }

    private fun configureLineChart(chart: LineChart, description: String) {
        chart.description.text = description
        chart.description.textSize = 12f
        chart.setTouchEnabled(true)
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(true)
        chart.setScaleEnabled(true)

        // Enable scrolling/dragging
        chart.isDragEnabled = true
        chart.setScaleXEnabled(true)   // Enable X-axis scaling (time)
        chart.setScaleYEnabled(false)  // Disable Y-axis scaling (auto-scale instead)

        // Auto-scale Y-axis to fit visible data
        chart.isAutoScaleMinMaxEnabled = true

        // Set visible range (show ~50 data points initially, can scroll to see more)
        chart.setVisibleXRangeMaximum(50f * 30f)  // ~25 minutes visible (50 points * 30 seconds)
        chart.setVisibleXRangeMinimum(10f * 30f)  // Min zoom shows 10 points

        // X-axis configuration
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val date = Date(value.toLong() * 1000)
                return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            }
        }

        // Y-axis configuration
        chart.axisLeft.setDrawGridLines(true)
        chart.axisLeft.granularity = 1f
        chart.axisRight.isEnabled = false

        // Legend
        chart.legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
        chart.legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
        chart.legend.orientation = Legend.LegendOrientation.VERTICAL
        chart.legend.setDrawInside(true)
    }

    private fun updateIaqChart() {
        // Limit data points
        while (iaqData.size > MAX_CHART_ENTRIES) iaqData.removeAt(0)

        if (iaqData.isEmpty()) {
            Log.w(TAG, "⚠️ IAQ chart: No data to display")
            iaqChart.clear()
            iaqChart.invalidate()
            return
        }

        Log.d(TAG, "📊 Updating IAQ chart with ${iaqData.size} points")

        // Save current viewport state to preserve user's zoom/pan
        val hadData = iaqChart.data != null && iaqChart.data.entryCount > 0
        val savedLowestVisibleX = if (hadData) iaqChart.lowestVisibleX else null
        val savedHighestVisibleX = if (hadData) iaqChart.highestVisibleX else null

        val entries = iaqData.sortedBy { it.first }.mapIndexed { index, (timestamp, value) ->
            Entry(index.toFloat(), value)
        }

        val dataSet = LineDataSet(entries, "IAQ")
        dataSet.color = Color.rgb(255, 152, 0)  // Orange
        dataSet.setCircleColor(Color.rgb(255, 152, 0))
        dataSet.circleRadius = 3f
        dataSet.lineWidth = 2f
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 9f

        val dataSets = listOf<ILineDataSet>(dataSet)
        val lineData = LineData(dataSets)
        iaqChart.data = lineData

        // Configure X-axis
        iaqChart.xAxis.apply {
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    if (index >= 0 && index < iaqData.size) {
                        val timestamp = iaqData[index].first
                        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                        return sdf.format(java.util.Date(timestamp * 1000))
                    }
                    return ""
                }
            }
        }

        // Restore viewport if user was viewing data, otherwise show latest data
        if (hadData && savedLowestVisibleX != null && savedHighestVisibleX != null) {
            // Preserve user's current view
            iaqChart.moveViewToX(savedLowestVisibleX)
        } else if (entries.isNotEmpty()) {
            // First load: show the most recent data
            iaqChart.moveViewToX(entries.last().x)
        }

        iaqChart.invalidate()
        Log.d(TAG, "✓ IAQ chart updated successfully")
    }

    private fun updateGasProfileChart() {
        Log.d(TAG, "📊 Updating gas profile chart with ${allMeasurements.size} measurements")
        gasProfileChart.updateData(allMeasurements)
    }

    private fun updateParticleMatterChart() {
        // Limit data points
        while (pm10Data.size > MAX_CHART_ENTRIES) pm10Data.removeAt(0)
        while (pm25Data.size > MAX_CHART_ENTRIES) pm25Data.removeAt(0)
        while (pm1Data.size > MAX_CHART_ENTRIES) pm1Data.removeAt(0)

        if (pm10Data.isEmpty() && pm25Data.isEmpty() && pm1Data.isEmpty()) {
            Log.w(TAG, "⚠️ Particle matter chart: No data to display")
            particleMatterChart.clear()
            particleMatterChart.invalidate()
            return
        }

        Log.d(TAG, "📊 Updating PM chart with PM10=${pm10Data.size}, PM2.5=${pm25Data.size}, PM1=${pm1Data.size} points")

        // Save current viewport state to preserve user's view
        val hadData = particleMatterChart.data != null && particleMatterChart.data.entryCount > 0
        val savedLowestVisibleX = if (hadData) particleMatterChart.lowestVisibleX else null

        val dataSets = mutableListOf<ILineDataSet>()
        var lastX = 0f

        if (particleMatterChart.data != null && particleMatterChart.data.dataSetCount > 0) {
            // Update existing data sets
            val existingData = particleMatterChart.data

            // PM10 dataset
            var pm10Entries: List<Entry>? = null
            if (pm10Data.isNotEmpty()) {
                pm10Entries = pm10Data.sortedBy { it.first }.map { (timestamp, value) -> Entry(timestamp.toFloat(), value) }
                var dataSet = existingData.getDataSetByLabel("PM10", false) as? LineDataSet
                if (dataSet != null) {
                    // Recreate dataset to update entries
                    existingData.removeDataSet(dataSet)
                    dataSet = LineDataSet(pm10Entries, "PM10")
                    dataSet.color = Color.rgb(255, 99, 71)
                    dataSet.setCircleColor(Color.rgb(255, 99, 71))
                    dataSet.lineWidth = 2f
                    dataSet.circleRadius = 3f
                    dataSet.setDrawValues(false)
                    existingData.addDataSet(dataSet)
                    lastX = pm10Entries.last().x
                } else {
                    Log.e(TAG, "PM10 DataSet not found. Creating and adding a new one.")
                    dataSet = LineDataSet(pm10Entries, "PM10")
                    dataSet.color = Color.rgb(255, 99, 71)
                    dataSet.setCircleColor(Color.rgb(255, 99, 71))
                    dataSet.lineWidth = 2f
                    dataSet.circleRadius = 3f
                    dataSet.setDrawValues(false)
                    existingData.addDataSet(dataSet) // Directly add to existing LineData
                    lastX = pm10Entries.last().x
                }
            }

            // PM2.5 dataset
            var pm25Entries: List<Entry>? = null
            if (pm25Data.isNotEmpty()) {
                pm25Entries = pm25Data.sortedBy { it.first }.map { (timestamp, value) -> Entry(timestamp.toFloat(), value) }
                var dataSet = existingData.getDataSetByLabel("PM2.5", false) as? LineDataSet
                if (dataSet != null) {
                    // Recreate dataset to update entries
                    existingData.removeDataSet(dataSet)
                    dataSet = LineDataSet(pm25Entries, "PM2.5")
                    dataSet.color = Color.rgb(255, 165, 0)
                    dataSet.setCircleColor(Color.rgb(255, 165, 0))
                    dataSet.lineWidth = 2f
                    dataSet.circleRadius = 3f
                    dataSet.setDrawValues(false)
                    existingData.addDataSet(dataSet)
                    if (pm25Entries.last().x > lastX) lastX = pm25Entries.last().x
                } else {
                    Log.e(TAG, "PM2.5 DataSet not found. Creating and adding a new one.")
                    dataSet = LineDataSet(pm25Entries, "PM2.5")
                    dataSet.color = Color.rgb(255, 165, 0)
                    dataSet.setCircleColor(Color.rgb(255, 165, 0))
                    dataSet.lineWidth = 2f
                    dataSet.circleRadius = 3f
                    dataSet.setDrawValues(false)
                    existingData.addDataSet(dataSet) // Directly add to existing LineData
                    if (pm25Entries.last().x > lastX) lastX = pm25Entries.last().x
                }
            }

            // PM1 dataset
            var pm1Entries: List<Entry>? = null
            if (pm1Data.isNotEmpty()) {
                pm1Entries = pm1Data.sortedBy { it.first }.map { (timestamp, value) -> Entry(timestamp.toFloat(), value) }
                var dataSet = existingData.getDataSetByLabel("PM1", false) as? LineDataSet
                if (dataSet != null) {
                    // Recreate dataset to update entries
                    existingData.removeDataSet(dataSet)
                    dataSet = LineDataSet(pm1Entries, "PM1")
                    dataSet.color = Color.rgb(135, 206, 250)
                    dataSet.setCircleColor(Color.rgb(135, 206, 250))
                    dataSet.lineWidth = 2f
                    dataSet.circleRadius = 3f
                    dataSet.setDrawValues(false)
                    existingData.addDataSet(dataSet)
                    if (pm1Entries.last().x > lastX) lastX = pm1Entries.last().x
                } else {
                    Log.e(TAG, "PM1 DataSet not found. Creating and adding a new one.")
                    dataSet = LineDataSet(pm1Entries, "PM1")
                    dataSet.color = Color.rgb(135, 206, 250)
                    dataSet.setCircleColor(Color.rgb(135, 206, 250))
                    dataSet.lineWidth = 2f
                    dataSet.circleRadius = 3f
                    dataSet.setDrawValues(false)
                    existingData.addDataSet(dataSet) // Directly add to existing LineData
                    if (pm1Entries.last().x > lastX) lastX = pm1Entries.last().x
                }
            }

            particleMatterChart.data.notifyDataChanged()
            particleMatterChart.notifyDataSetChanged()

        } else {
            // Create new data sets if none exist
            val dataSets = mutableListOf<ILineDataSet>() // Re-introduce dataSets declaration

            // PM10 dataset
            var pm10Entries: List<Entry>? = null
            if (pm10Data.isNotEmpty()) {
                pm10Entries = pm10Data.sortedBy { it.first }.map { (timestamp, value) ->
                    Entry(timestamp.toFloat(), value)
                }
                val dataSet = LineDataSet(pm10Entries, "PM10")
                dataSet.color = Color.rgb(255, 99, 71)
                dataSet.setCircleColor(Color.rgb(255, 99, 71))
                dataSet.lineWidth = 2f
                dataSet.circleRadius = 3f
                dataSet.setDrawValues(false)
                dataSets.add(dataSet)
                lastX = pm10Entries.last().x
            }

            // PM2.5 dataset
            var pm25Entries: List<Entry>? = null
            if (pm25Data.isNotEmpty()) {
                pm25Entries = pm25Data.sortedBy { it.first }.map { (timestamp, value) ->
                    Entry(timestamp.toFloat(), value)
                }
                val dataSet = LineDataSet(pm25Entries, "PM2.5")
                dataSet.color = Color.rgb(255, 165, 0)
                dataSet.setCircleColor(Color.rgb(255, 165, 0))
                dataSet.lineWidth = 2f
                dataSet.circleRadius = 3f
                dataSet.setDrawValues(false)
                dataSets.add(dataSet)
                if (pm25Entries.last().x > lastX) lastX = pm25Entries.last().x
            }

            // PM1 dataset
            var pm1Entries: List<Entry>? = null
            if (pm1Data.isNotEmpty()) {
                pm1Entries = pm1Data.sortedBy { it.first }.map { (timestamp, value) ->
                    Entry(timestamp.toFloat(), value)
                }
                val dataSet = LineDataSet(pm1Entries, "PM1")
                dataSet.color = Color.rgb(135, 206, 250)
                dataSet.setCircleColor(Color.rgb(135, 206, 250))
                dataSet.lineWidth = 2f
                dataSet.circleRadius = 3f
                dataSet.setDrawValues(false)
                dataSets.add(dataSet)
                if (pm1Entries.last().x > lastX) lastX = pm1Entries.last().x
            }
            val lineData = LineData(dataSets) // Use the newly declared dataSets
            particleMatterChart.data = lineData
        }

        // Restore viewport if user was viewing data, otherwise show latest data
        if (hadData && savedLowestVisibleX != null) {
            // Preserve user's current view
            particleMatterChart.moveViewToX(savedLowestVisibleX)
        } else {
            // First load: show the most recent data
            particleMatterChart.moveViewToX(lastX)
        }

        particleMatterChart.invalidate()

        Log.d(TAG, "✓ Particle matter chart updated successfully")
    }

    private fun updateTempHumidityChart() {
        // Limit data points
        while (temperatureData.size > MAX_CHART_ENTRIES) temperatureData.removeAt(0)
        while (humidityData.size > MAX_CHART_ENTRIES) humidityData.removeAt(0)

        if (temperatureData.isEmpty() && humidityData.isEmpty()) {
            Log.w(TAG, "⚠️ Temp/Humidity chart: No data to display")
            tempHumidityChart.clear()
            tempHumidityChart.invalidate()
            return
        }

        Log.d(TAG, "📊 Updating Temp/Humidity chart with Temp=${temperatureData.size}, Humidity=${humidityData.size} points")

        // Save current viewport state to preserve user's view
        val hadData = tempHumidityChart.data != null && tempHumidityChart.data.entryCount > 0
        val savedLowestVisibleX = if (hadData) tempHumidityChart.lowestVisibleX else null

        var lastX = 0f

        if (tempHumidityChart.data != null && tempHumidityChart.data.dataSetCount > 0) {
            // Update existing data sets
            val existingData = tempHumidityChart.data

            // Temperature dataset
            var tempEntries: List<Entry>? = null
            if (temperatureData.isNotEmpty()) {
                tempEntries = temperatureData.sortedBy { it.first }.map { (timestamp, value) -> Entry(timestamp.toFloat(), value) }
                var tempDataSet = existingData.getDataSetByLabel("Temperature (°C)", false) as? LineDataSet
                if (tempDataSet != null) {
                    // Recreate dataset to update entries
                    existingData.removeDataSet(tempDataSet)
                    tempDataSet = LineDataSet(tempEntries, "Temperature (°C)")
                    tempDataSet.color = Color.rgb(255, 69, 0)
                    tempDataSet.setCircleColor(Color.rgb(255, 69, 0))
                    tempDataSet.lineWidth = 2f
                    tempDataSet.circleRadius = 3f
                    tempDataSet.setDrawValues(false)
                    existingData.addDataSet(tempDataSet)
                    if (tempEntries.isNotEmpty()) lastX = tempEntries.last().x
                } else {
                    Log.e(TAG, "Temperature DataSet not found. Creating and adding a new one.")
                    tempDataSet = LineDataSet(tempEntries, "Temperature (°C)")
                    tempDataSet.color = Color.rgb(255, 69, 0)
                    tempDataSet.setCircleColor(Color.rgb(255, 69, 0))
                    tempDataSet.lineWidth = 2f
                    tempDataSet.circleRadius = 3f
                    tempDataSet.setDrawValues(false)
                    existingData.addDataSet(tempDataSet) // Add to existing LineData
                    if (tempEntries.isNotEmpty()) lastX = tempEntries.last().x
                }
            }

            // Humidity dataset
            var humEntries: List<Entry>? = null
            if (humidityData.isNotEmpty()) {
                humEntries = humidityData.sortedBy { it.first }.map { (timestamp, value) -> Entry(timestamp.toFloat(), value) }
                var humDataSet = existingData.getDataSetByLabel("Humidity (%)", false) as? LineDataSet
                if (humDataSet != null) {
                    // Recreate dataset to update entries
                    existingData.removeDataSet(humDataSet)
                    humDataSet = LineDataSet(humEntries, "Humidity (%)")
                    humDataSet.color = Color.rgb(30, 144, 255)
                    humDataSet.setCircleColor(Color.rgb(30, 144, 255))
                    humDataSet.lineWidth = 2f
                    humDataSet.circleRadius = 3f
                    humDataSet.setDrawValues(false)
                    existingData.addDataSet(humDataSet)
                    if (humEntries.isNotEmpty() && humEntries.last().x > lastX) lastX = humEntries.last().x
                } else {
                    Log.e(TAG, "Humidity DataSet not found. Creating and adding a new one.")
                    humDataSet = LineDataSet(humEntries, "Humidity (%)")
                    humDataSet.color = Color.rgb(30, 144, 255)
                    humDataSet.setCircleColor(Color.rgb(30, 144, 255))
                    humDataSet.lineWidth = 2f
                    humDataSet.circleRadius = 3f
                    humDataSet.setDrawValues(false)
                    existingData.addDataSet(humDataSet) // Add to existing LineData
                    if (humEntries.isNotEmpty() && humEntries.last().x > lastX) lastX = humEntries.last().x
                }
            }

            tempHumidityChart.data.notifyDataChanged()
            tempHumidityChart.notifyDataSetChanged()

        } else {
            // Create new data sets if none exist
            val newTempHumidityDataSets = mutableListOf<ILineDataSet>()

            // Temperature dataset
            var tempEntries: List<Entry>? = null
            if (temperatureData.isNotEmpty()) {
                tempEntries = temperatureData.sortedBy { it.first }.map { (timestamp, value) ->
                    Entry(timestamp.toFloat(), value)
                }
                val dataSet = LineDataSet(tempEntries, "Temperature (°C)")
                dataSet.color = Color.rgb(255, 69, 0)
                dataSet.setCircleColor(Color.rgb(255, 69, 0))
                dataSet.lineWidth = 2f
                dataSet.circleRadius = 3f
                dataSet.setDrawValues(false)
                newTempHumidityDataSets.add(dataSet)
                lastX = tempEntries.last().x
            }

            // Humidity dataset
            var humEntries: List<Entry>? = null
            if (humidityData.isNotEmpty()) {
                humEntries = humidityData.sortedBy { it.first }.map { (timestamp, value) ->
                    Entry(timestamp.toFloat(), value)
                }
                val dataSet = LineDataSet(humEntries, "Humidity (%)")
                dataSet.color = Color.rgb(30, 144, 255)
                dataSet.setCircleColor(Color.rgb(30, 144, 255))
                dataSet.lineWidth = 2f
                dataSet.circleRadius = 3f
                dataSet.setDrawValues(false)
                newTempHumidityDataSets.add(dataSet)
                if (humEntries.isNotEmpty() && humEntries.last().x > lastX) lastX = humEntries.last().x
            }

            val lineData = LineData(newTempHumidityDataSets)
            tempHumidityChart.data = lineData
        }

        // Restore viewport if user was viewing data, otherwise show latest data
        if (hadData && savedLowestVisibleX != null) {
            // Preserve user's current view
            tempHumidityChart.moveViewToX(savedLowestVisibleX)
        } else {
            // First load: show the most recent data
            tempHumidityChart.moveViewToX(lastX)
        }

        tempHumidityChart.invalidate()

        Log.d(TAG, "✓ Temp/Humidity chart updated successfully")
    }

    private fun clearChartData() {
        iaqData.clear()
        pm10Data.clear()
        pm25Data.clear()
        pm1Data.clear()
        temperatureData.clear()
        humidityData.clear()

        updateIaqChart()
        updateParticleMatterChart()
        updateTempHumidityChart()
    }

    private fun filterChartsToLastHour() {
        val now = System.currentTimeMillis() / 1000 // Current time in seconds
        val oneHourAgo = now - 3600 // 1 hour = 3600 seconds

        // Filter all data to last hour
        iaqData.clear()
        iaqData.addAll(fullIaqData.filter { it.first >= oneHourAgo })

        pm10Data.clear()
        pm10Data.addAll(fullPm10Data.filter { it.first >= oneHourAgo })

        pm25Data.clear()
        pm25Data.addAll(fullPm25Data.filter { it.first >= oneHourAgo })

        pm1Data.clear()
        pm1Data.addAll(fullPm1Data.filter { it.first >= oneHourAgo })

        temperatureData.clear()
        temperatureData.addAll(fullTemperatureData.filter { it.first >= oneHourAgo })

        humidityData.clear()
        humidityData.addAll(fullHumidityData.filter { it.first >= oneHourAgo })

        // Update all charts with filtered data
        updateIaqChart()
        updateGasProfileChart()
        updateParticleMatterChart()
        updateTempHumidityChart()

        Toast.makeText(this, "Showing data from last hour", Toast.LENGTH_SHORT).show()
    }

    private fun showAllChartData() {
        // Copy all full data to filtered data
        iaqData.clear()
        iaqData.addAll(fullIaqData)

        pm10Data.clear()
        pm10Data.addAll(fullPm10Data)

        pm25Data.clear()
        pm25Data.addAll(fullPm25Data)

        pm1Data.clear()
        pm1Data.addAll(fullPm1Data)

        temperatureData.clear()
        temperatureData.addAll(fullTemperatureData)

        humidityData.clear()
        humidityData.addAll(fullHumidityData)

        // Update all charts with full data
        updateIaqChart()
        updateGasProfileChart()
        updateParticleMatterChart()
        updateTempHumidityChart()

        if (fullPm10Data.isNotEmpty()) {
            Toast.makeText(this, "Showing all data (${fullPm10Data.size} points)", Toast.LENGTH_SHORT).show()
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
