package com.enki.connect.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.*
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Worker that collects hardware sensor data (Accelerometer, Pressure, Light, Steps).
 * Runs periodically based on EnkiPrefs.sensorIntervalSeconds.
 */
class SensorWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), SensorEventListener {

    private val prefs = EnkiPrefs(context)
    private val db = EnkiDatabase.getInstance(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val sensorData = JSONObject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.sensorEnabled || !prefs.isPaired) return@withContext Result.success()

        collectSensorData()
        
        // Wait a few seconds to gather readings
        delay(5000)
        
        stopCollecting()
        
        if (sensorData.length() > 0) {
            sensorData.put("timestamp", System.currentTimeMillis())
            
            db.queueDao().insert(
                QueuedSignal(
                    endpoint = "/signals/ingest",
                    contentType = "application/json",
                    bodyJson = JSONObject().apply {
                        put("type", "sensor_reading")
                        put("device_id", prefs.deviceId)
                        put("data", sensorData)
                    }.toString()
                )
            )
            UploadWorker.triggerNow(applicationContext)
        }

        Result.success()
    }

    private fun collectSensorData() {
        val sensorsToRead = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_MAGNETIC_FIELD
        )

        sensorsToRead.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private fun stopCollecting() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val accel = JSONObject().apply {
                        put("x", it.values[0])
                        put("y", it.values[1])
                        put("z", it.values[2])
                    }
                    sensorData.put("accelerometer", accel)
                }
                Sensor.TYPE_PRESSURE -> sensorData.put("pressure_hpa", it.values[0])
                Sensor.TYPE_LIGHT -> sensorData.put("light_lux", it.values[0])
                Sensor.TYPE_STEP_COUNTER -> sensorData.put("steps_cumulative", it.values[0])
                Sensor.TYPE_PROXIMITY -> sensorData.put("proximity_cm", it.values[0])
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val mag = JSONObject().apply {
                        put("x", it.values[0])
                        put("y", it.values[1])
                        put("z", it.values[2])
                    }
                    sensorData.put("magnetometer", mag)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val WORK_NAME = "enki_sensor_worker"

        fun enqueue(context: Context, intervalSeconds: Int) {
            val request = PeriodicWorkRequestBuilder<SensorWorker>(
                intervalSeconds.toLong().coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 1000), 
                TimeUnit.SECONDS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
