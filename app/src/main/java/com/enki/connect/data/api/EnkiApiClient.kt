package com.enki.connect.data.api

import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for all communication with the Enki server.
 * Adds X-Device-ID and X-Tenant-ID headers to every request.
 */
class EnkiApiClient(private val prefs: EnkiPrefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val OCTET_TYPE = "application/octet-stream".toMediaType()

    private fun baseUrl(): String = prefs.serverUrl.trimEnd('/')

    private fun Request.Builder.addEnkiHeaders(): Request.Builder = this
        .addHeader("X-Device-ID", prefs.deviceId)
        .addHeader("X-Tenant-ID", prefs.tenantId)

    // ─── Pairing ─────────────────────────────────────────────────────

    suspend fun activateDevice(token: String, platform: String = "android"): JSONObject? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("token", token)
                put("platform", platform)
            }
            val request = Request.Builder()
                .url("${baseUrl()}/devices/pair/activate")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { JSONObject(it) }
            } else null
        }

    // ─── GPS Ping ────────────────────────────────────────────────────

    suspend fun sendGpsPing(
        lat: Double, lon: Double, altitude: Double?,
        speed: Float?, bearing: Float?, accuracy: Float?,
        battery: Int?, timestamp: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            altitude?.let { put("altitude", it) }
            speed?.let { put("speed", it.toDouble()) }
            bearing?.let { put("bearing", it.toDouble()) }
            accuracy?.let { put("accuracy", it.toDouble()) }
            battery?.let { put("battery", it) }
            put("timestamp", timestamp)
        }

        val request = Request.Builder()
            .url("${baseUrl()}/signals/geo/ping")
            .addEnkiHeaders()
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        try {
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ─── Generic Signal Ingest (JSON) ────────────────────────────────

    suspend fun ingestJson(payload: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/signals/ingest")
            .addEnkiHeaders()
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        try {
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ─── Binary Ingest (photos, audio, video) ────────────────────────

    suspend fun ingestBytes(bytes: ByteArray, contentType: String = "application/octet-stream"): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/signals/ingest")
                .addEnkiHeaders()
                .addHeader("Content-Type", contentType)
                .post(bytes.toRequestBody(contentType.toMediaType()))
                .build()

            try {
                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }

    // ─── Config Polling ──────────────────────────────────────────────

    suspend fun pollConfig(): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/devices/${prefs.deviceId}/config")
            .addEnkiHeaders()
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { JSONObject(it) }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ─── Config Sync (push local config to server) ────────────────────

    suspend fun syncConfig(config: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/devices/${prefs.deviceId}")
            .addEnkiHeaders()
            .patch(config.toString().toRequestBody(JSON_TYPE))
            .build()

        try {
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ─── Health Check ────────────────────────────────────────────────

    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/health")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
