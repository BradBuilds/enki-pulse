package com.enki.connect.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.enki.connect.data.prefs.EnkiPrefs
import com.enki.connect.service.LocationService
import com.enki.connect.service.TrafficLoggerService

/**
 * Restarts background services when the device reboots.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = EnkiPrefs(context)
            if (prefs.isPaired) {
                if (prefs.gpsEnabled) {
                    LocationService.start(context)
                }
                if (prefs.trafficLogEnabled) {
                    TrafficLoggerService.start(context)
                }
            }
        }
    }
}
