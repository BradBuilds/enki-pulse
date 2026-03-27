package com.enki.connect.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.enki.connect.data.db.EnkiDatabase
import com.enki.connect.data.db.QueuedSignal
import com.enki.connect.data.prefs.EnkiPrefs
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * VpnService implementation that intercepts local traffic to log DNS queries and network flows.
 */
class TrafficLoggerService : VpnService() {

    private lateinit var prefs: EnkiPrefs
    private lateinit var db: EnkiDatabase
    private lateinit var connectivityManager: ConnectivityManager
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val dnsQueries = mutableListOf<JSONObject>()
    private val flows = ConcurrentHashMap<String, JSONObject>()
    
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        prefs = EnkiPrefs(this)
        db = EnkiDatabase.getInstance(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        
        if (!isRunning) {
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("Enki Traffic Logger")
                .addAddress("10.0.0.1", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0) 
                .setBlocking(true)

            vpnInterface = builder.establish()
            isRunning = true
            
            // Required for Android 14+ 
            // VpnService usually implies its own foreground state, but we ensure it matches 
            // the system expectations for a persistent agent.
            
            // 1. Packet processing loop
            scope.launch {
                processPackets()
            }
            
            // 2. Batch upload loop (every 5 minutes)
            scope.launch {
                while (isRunning) {
                    delay(5 * 60 * 1000)
                    flushBatch()
                }
            }
            
            Log.d("TrafficLogger", "VPN Started")
        } catch (e: Exception) {
            Log.e("TrafficLogger", "Failed to start VPN", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        isRunning = false
        scope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopSelf()
    }

    private suspend fun processPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buffer = ByteBuffer.allocate(16384)

        while (isRunning) {
            try {
                val read = input.read(buffer.array())
                if (read > 0) {
                    val packet = buffer.array().copyOf(read)
                    analyzePacket(packet)
                    output.write(packet, 0, read)
                }
            } catch (e: Exception) {
                if (isRunning) delay(100)
            }
            buffer.clear()
        }
    }

    private fun analyzePacket(packet: ByteArray) {
        if (packet.size < 20) return
        
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return 

        val protocol = packet[9].toInt()
        val srcIp = InetAddress.getByAddress(packet.sliceArray(12..15)).hostAddress
        val dstIp = InetAddress.getByAddress(packet.sliceArray(16..19)).hostAddress
        val ihl = (packet[0].toInt() and 0x0F) * 4

        when (protocol) {
            17 -> handleUdp(packet, ihl, srcIp, dstIp) // UDP
            6 -> handleTcp(packet, ihl, srcIp, dstIp)  // TCP
        }
    }

    private fun handleUdp(packet: ByteArray, headerLength: Int, srcIp: String, dstIp: String) {
        if (packet.size < headerLength + 8) return
        val dstPort = ((packet[headerLength + 2].toInt() and 0xFF) shl 8) or (packet[headerLength + 3].toInt() and 0xFF)
        
        if (dstPort == 53) {
            parseDns(packet, headerLength + 8, srcIp)
        }
    }

    private fun handleTcp(packet: ByteArray, headerLength: Int, srcIp: String, dstIp: String) {
        if (packet.size < headerLength + 20) return
        val srcPort = ((packet[headerLength].toInt() and 0xFF) shl 8) or (packet[headerLength + 1].toInt() and 0xFF)
        val dstPort = ((packet[headerLength + 2].toInt() and 0xFF) shl 8) or (packet[headerLength + 3].toInt() and 0xFF)
        val dataOffset = ((packet[headerLength + 12].toInt() shr 4) and 0x0F) * 4
        val payloadOffset = headerLength + dataOffset
        
        val flowKey = "$srcIp:$srcPort->$dstIp:$dstPort"
        val appInfo = getAppForConnection(6, srcPort, dstIp, dstPort)

        val flow = flows.getOrPut(flowKey) {
            JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("app", appInfo.appName)
                put("pkg", appInfo.packageName)
                put("dest_ip", dstIp)
                put("dest_port", dstPort)
                put("proto", if (dstPort == 443) "TLS" else "TCP")
                put("bytes_sent", 0L)
                put("bytes_recv", 0L)
            }
        }
        
        flow.put("bytes_sent", flow.getLong("bytes_sent") + packet.size)
    }

    private fun parseDns(packet: ByteArray, payloadOffset: Int, srcIp: String) {
        try {
            val domain = StringBuilder()
            var pos = payloadOffset + 12 
            while (pos < packet.size) {
                val len = packet[pos].toInt()
                if (len == 0) break
                pos++
                for (i in 0 until len) {
                    domain.append(packet[pos + i].toInt().toChar())
                }
                domain.append(".")
                pos += len
            }
            val finalDomain = domain.toString().trimEnd('.')
            if (finalDomain.isNotEmpty()) {
                val query = JSONObject().apply {
                    put("ts", System.currentTimeMillis())
                    put("domain", finalDomain)
                    put("app", "System/Unknown") 
                }
                synchronized(dnsQueries) {
                    dnsQueries.add(query)
                }
            }
        } catch (e: Exception) {}
    }

    private data class AppInfo(val packageName: String, val appName: String)

    private fun getAppForConnection(proto: Int, localPort: Int, remoteIp: String, remotePort: Int): AppInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val uid = connectivityManager.getConnectionOwnerUid(
                    proto, 
                    java.net.InetSocketAddress("10.0.0.1", localPort),
                    java.net.InetSocketAddress(remoteIp, remotePort)
                )
                if (uid != -1) {
                    val pkg = packageManager.getNameForUid(uid) ?: "unknown"
                    val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    return AppInfo(pkg, label)
                }
            } catch (e: Exception) {}
        }
        return AppInfo("unknown", "Unknown App")
    }

    private suspend fun flushBatch() {
        val dnsCopy: List<JSONObject>
        synchronized(dnsQueries) {
            dnsCopy = dnsQueries.toList()
            dnsQueries.clear()
        }
        
        val flowsCopy = flows.values.toList()
        flows.clear()

        if (dnsCopy.isEmpty() && flowsCopy.isEmpty()) return

        val batch = JSONObject().apply {
            put("batch_type", "traffic_log")
            put("device_id", prefs.deviceId)
            put("timestamp", System.currentTimeMillis())
            put("dns_queries", JSONArray(dnsCopy))
            put("flows", JSONArray(flowsCopy))
        }

        db.queueDao().insert(
            QueuedSignal(
                endpoint = "/signals/ingest",
                contentType = "application/json",
                bodyJson = batch.toString()
            )
        )

        UploadWorker.triggerNow(this)
    }

    companion object {
        const val ACTION_STOP = "com.enki.connect.STOP_VPN"

        fun start(context: Context) {
            val intent = Intent(context, TrafficLoggerService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrafficLoggerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
