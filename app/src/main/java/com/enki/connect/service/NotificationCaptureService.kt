package com.enki.connect.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.content.Intent
import android.util.Log
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service that captures all incoming app notifications.
 * Filters out sensitive apps (like banking) and batches metadata every 5 minutes.
 */
class NotificationCaptureService : NotificationListenerService() {

    private lateinit var prefs: EnkiPrefs
    private lateinit var db: EnkiDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val notificationBuffer = mutableListOf<JSONObject>()
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        prefs = EnkiPrefs(this)
        db = EnkiDatabase.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startBatchLoop()
        }
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!prefs.notificationCaptureEnabled) return

        val packageName = sbn.packageName
        if (isFilteredApp(packageName)) return

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        
        // Skip if both title and text are empty
        if (title.isEmpty() && text.isEmpty()) return

        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val signal = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("app_package", packageName)
            put("app_name", appLabel)
            put("title", title)
            put("text", if (bigText.isNotEmpty()) bigText else text)
            put("category", notification.category ?: "")
        }

        synchronized(notificationBuffer) {
            notificationBuffer.add(signal)
        }
        
        Log.d("NotificationCapture", "Captured notification from $appLabel")
    }

    private fun startBatchLoop() {
        scope.launch {
            while (isRunning) {
                delay(5 * 60 * 1000) // 5 minutes
                flushBatch()
            }
        }
    }

    private suspend fun flushBatch() {
        val copy: List<JSONObject>
        synchronized(notificationBuffer) {
            if (notificationBuffer.isEmpty()) return
            copy = notificationBuffer.toList()
            notificationBuffer.clear()
        }

        val batch = JSONObject().apply {
            put("type", "notification_batch")
            put("device_id", prefs.deviceId)
            put("notifications", JSONArray(copy))
        }

        db.queueDao().insert(
            QueuedSignal(
                endpoint = "/signals/ingest",
                contentType = "application/json",
                bodyJson = batch.toString()
            )
        )

        UploadWorker.triggerNow(this@NotificationCaptureService)
    }

    private fun isFilteredApp(packageName: String): Boolean {
        val filteredPackages = listOf(
            "com.android.settings",
            "com.android.systemui"
            // Add banking apps or other sensitive packages here
        )
        return filteredPackages.contains(packageName)
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }
}
