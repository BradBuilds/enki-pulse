package com.enki.connect.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.work.*
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Worker that performs periodic WiFi and Bluetooth environment scans.
 * Results are batched and queued as signal.device.network_scan.
 */
class NetworkScannerWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val prefs = EnkiPrefs(context)
    private val db = EnkiDatabase.getInstance(context)
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.isPaired) return@withContext Result.success()

        val results = JSONObject().apply {
            put("type", "network_scan")
            put("device_id", prefs.deviceId)
            put("timestamp", System.currentTimeMillis())
        }

        // 1. WiFi Scan
        if (prefs.wifiScanEnabled) {
            val wifiArray = JSONArray()
            val scanResults = wifiManager.scanResults
            scanResults.forEach { scan ->
                wifiArray.put(JSONObject().apply {
                    put("ssid", scan.SSID)
                    put("bssid", scan.BSSID)
                    put("rssi", scan.level)
                    put("freq", scan.frequency)
                    put("capabilities", scan.capabilities)
                })
            }
            results.put("wifi_networks", wifiArray)
        }

        // 2. Bluetooth LE Scan
        if (prefs.bluetoothScanEnabled) {
            val btArray = JSONArray()
            val scanner = bluetoothManager.adapter?.bluetoothLeScanner
            
            if (scanner != null) {
                val scanJob = Job()
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val device = result.device
                        val json = JSONObject().apply {
                            put("name", result.scanRecord?.deviceName ?: "Unknown")
                            put("address", device.address)
                            put("rssi", result.rssi)
                            put("bond_state", device.bondState)
                        }
                        btArray.put(json)
                    }
                }

                scanner.startScan(callback)
                delay(10000) // Scan for 10 seconds
                scanner.stopScan(callback)
            }
            results.put("bluetooth_devices", btArray)
        }

        if (results.has("wifi_networks") || results.has("bluetooth_devices")) {
            db.queueDao().insert(
                QueuedSignal(
                    endpoint = "/signals/ingest",
                    contentType = "application/json",
                    bodyJson = results.toString()
                )
            )
            UploadWorker.triggerNow(applicationContext)
        }

        Result.success()
    }

    companion object {
        private const val WORK_NAME = "enki_network_scanner"

        fun enqueue(context: Context, intervalSeconds: Int) {
            val request = PeriodicWorkRequestBuilder<NetworkScannerWorker>(
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
