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

        init {
            Log.i(TAG, "⚡ AirSensApp CLASS LOADED (static initializer)")
        }
    }

    init {
        Log.i(TAG, "🔨 AirSensApp INSTANCE CREATED (constructor)")
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "========================================")
        Log.i(TAG, "🚀 APPLICATION STARTING")
        Log.i(TAG, "========================================")

        // Schedule periodic background sync with WorkManager
        schedulePeriodicSync()

        // Log WorkManager status
        logWorkManagerStatus()
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

        Log.i(TAG, "========================================")
        Log.i(TAG, "✓ WORKMANAGER SCHEDULED")
        Log.i(TAG, "   Interval: Every 15 minutes")
        Log.i(TAG, "   Work name: $WORK_NAME")
        Log.i(TAG, "   Policy: KEEP (won't replace existing)")
        Log.i(TAG, "========================================")
    }

    private fun logWorkManagerStatus() {
        val workManager = WorkManager.getInstance(this)

        // Get work info to check if it's scheduled
        val workInfos = workManager.getWorkInfosForUniqueWork(WORK_NAME)
        workInfos.get().forEach { workInfo ->
            Log.i(TAG, "📊 WorkManager Work Info:")
            Log.i(TAG, "   ID: ${workInfo.id}")
            Log.i(TAG, "   State: ${workInfo.state}")
            Log.i(TAG, "   Run attempt: ${workInfo.runAttemptCount}")

            if (workInfo.state == WorkInfo.State.ENQUEUED) {
                Log.i(TAG, "   ✓ Work is ENQUEUED and will run soon!")
            } else {
                Log.w(TAG, "   ⚠ Work state is ${workInfo.state}")
            }
        }
    }
}
