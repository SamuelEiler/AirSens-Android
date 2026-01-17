package com.airsens.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airsens.monitor.database.AppDatabase
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 2
        private const val MAX_CHART_ENTRIES = 500
    }

    private var isSyncing = false
    private lateinit var database: AppDatabase

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var dataContainer: ScrollView
    private lateinit var lastHourButton: Button
    private lateinit var allDataButton: Button
    private lateinit var deleteAllDataButton: Button
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

    // Chart views
    private lateinit var iaqChart: LineChart
    private lateinit var gasProfileChart: GasProfileChart
    private lateinit var particleMatterChart: LineChart
    private lateinit var tempHumidityChart: LineChart

    // Data lists
    private val fullIaqData = mutableListOf<Pair<Long, Float>>()
    private val fullPm10Data = mutableListOf<Pair<Long, Float>>()
    private val fullPm25Data = mutableListOf<Pair<Long, Float>>()
    private val fullPm1Data = mutableListOf<Pair<Long, Float>>()
    private val fullTemperatureData = mutableListOf<Pair<Long, Float>>()
    private val fullHumidityData = mutableListOf<Pair<Long, Float>>()
    private val iaqData = mutableListOf<Pair<Long, Float>>()
    private val pm10Data = mutableListOf<Pair<Long, Float>>()
    private val pm25Data = mutableListOf<Pair<Long, Float>>()
    private val pm1Data = mutableListOf<Pair<Long, Float>>()
    private val temperatureData = mutableListOf<Pair<Long, Float>>()
    private val humidityData = mutableListOf<Pair<Long, Float>>()
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
        loadHistoricalData()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        dataContainer = findViewById(R.id.dataContainer)
        bleConnectionStatus = findViewById(R.id.bleConnectionStatus)
        lastDataReceived = findViewById(R.id.lastDataReceived)
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
        iaqChart = findViewById(R.id.iaqChart)
        gasProfileChart = findViewById(R.id.gasProfileChart)
        particleMatterChart = findViewById(R.id.particleMatterChart)
        tempHumidityChart = findViewById(R.id.tempHumidityChart)
        lastHourButton = findViewById(R.id.lastHourButton)
        allDataButton = findViewById(R.id.allDataButton)
        deleteAllDataButton = findViewById(R.id.deleteAllDataButton)

        lastHourButton.setOnClickListener { filterChartsToLastHour() }
        allDataButton.setOnClickListener { showAllChartData() }
        deleteAllDataButton.setOnClickListener { showDeleteConfirmationDialog() }

        initializeCharts()
    }
    
    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Deletion")
            .setMessage("Are you sure you want to delete all historical data? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteAllData() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAllData() {
        lifecycleScope.launch {
            database.measurementDao().deleteAll()
            clearChartData()
            runOnUiThread {
                Toast.makeText(this@MainActivity, "All data has been deleted.", Toast.LENGTH_SHORT).show()
                pm10Text.text = "PM10: -- µg/m³"
                pm25Text.text = "PM2.5: -- µg/m³"
                pm1Text.text = "PM1.0: -- µg/m³"
                temperatureText.text = "Temperature: -- °C"
                humidityText.text = "Humidity: -- %"
                pressureText.text = "Pressure: -- hPa"
                iaqText.text = "IAQ: --"
                gasResistanceText.text = "Gas Resistance: -- Ω"
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun observeDatabaseChanges() {
        lifecycleScope.launch {
            database.measurementDao().getAllFlow().collect { measurements ->
                if (measurements.isNotEmpty()) {
                    runOnUiThread {
                        bleConnectionStatus.text = "BLE: Connected & Receiving Data"
                        bleConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                        val latest = measurements.first()
                        val dataAge = (System.currentTimeMillis() / 1000) - latest.timestamp
                        lastDataReceived.text = when {
                            dataAge < 5 -> "Last data: Just now"
                            dataAge < 60 -> "Last data: ${dataAge}s ago"
                            else -> "Last data: ${dataAge / 60}m ago"
                        }
                    }
                    updateUIWithMeasurements(measurements.take(MAX_CHART_ENTRIES))
                }
            }
        }
    }

    private fun loadHistoricalData() {
        lifecycleScope.launch {
            try {
                val measurements = database.measurementDao().getLastN(MAX_CHART_ENTRIES)
                if (measurements.isEmpty()) {
                    runOnUiThread {
                        bleConnectionStatus.text = "BLE: Waiting for data..."
                        bleConnectionStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                    }
                    return@launch
                }
                updateUIWithMeasurements(measurements)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading historical data", e)
            }
        }
    }

    private fun updateUIWithMeasurements(measurements: List<com.airsens.monitor.database.MeasurementEntity>) {
        val distinctMeasurements = measurements.distinctBy { it.timestamp }

        fullIaqData.clear(); fullPm10Data.clear(); fullPm25Data.clear(); fullPm1Data.clear(); fullTemperatureData.clear(); fullHumidityData.clear(); allMeasurements.clear()
        allMeasurements.addAll(distinctMeasurements)
        distinctMeasurements.forEach {
            val ts = it.timestamp
            fullPm10Data.add(Pair(ts, it.pm10)); fullPm25Data.add(Pair(ts, it.pm25)); fullPm1Data.add(Pair(ts, it.pm1))
            it.temperature?.let { t -> fullTemperatureData.add(Pair(ts, t)) }
            it.humidity?.let { h -> fullHumidityData.add(Pair(ts, h)) }
            it.iaq?.let { i -> fullIaqData.add(Pair(ts, i)) }
        }
        showAllChartData()
        runOnUiThread {
            try {
                val latest = distinctMeasurements.first()
                timestampText.text = "Last Update: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(latest.timestamp * 1000))}"
                pm10Text.text = "PM10: %.2f µg/m³".format(latest.pm10)
                pm25Text.text = "PM2.5: %.2f µg/m³".format(latest.pm25)
                pm1Text.text = "PM1.0: %.2f µg/m³".format(latest.pm1)
                latest.temperature?.let { temperatureText.text = "Temperature: %.1f°C".format(it); temperatureText.visibility = View.VISIBLE }
                latest.humidity?.let { humidityText.text = "Humidity: %.1f%%".format(it); humidityText.visibility = View.VISIBLE }
                latest.pressure?.let { pressureText.text = "Pressure: %.1f hPa".format(it); pressureText.visibility = View.VISIBLE }
                latest.iaq?.let { iaqText.text = "IAQ: %.1f".format(it); iaqText.visibility = View.VISIBLE }
                latest.gasResistance?.let { gasResistanceText.text = "Gas Resistance: %.0f Ω".format(it); gasResistanceText.visibility = View.VISIBLE }
                obstructedText.text = if (latest.obstructed) "⚠ Sensor Obstructed" else "✓ Sensor Clear"
                obstructedText.setTextColor(if (latest.obstructed) ContextCompat.getColor(this, android.R.color.holo_red_dark) else ContextCompat.getColor(this, android.R.color.holo_green_dark))
                iaqAccuracyText.text = "IAQ Accuracy: ${getIAQAccuracyString(latest.iaqAccuracy)}"
                dataContainer.visibility = View.VISIBLE
            } catch (e: Exception) { Log.e(TAG, "Error updating UI text", e) }
        }
    }

    private fun initializeCharts() {
        configureIaqChart()
        configureParticleMatterChart()
        configureTempHumidityChart()
        updateIaqChart(true); updateGasProfileChart(); updateParticleMatterChart(true); updateTempHumidityChart(true)
        iaqChart.onChartGestureListener = createGestureListener(iaqChart)
        particleMatterChart.onChartGestureListener = createGestureListener(particleMatterChart)
        tempHumidityChart.onChartGestureListener = createGestureListener(tempHumidityChart)
    }

    private fun configureLineChart(chart: LineChart, description: String) {
        chart.description.text = description; chart.description.textSize = 12f; chart.setTouchEnabled(true); chart.setDrawGridBackground(false); chart.setPinchZoom(true); chart.setScaleEnabled(true); chart.isDragEnabled = true; chart.setScaleXEnabled(true); chart.setScaleYEnabled(false); chart.isAutoScaleMinMaxEnabled = true;         chart.setVisibleXRangeMaximum(120f)
        chart.setVisibleXRangeMinimum(60f)
        val xAxis = chart.xAxis; xAxis.position = XAxis.XAxisPosition.BOTTOM; xAxis.setDrawGridLines(true); xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() { private val tf = SimpleDateFormat("HH:mm", Locale.getDefault()); override fun getFormattedValue(value: Float) = tf.format(Date(value.toLong() * 1000)) }
        chart.axisLeft.setDrawGridLines(true); chart.axisRight.isEnabled = false
        chart.legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP; chart.legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT; chart.legend.orientation = Legend.LegendOrientation.VERTICAL; chart.legend.setDrawInside(true)
    }

    private fun configureIaqChart() { configureLineChart(iaqChart, "IAQ (Indoor Air Quality Index)"); val m = TimeBasedMarkerView(this, R.layout.chart_marker_view); m.chartView = iaqChart; iaqChart.marker = m; iaqChart.invalidate() }
    private fun configureParticleMatterChart() { configureLineChart(particleMatterChart, "Particle Matter (µg/m³)"); val m = TimeBasedMarkerView(this, R.layout.chart_marker_view); m.chartView = particleMatterChart; particleMatterChart.marker = m; particleMatterChart.invalidate() }
    private fun configureTempHumidityChart() { configureLineChart(tempHumidityChart, "Temperature (°C) & Humidity (%)"); val m = TimeBasedMarkerView(this, R.layout.chart_marker_view); m.chartView = tempHumidityChart; tempHumidityChart.marker = m; tempHumidityChart.invalidate() }

    private fun updateIaqChart(forceResetView: Boolean = false) {
        val entries = iaqData.sortedBy { it.first }.map { (ts, v) -> Entry(ts.toFloat(), v) }
        if (iaqChart.data == null) { iaqChart.data = LineData(LineDataSet(entries, "IAQ").apply { color = Color.rgb(255, 152, 0); setCircleColor(color); lineWidth = 2f; circleRadius = 1.5f; setDrawValues(false); mode = LineDataSet.Mode.LINEAR }) }
        else { (iaqChart.data.getDataSetByLabel("IAQ", true) as LineDataSet).apply { clear(); entries.forEach { addEntryOrdered(it) } }; iaqChart.data.notifyDataChanged(); iaqChart.notifyDataSetChanged() }
        if (forceResetView || iaqChart.viewPortHandler.scaleX <= 1f) { entries.lastOrNull()?.let { iaqChart.moveViewToX(it.x) } }; iaqChart.invalidate()
    }

    private fun updateGasProfileChart() { gasProfileChart.updateData(allMeasurements) }

    private fun updateParticleMatterChart(forceResetView: Boolean = false) {
        val pm10e = pm10Data.sortedBy { it.first }.map { (ts, v) -> Entry(ts.toFloat(), v) }; val pm25e = pm25Data.sortedBy { it.first }.map { (ts, v) -> Entry(ts.toFloat(), v) }; val pm1e = pm1Data.sortedBy { it.first }.map { (ts, v) -> Entry(ts.toFloat(), v) }
        if (particleMatterChart.data == null) {
            val sets = mutableListOf<ILineDataSet>(); sets.add(LineDataSet(pm10e, "PM10 (µg/m³)") .apply { color = Color.rgb(255, 99, 71); setCircleColor(color); lineWidth = 2f; circleRadius = 1.5f; setDrawValues(false) }); sets.add(LineDataSet(pm25e, "PM2.5 (µg/m³)") .apply { color = Color.rgb(255, 165, 0); setCircleColor(color); lineWidth = 2f; circleRadius = 1.5f; setDrawValues(false) }); sets.add(LineDataSet(pm1e, "PM1.0 (µg/m³)") .apply { color = Color.rgb(135, 206, 250); setCircleColor(color); lineWidth = 2f; circleRadius = 1.5f; setDrawValues(false) }); particleMatterChart.data = LineData(sets)
        } else {
            (particleMatterChart.data.getDataSetByLabel("PM10 (µg/m³)", true) as LineDataSet).apply { clear(); pm10e.forEach { addEntryOrdered(it) } }; (particleMatterChart.data.getDataSetByLabel("PM2.5 (µg/m³)", true) as LineDataSet).apply { clear(); pm25e.forEach { addEntryOrdered(it) } }; (particleMatterChart.data.getDataSetByLabel("PM1.0 (µg/m³)", true) as LineDataSet).apply { clear(); pm1e.forEach { addEntryOrdered(it) } }
            particleMatterChart.data.notifyDataChanged(); particleMatterChart.notifyDataSetChanged()
        }
        if (forceResetView || particleMatterChart.viewPortHandler.scaleX <= 1f) { val lastX = pm10e.lastOrNull()?.x ?: pm25e.lastOrNull()?.x ?: pm1e.lastOrNull()?.x ?: 0f; if (lastX > 0f) particleMatterChart.moveViewToX(lastX) }
        particleMatterChart.invalidate()
    }

    private fun updateTempHumidityChart(forceResetView: Boolean = false) {
        val tempE = temperatureData.sortedBy { it.first }.map { (ts, v) -> Entry(ts.toFloat(), v) }; val humE = humidityData.sortedBy { it.first }.map { (ts, v) -> Entry(ts.toFloat(), v) }
        if (tempHumidityChart.data == null) {
            val sets = mutableListOf<ILineDataSet>(); sets.add(LineDataSet(tempE, "Temperature (°C)").apply { color = Color.parseColor("#FF5722"); setCircleColor(color); lineWidth = 2.5f; circleRadius = 1.5f; setDrawValues(false); mode = LineDataSet.Mode.LINEAR }); sets.add(LineDataSet(humE, "Humidity (%)").apply { color = Color.parseColor("#2196F3"); setCircleColor(color); lineWidth = 2.5f; circleRadius = 1.5f; setDrawValues(false); mode = LineDataSet.Mode.LINEAR }); tempHumidityChart.data = LineData(sets)
        } else {
            (tempHumidityChart.data.getDataSetByLabel("Temperature (°C)", true) as LineDataSet).apply { clear(); tempE.forEach { addEntryOrdered(it) } }; (tempHumidityChart.data.getDataSetByLabel("Humidity (%)", true) as LineDataSet).apply { clear(); humE.forEach { addEntryOrdered(it) } }
            tempHumidityChart.data.notifyDataChanged(); tempHumidityChart.notifyDataSetChanged()
        }
        if (forceResetView || tempHumidityChart.viewPortHandler.scaleX <= 1f) { val lastX = tempE.lastOrNull()?.x ?: humE.lastOrNull()?.x ?: 0f; if (lastX > 0f) tempHumidityChart.moveViewToX(lastX) }
        tempHumidityChart.invalidate()
    }

    private fun clearChartData() {
        iaqData.clear(); pm10Data.clear(); pm25Data.clear(); pm1Data.clear(); temperatureData.clear(); humidityData.clear()
        updateIaqChart(true); updateParticleMatterChart(true); updateTempHumidityChart(true)
    }

    private fun filterChartsToLastHour() {
        val oneHourAgo = (System.currentTimeMillis() / 1000) - 3600
        iaqData.clear(); iaqData.addAll(fullIaqData.filter { it.first >= oneHourAgo }); pm10Data.clear(); pm10Data.addAll(fullPm10Data.filter { it.first >= oneHourAgo }); pm25Data.clear(); pm25Data.addAll(fullPm25Data.filter { it.first >= oneHourAgo }); pm1Data.clear(); pm1Data.addAll(fullPm1Data.filter { it.first >= oneHourAgo }); temperatureData.clear(); temperatureData.addAll(fullTemperatureData.filter { it.first >= oneHourAgo }); humidityData.clear(); humidityData.addAll(fullHumidityData.filter { it.first >= oneHourAgo })
        updateIaqChart(true); updateGasProfileChart(); updateParticleMatterChart(true); updateTempHumidityChart(true)
        Toast.makeText(this, "Showing data from last hour", Toast.LENGTH_SHORT).show()
    }

    private fun showAllChartData() {
        iaqData.clear(); iaqData.addAll(fullIaqData); pm10Data.clear(); pm10Data.addAll(fullPm10Data); pm25Data.clear(); pm25Data.addAll(fullPm25Data); pm1Data.clear(); pm1Data.addAll(fullPm1Data); temperatureData.clear(); temperatureData.addAll(fullTemperatureData); humidityData.clear(); humidityData.addAll(fullHumidityData)
        updateIaqChart(true); updateGasProfileChart(); updateParticleMatterChart(true); updateTempHumidityChart(true)
    }

    private fun createGestureListener(sourceChart: LineChart): OnChartGestureListener {
        return object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) { syncCharts(sourceChart) }
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) { syncCharts(sourceChart) }
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) { syncCharts(sourceChart) }
        }
    }

    private fun syncCharts(sourceChart: LineChart) {
        if (isSyncing) return
        isSyncing = true
        val sourceMatrix = sourceChart.viewPortHandler.matrixTouch
        val charts = listOf(iaqChart, particleMatterChart, tempHumidityChart)
        for (targetChart in charts) {
            if (targetChart != sourceChart) {
                targetChart.viewPortHandler.matrixTouch.set(sourceMatrix)
                targetChart.invalidate()
            }
        }
        isSyncing = false
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
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (!grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions required for BLE scanning", Toast.LENGTH_LONG).show()
            }
        }
    }
}