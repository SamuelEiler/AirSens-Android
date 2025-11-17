package com.airsens.monitor

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.work.WorkManager

/**
 * Observes app lifecycle to switch between background sync and live mode
 */
class AppLifecycleObserver(private val context: Context) : LifecycleObserver {

    companion object {
        private const val TAG = "AppLifecycleObserver"
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForeground() {
        Log.i(TAG, "📱 App opened - switching to LIVE mode")

        // 1. Cancel WorkManager periodic sync
        WorkManager.getInstance(context).cancelUniqueWork(AirSensApp.WORK_NAME)
        Log.i(TAG, "  ⏸ Paused WorkManager background sync")

        // 2. Start live connection service
        val intent = Intent(context, BleLiveConnectionService::class.java).apply {
            action = BleLiveConnectionService.ACTION_START
        }
        context.startForegroundService(intent)
        Log.i(TAG, "  ▶ Started live connection service")
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackground() {
        Log.i(TAG, "📱 App closed - switching to PERIODIC mode")

        // 1. Stop live connection service
        val intent = Intent(context, BleLiveConnectionService::class.java).apply {
            action = BleLiveConnectionService.ACTION_STOP
        }
        context.stopService(intent)
        Log.i(TAG, "  ⏹ Stopped live connection service")

        // 2. WorkManager will resume automatically (already scheduled in AirSensApp)
        Log.i(TAG, "  ▶ WorkManager background sync will resume")
    }
}
