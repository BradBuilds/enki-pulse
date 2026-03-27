package com.enki.connect.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * AccessibilityService that tracks app usage and browser URLs.
 * - Records which app is in the foreground and for how long.
 * - Extracts URL bar content from popular browsers (Chrome, Firefox, Edge, etc.).
 */
class AppTrackerService : AccessibilityService() {

    private lateinit var prefs: EnkiPrefs
    private lateinit var db: EnkiDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var lastPackageName: String? = null
    private var lastUrl: String? = null
    private var appStartTime: Long = 0
    
    private val activityBuffer = mutableListOf<JSONObject>()

    override fun onCreate() {
        super.onCreate()
        prefs = EnkiPrefs(this)
        db = EnkiDatabase.getInstance(this)
        
        // Batch flush loop (every 5 minutes)
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000)
                flushBatch()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!prefs.appUsageTrackingEnabled) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleAppSwitch(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Throttle URL checks to avoid performance hits
                handleUrlCapture(event)
            }
        }
    }

    private fun handleAppSwitch(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == lastPackageName) return

        val now = System.currentTimeMillis()
        
        // Log the end of the previous app session
        if (lastPackageName != null && appStartTime > 0) {
            val durationMs = now - appStartTime
            if (durationMs > 1000) { // Only log if > 1s
                logAppUsage(lastPackageName!!, appStartTime, durationMs)
            }
        }

        // Start new app session
        lastPackageName = packageName
        appStartTime = now
        lastUrl = null // Reset URL for new app
        
        Log.d("AppTracker", "Switched to: $packageName")
    }

    private fun handleUrlCapture(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (!isBrowser(packageName)) return

        val rootNode = rootInActiveWindow ?: return
        val url = findUrlInNode(rootNode)
        
        if (url != null && url != lastUrl) {
            lastUrl = url
            logWebVisit(packageName, url)
        }
        rootNode.recycle()
    }

    private fun findUrlInNode(root: AccessibilityNodeInfo): String? {
        // Known browser URL bar resource IDs
        val urlBarIds = listOf(
            "com.android.chrome:id/url_bar",                                    // Chrome
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",           // Firefox
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",          // Samsung
            "com.microsoft.emmx:id/url_bar",                                    // Edge
            "com.duckduckgo.mobile.android:id/omnibarTextInput"                // DuckDuckGo
        )

        for (id in urlBarIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val url = nodes[0].text?.toString()
                nodes.forEach { it.recycle() }
                if (url != null && (url.contains(".") || url.contains("://"))) {
                    return url
                }
            }
        }
        
        // Fallback: search nodes with "url" in text or content description
        // (Note: recursive search can be slow, but useful for generic browsers)
        return null
    }

    private fun isBrowser(packageName: String): Boolean {
        val browsers = listOf(
            "com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser",
            "com.microsoft.emmx", "com.duckduckgo.mobile.android", "com.brave.browser"
        )
        return browsers.contains(packageName)
    }

    private fun logAppUsage(packageName: String, startTs: Long, durationMs: Long) {
        val signal = JSONObject().apply {
            put("type", "app_usage")
            put("package_name", packageName)
            put("timestamp", startTs)
            put("duration_ms", durationMs)
        }
        synchronized(activityBuffer) {
            activityBuffer.add(signal)
        }
    }

    private fun logWebVisit(browser: String, url: String) {
        val signal = JSONObject().apply {
            put("type", "web_visit")
            put("browser", browser)
            put("url", url)
            put("timestamp", System.currentTimeMillis())
        }
        synchronized(activityBuffer) {
            activityBuffer.add(signal)
        }
        Log.d("AppTracker", "Captured URL: $url")
    }

    private suspend fun flushBatch() {
        val copy: List<JSONObject>
        synchronized(activityBuffer) {
            if (activityBuffer.isEmpty()) return
            copy = activityBuffer.toList()
            activityBuffer.clear()
        }

        val batch = JSONObject().apply {
            put("batch_type", "app_activity_log")
            put("device_id", prefs.deviceId)
            put("activities", JSONArray(copy))
        }

        db.queueDao().insert(
            QueuedSignal(
                endpoint = "/signals/ingest",
                contentType = "application/json",
                bodyJson = batch.toString()
            )
        )

        UploadWorker.triggerNow(this@AppTrackerService)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
