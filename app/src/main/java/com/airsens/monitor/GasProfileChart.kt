package com.airsens.monitor

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

data class GasProfileDataPoint(
    val timestamp: Long,
    val gasResistanceArray: IntArray,
    val gasResistanceProfile: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GasProfileDataPoint
        return timestamp == other.timestamp &&
                gasResistanceArray.contentEquals(other.gasResistanceArray) &&
                gasResistanceProfile == other.gasResistanceProfile
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + gasResistanceArray.contentHashCode()
        result = 31 * result + gasResistanceProfile
        return result
    }
}

class GasProfileChart(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private lateinit var barChart: BarChart
    private lateinit var timeSlider: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var noDataText: TextView

    private val gasProfileData = mutableListOf<GasProfileDataPoint>()
    private val heaterColors = listOf(
        Color.rgb(26, 82, 165),    // 1 - Blue
        Color.rgb(56, 142, 60),    // 2 - Green
        Color.rgb(217, 48, 37),    // 3 - Red
        Color.rgb(251, 140, 0),    // 4 - Orange
        Color.rgb(142, 36, 170),   // 5 - Purple
        Color.rgb(0, 150, 136),    // 6 - Teal
        Color.rgb(233, 30, 99),    // 7 - Pink
        Color.rgb(63, 81, 181),    // 8 - Indigo
        Color.rgb(255, 152, 0),    // 9 - Amber
        Color.rgb(76, 175, 80)     // 10 - Light Green
    )

    init {
        LayoutInflater.from(context).inflate(R.layout.gas_profile_chart, this, true)
        initializeViews()
    }

    private fun initializeViews() {
        barChart = findViewById(R.id.gasProfileBarChart)
        timeSlider = findViewById(R.id.gasProfileTimeSlider)
        timeLabel = findViewById(R.id.gasProfileTimeLabel)
        noDataText = findViewById(R.id.gasProfileNoDataText)

        configureBarChart()
        configureTimeSlider()
    }

    private fun configureBarChart() {
        barChart.description.text = "Gas Resistance by Heater Profile (1-10)"
        barChart.description.textSize = 12f
        barChart.setTouchEnabled(false)
        barChart.setDrawGridBackground(false)
        barChart.setPinchZoom(false)
        barChart.setScaleEnabled(false)

        // Disable interactions for profile chart
        barChart.isDragEnabled = false
        barChart.setScaleXEnabled(false)
        barChart.setScaleYEnabled(false)

        // X-axis configuration (heater profiles 1-10)
        val xAxis = barChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "P${value.toInt()}"  // Profile 1-10
            }
        }

        // Y-axis configuration
        barChart.axisLeft.setDrawGridLines(true)
        barChart.axisRight.isEnabled = false
        barChart.axisLeft.axisMinimum = 0f

        // Legend
        barChart.legend.isEnabled = false

        // Interactive marker
        barChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: com.github.mikephil.charting.data.Entry?, h: Highlight?) {
                if (e != null) {
                    val profile = e.x.toInt()
                    val value = e.y.toInt()
                    android.widget.Toast.makeText(
                        context,
                        "Profile $profile: $value Ω",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onNothingSelected() {}
        })
    }

    private fun configureTimeSlider() {
        timeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && gasProfileData.isNotEmpty()) {
                    updateChartForTimePoint(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    fun updateData(measurements: List<com.airsens.monitor.database.MeasurementEntity>) {
        gasProfileData.clear()

        measurements.forEach { measurement ->
            if (measurement.gasResistanceProfile > 0 && measurement.gasResistanceArray != null) {
                try {
                    val arrayJson = JSONArray(measurement.gasResistanceArray)
                    val array = IntArray(arrayJson.length()) { i ->
                        arrayJson.getInt(i)
                    }
                    if (array.size == 10) {
                        gasProfileData.add(GasProfileDataPoint(
                            timestamp = measurement.timestamp,
                            gasResistanceArray = array,
                            gasResistanceProfile = measurement.gasResistanceProfile
                        ))
                    }
                } catch (e: Exception) {
                    // Silently skip malformed entries
                }
            }
        }

        if (gasProfileData.isEmpty()) {
            noDataText.text = "No gas resistance profile data available"
            noDataText.visibility = VISIBLE
            barChart.visibility = GONE
            timeSlider.visibility = GONE
            timeLabel.visibility = GONE
        } else {
            noDataText.visibility = GONE
            barChart.visibility = VISIBLE
            timeSlider.visibility = VISIBLE
            timeLabel.visibility = VISIBLE

            // Configure slider
            timeSlider.max = gasProfileData.size - 1
            timeSlider.progress = gasProfileData.size - 1  // Start at most recent

            // Update chart for the latest data point
            updateChartForTimePoint(timeSlider.progress)
        }
    }

    private fun updateChartForTimePoint(index: Int) {
        if (index < 0 || index >= gasProfileData.size) return

        val dataPoint = gasProfileData[index]

        // Update time label
        val date = Date(dataPoint.timestamp * 1000)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        timeLabel.text = "Time: ${timeFormat.format(date)}"

        // Create bar entries for each heater profile (1-10)
        val entries = mutableListOf<BarEntry>()
        for (i in 0 until 10) {
            // X position is i (0-9), Y value is gas resistance
            entries.add(BarEntry(i.toFloat(), dataPoint.gasResistanceArray[i].toFloat()))
        }

        // Create dataset with all profiles
        val dataSet = BarDataSet(entries, "Gas Resistance by Profile")
        dataSet.setColors(*heaterColors.toIntArray())  // One color per bar
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 9f

        val barData = BarData(dataSet)
        barData.barWidth = 0.8f

        barChart.data = barData

        // Configure X-axis for 0-9 (display as P1-P10)
        val xAxis = barChart.xAxis
        xAxis.axisMinimum = -0.5f
        xAxis.axisMaximum = 9.5f

        barChart.invalidate()
    }
}
