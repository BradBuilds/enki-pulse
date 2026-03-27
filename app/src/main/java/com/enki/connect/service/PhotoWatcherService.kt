package com.enki.connect.service

import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Service that watches MediaStore for new photos and queues them for upload.
 */
class PhotoWatcherService : Service() {

    private lateinit var prefs: EnkiPrefs
    private lateinit var db: EnkiDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var contentObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        prefs = EnkiPrefs(this)
        db = EnkiDatabase.getInstance(this)
        registerObserver()
    }

    private fun registerObserver() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri != null && prefs.photoUploadEnabled) {
                    scope.launch {
                        handleNewMedia(uri)
                    }
                }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
    }

    private suspend fun handleNewMedia(uri: Uri) {
        // Query for the newest image to confirm it's a new entry
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
        )
        
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                // Check if we've already processed this file (simple path check for now)
                // In a production app, we'd track processed IDs in a separate DB table
                
                queuePhoto(contentUri, path)
            }
        }
    }

    private suspend fun queuePhoto(uri: Uri, originalPath: String) {
        try {
            // Copy file to internal app storage to ensure it's available for the worker
            // and won't be deleted/modified before upload.
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fileName = "upload_${System.currentTimeMillis()}.jpg"
            val internalFile = File(cacheDir, fileName)
            
            FileOutputStream(internalFile).use { output ->
                inputStream.copyTo(output)
            }

            db.queueDao().insert(
                QueuedSignal(
                    endpoint = "/signals/ingest",
                    contentType = "image/jpeg",
                    bodyFilePath = internalFile.absolutePath,
                    wifiOnly = prefs.wifiOnlyForLargeFiles
                )
            )

            Log.d("PhotoWatcher", "Queued photo: $fileName (WiFi-only: ${prefs.wifiOnlyForLargeFiles})")
            
            // Trigger the worker to check if we can upload now (if on WiFi)
            UploadWorker.triggerNow(this@PhotoWatcherService)

        } catch (e: Exception) {
            Log.e("PhotoWatcher", "Failed to queue photo", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: android.content.Context) {
            val intent = Intent(context, PhotoWatcherService::class.java)
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, PhotoWatcherService::class.java))
        }
    }
}
