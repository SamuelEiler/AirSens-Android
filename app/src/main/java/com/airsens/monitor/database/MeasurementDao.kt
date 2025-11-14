package com.airsens.monitor.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: MeasurementEntity): Long

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestMeasurements(limit: Int): List<MeasurementEntity>

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastN(limit: Int): List<MeasurementEntity>

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): MeasurementEntity?

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    suspend fun getMeasurementsSince(startTime: Long): List<MeasurementEntity>

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun getCount(): Int

    @Query("DELETE FROM measurements WHERE id IN (SELECT id FROM measurements ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()

    // Keep only the last N measurements
    suspend fun keepOnlyLast(count: Int) {
        val total = getCount()
        if (total > count) {
            deleteOldest(total - count)
        }
    }
}
