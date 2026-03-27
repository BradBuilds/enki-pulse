package com.enki.connect.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.enki.connect.data.api.EnkiApiClient
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background worker that flushes the offline signal queue.
 * Runs periodically or when connectivity is restored.
 */
class UploadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val prefs = EnkiPrefs(context)
    private val apiClient = EnkiApiClient(prefs)
    private val db = EnkiDatabase.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.isPaired) return@withContext Result.success()

        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!isConnected) return@withContext Result.retry()

        // Prune old signals (older than 7 days)
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        db.queueDao().pruneOlderThan(sevenDaysAgo)

        // Get pending signals
        val limit = 50
        val signals = if (isWifi) {
            db.queueDao().getPending(limit)
        } else {
            db.queueDao().getPendingCellular(limit)
        }

        if (signals.isEmpty()) return@withContext Result.success()

        var successCount = 0
        for (signal in signals) {
            val success = uploadSignal(signal)
            if (success) {
                db.queueDao().delete(signal.id)
                successCount++
            } else {
                db.queueDao().incrementRetry(signal.id)
            }
        }

        if (successCount > 0) {
            prefs.incrementSignals(successCount)
        }

        // If we still have more signals, request another run soon
        if (signals.size == limit) {
            return@withContext Result.retry()
        }

        Result.success()
    }

    private suspend fun uploadSignal(signal: QueuedSignal): Boolean {
        return try {
            if (signal.bodyFilePath != null) {
                // Large payload (photo/video)
                val file = File(signal.bodyFilePath)
                if (!file.exists()) return true // File gone, nothing to upload
                val bytes = file.readBytes()
                apiClient.ingestBytes(bytes, signal.contentType)
            } else if (signal.bodyJson != null) {
                // JSON payload
                if (signal.endpoint == "/signals/geo/ping") {
                    val json = JSONObject(signal.bodyJson)
                    apiClient.sendGpsPing(
                        lat = json.getDouble("lat"),
                        lon = json.getDouble("lon"),
                        altitude = json.optDouble("altitude").takeIf { !it.isNaN() },
                        speed = json.optDouble("speed").takeIf { !it.isNaN() }?.toFloat(),
                        bearing = json.optDouble("bearing").takeIf { !it.isNaN() }?.toFloat(),
                        accuracy = json.optDouble("accuracy").takeIf { !it.isNaN() }?.toFloat(),
                        battery = json.optInt("battery").takeIf { it != 0 },
                        timestamp = json.getLong("timestamp")
                    )
                } else {
                    apiClient.ingestJson(JSONObject(signal.bodyJson))
                }
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val WORK_NAME = "enki_upload_worker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun triggerNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
