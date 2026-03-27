package com.enki.connect.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.enki.connect.EnkiConnectApp
import com.enki.connect.R
import com.enki.connect.data.api.EnkiApiClient
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Foreground service for continuous GPS tracking.
 * Sends pings to Enki server at configurable intervals (5s - 1h).
 * Queues pings to Room DB if server is unreachable.
 */
class LocationService : Service() {

    private lateinit var prefs: EnkiPrefs
    private lateinit var apiClient: EnkiApiClient
    private lateinit var db: EnkiDatabase
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastSentLat = 0.0
    private var lastSentLon = 0.0

    override fun onCreate() {
        super.onCreate()
        prefs = EnkiPrefs(this)
        apiClient = EnkiApiClient(prefs)
        db = EnkiDatabase.getInstance(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    // Dead-zone suppression: skip if moved less than 10m
                    val dist = floatArrayOf(0f)
                    android.location.Location.distanceBetween(
                        lastSentLat, lastSentLon,
                        location.latitude, location.longitude, dist
                    )
                    if (lastSentLat != 0.0 && dist[0] < 10f) return

                    lastSentLat = location.latitude
                    lastSentLon = location.longitude

                    scope.launch {
                        sendPing(location)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Specifying FOREGROUND_SERVICE_TYPE_LOCATION is required for Android 14+ (SDK 34+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLocationUpdates() {
        val intervalMs = (prefs.gpsIntervalSeconds * 1000).toLong()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Permission not granted
            stopSelf()
        }
    }

    private suspend fun sendPing(location: android.location.Location) {
        val battery = getBatteryLevel()

        val success = apiClient.sendGpsPing(
            lat = location.latitude,
            lon = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speed = if (location.hasSpeed()) location.speed else null,
            bearing = if (location.hasBearing()) location.bearing else null,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            battery = battery,
            timestamp = location.time
        )

        if (success) {
            prefs.incrementSignals()
        } else {
            // Queue for retry
            val body = JSONObject().apply {
                put("lat", location.latitude)
                put("lon", location.longitude)
                if (location.hasAltitude()) put("altitude", location.altitude)
                if (location.hasSpeed()) put("speed", location.speed.toDouble())
                if (location.hasBearing()) put("bearing", location.bearing.toDouble())
                if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
                battery?.let { put("battery", it) }
                put("timestamp", location.time)
            }
            db.queueDao().insert(
                QueuedSignal(
                    endpoint = "/signals/geo/ping",
                    contentType = "application/json",
                    bodyJson = body.toString()
                )
            )
        }
    }

    private fun getBatteryLevel(): Int? {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100) / scale else null
        }
    }

    private fun buildNotification(): Notification {
        val interval = prefs.gpsIntervalSeconds
        val label = when {
            interval < 60 -> "${interval}s"
            interval < 3600 -> "${interval / 60}m"
            else -> "1h"
        }

        return NotificationCompat.Builder(this, EnkiConnectApp.CHANNEL_TRACKING)
            .setContentTitle("Enki Pulse")
            .setContentText("Tracking active (every $label)")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, LocationService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}
