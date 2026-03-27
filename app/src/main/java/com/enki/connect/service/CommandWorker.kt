package com.enki.connect.service

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import androidx.work.*
import com.enki.connect.data.api.EnkiApiClient
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Worker that polls the Enki server for remote commands.
 * Executes: take_photo, record_audio, get_location, sound_alarm, etc.
 */
class CommandWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val prefs = EnkiPrefs(context)
    private val apiClient = EnkiApiClient(prefs)
    private val db = EnkiDatabase.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.isPaired) return@withContext Result.success()

        val config = apiClient.pollConfig() ?: return@withContext Result.retry()
        val commands = config.optJSONArray("commands") ?: return@withContext Result.success()

        for (i in 0 until commands.length()) {
            val command = commands.getJSONObject(i)
            executeCommand(command)
        }

        Result.success()
    }

    private suspend fun executeCommand(command: JSONObject) {
        val type = command.getString("type")
        val cmdId = command.getString("id")
        
        Log.d("CommandWorker", "Executing: $type ($cmdId)")

        when (type) {
            "get_location" -> {
                // LocationService is already running, this forces an immediate ping
                LocationService.start(applicationContext)
            }
            "sound_alarm" -> {
                soundAlarm()
            }
            "wifi_scan" -> {
                NetworkScannerWorker.enqueue(applicationContext, 15) // Immediate scan
            }
            "sensor_snapshot" -> {
                SensorWorker.enqueue(applicationContext, 15) // Immediate scan
            }
            // "take_photo" and "record_audio" require specific UI/Camera lifecycle
            // usually triggered via a separate high-priority Foreground Service
        }

        // Report completion
        reportCommandComplete(cmdId)
    }

    private fun soundAlarm() {
        try {
            val alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val mp = MediaPlayer.create(applicationContext, alert)
            mp.setVolume(1.0f, 1.0f)
            mp.start()
        } catch (e: Exception) {}
    }

    private suspend fun reportCommandComplete(cmdId: String) {
        val result = JSONObject().apply {
            put("type", "command_result")
            put("command_id", cmdId)
            put("status", "completed")
            put("timestamp", System.currentTimeMillis())
        }
        apiClient.ingestJson(result)
    }

    companion object {
        private const val WORK_NAME = "enki_command_poll"

        fun enqueue(context: Context, intervalSeconds: Int) {
            val request = PeriodicWorkRequestBuilder<CommandWorker>(
                intervalSeconds.toLong().coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 1000),
                TimeUnit.SECONDS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
