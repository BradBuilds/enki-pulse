package com.enki.connect.service

import android.content.Context
import android.provider.CalendarContract
import androidx.work.*
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Worker that syncs calendar events from the device.
 * Queries CalendarContract and batches events.
 */
class CalendarSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val prefs = EnkiPrefs(context)
    private val db = EnkiDatabase.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.calendarSyncEnabled || !prefs.isPaired) return@withContext Result.success()

        val events = JSONArray()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_ID
        )

        // Sync events from last 24 hours to 30 days in the future
        val now = System.currentTimeMillis()
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(
            (now - 24 * 60 * 60 * 1000).toString(),
            (now + 30L * 24 * 60 * 60 * 1000).toString()
        )

        try {
            applicationContext.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val locIdx = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)

                while (cursor.moveToNext()) {
                    events.put(JSONObject().apply {
                        put("event_id", cursor.getLong(idIdx))
                        put("title", cursor.getString(titleIdx))
                        put("description", cursor.getString(descIdx))
                        put("start_ts", cursor.getLong(startIdx))
                        put("end_ts", cursor.getLong(endIdx))
                        put("location", cursor.getString(locIdx))
                    })
                }
            }
        } catch (e: SecurityException) {
            return@withContext Result.failure()
        }

        if (events.length() > 0) {
            db.queueDao().insert(
                QueuedSignal(
                    endpoint = "/signals/ingest",
                    contentType = "application/json",
                    bodyJson = JSONObject().apply {
                        put("type", "calendar_sync")
                        put("device_id", prefs.deviceId)
                        put("events", events)
                    }.toString()
                )
            )
            UploadWorker.triggerNow(applicationContext)
        }

        Result.success()
    }

    companion object {
        private const val WORK_NAME = "enki_calendar_sync"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(12, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
