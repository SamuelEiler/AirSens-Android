package com.airsens.monitor

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Application class for starting persistent BLE connection service
 */
class AirSensApp : Application() {

    companion object {
        private const val TAG = "AirSensApp"

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
        Log.i(TAG, "   Starting persistent BLE connection service")
        Log.i(TAG, "========================================")

        // Start persistent BLE connection service (industry standard)
        startPersistentBleService()
    }

    private fun startPersistentBleService() {
        val intent = Intent(this, BleLiveConnectionService::class.java).apply {
            action = BleLiveConnectionService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
                Log.i(TAG, "✓ Persistent BLE service started (foreground)")
            } else {
                startService(intent)
                Log.i(TAG, "✓ Persistent BLE service started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE service", e)
        }
    }
}
