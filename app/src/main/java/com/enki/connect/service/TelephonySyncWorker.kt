package com.enki.connect.service

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.*
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
 * Worker that collects Telephony and Cell Tower data.
 * Essential for SIGINT (Signals Intelligence) and location mapping.
 */
class TelephonySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val prefs = EnkiPrefs(context)
    private val db = EnkiDatabase.getInstance(context)
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!prefs.isPaired) return@withContext Result.success()

        val results = JSONObject().apply {
            put("type", "telephony_scan")
            put("device_id", prefs.deviceId)
            put("timestamp", System.currentTimeMillis())
            
            // Device Identity (if permitted)
            put("carrier", telephonyManager.networkOperatorName)
            put("network_type", getNetworkTypeName(telephonyManager.networkType))
            put("phone_type", getPhoneTypeName(telephonyManager.phoneType))
            put("sim_state", getSimStateName(telephonyManager.simState))

            // Cell Tower Info (The "SIGINT" core)
            val cellArray = JSONArray()
            val allCellInfo = telephonyManager.allCellInfo
            allCellInfo?.forEach { info ->
                val cell = JSONObject()
                cell.put("is_registered", info.isRegistered)
                
                when (info) {
                    is CellInfoLte -> {
                        cell.put("type", "LTE")
                        cell.put("ci", info.cellIdentity.ci)
                        cell.put("pci", info.cellIdentity.pci)
                        cell.put("tac", info.cellIdentity.tac)
                        cell.put("dbm", info.cellSignalStrength.dbm)
                    }
                    is CellInfoGsm -> {
                        cell.put("type", "GSM")
                        cell.put("cid", info.cellIdentity.cid)
                        cell.put("lac", info.cellIdentity.lac)
                        cell.put("dbm", info.cellSignalStrength.dbm)
                    }
                    is CellInfoWcdma -> {
                        cell.put("type", "WCDMA")
                        cell.put("ci", info.cellIdentity.cid)
                        cell.put("lac", info.cellIdentity.lac)
                        cell.put("dbm", info.cellSignalStrength.dbm)
                    }
                }
                cellArray.put(cell)
            }
            put("cell_towers", cellArray)
        }

        db.queueDao().insert(
            QueuedSignal(
                endpoint = "/signals/ingest",
                contentType = "application/json",
                bodyJson = results.toString()
            )
        )
        
        UploadWorker.triggerNow(applicationContext)
        Result.success()
    }

    private fun getNetworkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        else -> "Unknown ($type)"
    }

    private fun getPhoneTypeName(type: Int): String = when (type) {
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        else -> "Unknown"
    }

    private fun getSimStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_ABSENT -> "Absent"
        else -> "Other ($state)"
    }

    companion object {
        private const val WORK_NAME = "enki_telephony_sync"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<TelephonySyncWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
