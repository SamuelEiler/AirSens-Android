package com.airsens.monitor

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import com.github.mikephil.charting.charts.RadarChart
import com.github.mikephil.charting.data.RadarData
import com.github.mikephil.charting.data.RadarDataSet
import com.github.mikephil.charting.data.RadarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.interfaces.datasets.IRadarDataSet
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

    private lateinit var radarChart: RadarChart
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
        radarChart = findViewById(R.id.gasProfileBarChart)
        timeSlider = findViewById(R.id.gasProfileTimeSlider)
        timeLabel = findViewById(R.id.gasProfileTimeLabel)
        noDataText = findViewById(R.id.gasProfileNoDataText)

        configureRadarChart()
        configureTimeSlider()
    }

    private fun configureRadarChart() {
        radarChart.description.text = "Gas Resistance by Heater Profile (1-10)"
        radarChart.description.textSize = 12f
        radarChart.setTouchEnabled(false)

        // X-axis configuration (heater profiles 1-10)
        val xAxis = radarChart.xAxis
        xAxis.setDrawGridLines(true)
        xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "P${(value.toInt() % 10) + 1}"  // Profile 1-10
            }
        }

        // Y-axis configuration
        radarChart.yAxis.setDrawGridLines(true)
        radarChart.yAxis.axisMinimum = 0f

        // Legend
        radarChart.legend.isEnabled = true

        // Interactive marker
        radarChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: com.github.mikephil.charting.data.Entry?, h: Highlight?) {
                if (e != null) {
                    val profile = (e.x.toInt() % 10) + 1
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
            radarChart.visibility = GONE
            timeSlider.visibility = GONE
            timeLabel.visibility = GONE
        } else {
            noDataText.visibility = GONE
            radarChart.visibility = VISIBLE
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

        // Create radar entries for each heater profile (1-10)
        val entries = mutableListOf<RadarEntry>()
        for (i in 0 until 10) {
            // RadarEntry takes (value, label_index)
            entries.add(RadarEntry(dataPoint.gasResistanceArray[i].toFloat()))
        }

        // Create dataset with all profiles
        val dataSet = RadarDataSet(entries, "Gas Resistance (Ω)")
        dataSet.color = Color.rgb(104, 241, 175)  // Cyan/Teal
        dataSet.fillColor = Color.rgb(104, 241, 175)
        dataSet.setDrawFilled(true)
        dataSet.fillAlpha = 70  // Semi-transparent fill
        dataSet.lineWidth = 2f
        dataSet.isDrawHighlightCircleEnabled = true
        dataSet.setHighlightCircleInnerRadius(3f)
        dataSet.setHighlightCircleOuterRadius(4f)
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 9f

        val radarData = RadarData(dataSet)
        radarChart.data = radarData

        // Configure X-axis for 0-9 (display as P1-P10)
        val xAxis = radarChart.xAxis
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "P${value.toInt() + 1}"  // Profile 1-10
            }
        }

        // Get max value for scaling
        val maxValue = dataPoint.gasResistanceArray.maxOrNull()?.toFloat() ?: 100000f
        radarChart.yAxis.axisMaximum = maxValue * 1.1f  // 10% padding

        radarChart.invalidate()
    }
}
