package com.airsens.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("ViewConstructor")
class TimeBasedMarkerView(context: Context, layoutResource: Int) : MarkerView(context, layoutResource) {

    private val markerText: TextView = findViewById(R.id.marker_text)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // This method is called each time the MarkerView is redrawn
    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e == null) {
            return
        }

        val timestamp = e.x
        val lineData = chartView.data as? com.github.mikephil.charting.data.LineData

        if (lineData == null) {
            super.refreshContent(e, highlight)
            return
        }

        val date = Date(timestamp.toLong() * 1000)
        val timeString = timeFormat.format(date)
        val markerContent = StringBuilder("Time: $timeString")

        // Loop through all datasets to find values at the selected timestamp
        for (dataSet in lineData.dataSets) {
            val entry = dataSet.getEntryForXValue(timestamp, Float.NaN, com.github.mikephil.charting.data.DataSet.Rounding.CLOSEST)
            if (entry != null && dataSet.isVisible) {
                // Append the label and value for each dataset
                val valueFormatted = "%.1f".format(entry.y)
                markerContent.append("\n${dataSet.label}: $valueFormatted")
            }
        }

        markerText.text = markerContent.toString()
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        // This determines the position of the marker view relative to the selected point
        return MPPointF(-(width / 2f), -height.toFloat() - 10f)
    }
}
