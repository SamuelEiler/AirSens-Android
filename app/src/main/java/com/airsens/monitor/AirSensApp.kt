package com.airsens.monitor

import android.app.Application
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Application class for scheduling WorkManager periodic sync
 */
class AirSensApp : Application() {

    companion object {
        private const val TAG = "AirSensApp"
        const val WORK_NAME = "sensor-periodic-sync"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application started")

        // Schedule periodic background sync with WorkManager
        schedulePeriodicSync()
    }

    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Works offline
            .setRequiresBatteryNotLow(false) // Works even on low battery
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SensorSyncWorker>(
            15, TimeUnit.MINUTES // Minimum interval for WorkManager
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
            syncRequest
        )

        Log.i(TAG, "✓ Scheduled periodic background sync every 15 minutes")
    }
}
