package com.airsens.monitor.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestamp: Long,        // Sensor timestamp (seconds since epoch)
    val receivedAt: Long,       // When we received it (milliseconds since epoch)

    // Particle Matter
    val pm1: Float,
    val pm25: Float,
    val pm10: Float,

    // Status
    val obstructed: Boolean,
    val timeValid: Boolean,

    // Environmental (BME690)
    val temperature: Float?,
    val humidity: Float?,
    val pressure: Float?,

    // Air Quality
    val iaq: Float?,
    val gasResistance: Float?,
    val iaqAccuracy: Int
)
