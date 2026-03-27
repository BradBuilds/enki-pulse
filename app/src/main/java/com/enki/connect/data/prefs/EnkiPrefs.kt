package com.enki.connect.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted credential storage for Enki server connection.
 * Stores: server URL, device token, device ID, tenant ID, feature config.
 */
class EnkiPrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "enki_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ─── Pairing credentials ─────────────────────────────────────────
    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit().putString("server_url", value).apply()

    var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(value) = prefs.edit().putString("device_token", value).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(value) = prefs.edit().putString("device_id", value).apply()

    var tenantId: String
        get() = prefs.getString("tenant_id", "") ?: ""
        set(value) = prefs.edit().putString("tenant_id", value).apply()

    val isPaired: Boolean
        get() = serverUrl.isNotBlank() && deviceId.isNotBlank() && tenantId.isNotBlank()

    // ─── Feature config ──────────────────────────────────────────────
    var gpsIntervalSeconds: Int
        get() = prefs.getInt("gps_interval_s", 30)
        set(value) = prefs.edit().putInt("gps_interval_s", value).apply()

    var gpsEnabled: Boolean
        get() = prefs.getBoolean("gps_enabled", true)
        set(value) = prefs.edit().putBoolean("gps_enabled", value).apply()

    var trafficLogEnabled: Boolean
        get() = prefs.getBoolean("traffic_log_enabled", false)
        set(value) = prefs.edit().putBoolean("traffic_log_enabled", value).apply()

    var photoUploadEnabled: Boolean
        get() = prefs.getBoolean("photo_upload_enabled", true)
        set(value) = prefs.edit().putBoolean("photo_upload_enabled", value).apply()

    var notificationCaptureEnabled: Boolean
        get() = prefs.getBoolean("notification_capture_enabled", false)
        set(value) = prefs.edit().putBoolean("notification_capture_enabled", value).apply()

    var emailSyncEnabled: Boolean
        get() = prefs.getBoolean("email_sync_enabled", false)
        set(value) = prefs.edit().putBoolean("email_sync_enabled", value).apply()

    var wifiScanEnabled: Boolean
        get() = prefs.getBoolean("wifi_scan_enabled", false)
        set(value) = prefs.edit().putBoolean("wifi_scan_enabled", value).apply()

    var sensorEnabled: Boolean
        get() = prefs.getBoolean("sensor_enabled", false)
        set(value) = prefs.edit().putBoolean("sensor_enabled", value).apply()

    // Added missing properties
    var appUsageTrackingEnabled: Boolean
        get() = prefs.getBoolean("app_usage_tracking_enabled", false)
        set(value) = prefs.edit().putBoolean("app_usage_tracking_enabled", value).apply()

    var calendarSyncEnabled: Boolean
        get() = prefs.getBoolean("calendar_sync_enabled", false)
        set(value) = prefs.edit().putBoolean("calendar_sync_enabled", value).apply()

    var bluetoothScanEnabled: Boolean
        get() = prefs.getBoolean("bluetooth_scan_enabled", false)
        set(value) = prefs.edit().putBoolean("bluetooth_scan_enabled", value).apply()

    var wifiOnlyForLargeFiles: Boolean
        get() = prefs.getBoolean("wifi_only_large_files", true)
        set(value) = prefs.edit().putBoolean("wifi_only_large_files", value).apply()

    // ─── Stats ───────────────────────────────────────────────────────
    var signalsSent: Long
        get() = prefs.getLong("signals_sent", 0)
        set(value) = prefs.edit().putLong("signals_sent", value).apply()

    var lastSyncTime: Long
        get() = prefs.getLong("last_sync_time", 0)
        set(value) = prefs.edit().putLong("last_sync_time", value).apply()

    fun incrementSignals(count: Int = 1) {
        signalsSent += count
        lastSyncTime = System.currentTimeMillis()
    }

    // ─── Pairing ─────────────────────────────────────────────────────
    fun savePairing(server: String, token: String, device: String, tenant: String) {
        serverUrl = server
        deviceToken = token
        deviceId = device
        tenantId = tenant
    }

    fun clearPairing() {
        prefs.edit().clear().apply()
    }
}
