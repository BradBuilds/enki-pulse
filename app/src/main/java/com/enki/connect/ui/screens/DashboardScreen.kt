package com.enki.connect.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.enki.connect.data.api.EnkiApiClient
import com.enki.connect.data.prefs.EnkiPrefs
import com.enki.connect.service.LocationService
import com.enki.connect.ui.theme.EnkiBlue
import com.enki.connect.ui.theme.EnkiRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun DashboardScreen(
    prefs: EnkiPrefs,
    apiClient: EnkiApiClient,
    onNavigateToSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State
    var isConnected by remember { mutableStateOf(false) }
    var signalsSent by remember { mutableStateOf(prefs.signalsSent) }
    var lastSync by remember { mutableStateOf(prefs.lastSyncTime) }

    // Toggles
    var gpsEnabled by remember { mutableStateOf(prefs.gpsEnabled) }
    var gpsInterval by remember { mutableFloatStateOf(prefs.gpsIntervalSeconds.toFloat()) }
    var trafficLogEnabled by remember { mutableStateOf(prefs.trafficLogEnabled) }
    var wifiScanEnabled by remember { mutableStateOf(prefs.wifiScanEnabled) }
    var bluetoothScanEnabled by remember { mutableStateOf(prefs.bluetoothScanEnabled) }
    var sensorEnabled by remember { mutableStateOf(prefs.sensorEnabled) }

    // Permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            gpsEnabled = true
            prefs.gpsEnabled = true
            // Now safe to start service
            LocationService.start(context)
        }
    }

    // Health check loop
    LaunchedEffect(Unit) {
        while (true) {
            isConnected = apiClient.healthCheck()
            signalsSent = prefs.signalsSent
            lastSync = prefs.lastSyncTime
            delay(10_000)
        }
    }

    // GPS Sync logic
    LaunchedEffect(gpsEnabled) {
        if (gpsEnabled) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                LocationService.start(context)
            } else {
                // If bypassing pairing, we might not have permissions yet.
                // Request them here instead of crashing.
                locationPermissionLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, 
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        } else {
            LocationService.stop(context)
        }
    }

    LaunchedEffect(gpsInterval) {
        prefs.gpsIntervalSeconds = gpsInterval.toInt()
        if (gpsEnabled) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (hasPermission) {
                LocationService.stop(context)
                delay(500)
                LocationService.start(context)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    "ENKI PULSE",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = EnkiBlue
                )
                Text(
                    "Universal Signal Agent",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = EnkiBlue)
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // --- Status ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (isConnected) Color(0xFF22C55E) else EnkiRed, CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                if (isConnected) "PULSE ONLINE" else "PULSE OFFLINE",
                fontWeight = FontWeight.Bold,
                color = if (isConnected) Color(0xFF22C55E) else EnkiRed,
                letterSpacing = 1.sp
            )
        }
        Text(
            prefs.serverUrl.ifBlank { "https://pulse.dev" },
            modifier = Modifier.padding(top = 16.dp, start = 4.dp),
            color = Color.Gray,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- Stats ---
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Signals Sent", fontSize = 12.sp, color = Color.Gray)
                Text(signalsSent.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Last Sync", fontSize = 12.sp, color = Color.Gray)
                Text(formatTimeAgo(lastSync), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // --- SIGINT CORE ---
        SectionTitle("SIGINT CORE")
        
        FeatureItem(
            title = "GPS Intelligence",
            subtitle = "Precision coordinate stream",
            checked = gpsEnabled,
            onCheckedChange = { gpsEnabled = it; prefs.gpsEnabled = it }
        )
        
        if (gpsEnabled) {
            Text(
                "Resolution: ${gpsInterval.toInt()}s",
                modifier = Modifier.padding(top = 16.dp),
                color = EnkiBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Slider(
                value = gpsInterval,
                onValueChange = { gpsInterval = it },
                valueRange = 5f..3600f,
                colors = SliderDefaults.colors(
                    thumbColor = EnkiBlue,
                    activeTrackColor = EnkiBlue
                )
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        FeatureItem(
            title = "Traffic Analysis",
            subtitle = "DNS & Network Flow telemetry",
            checked = trafficLogEnabled,
            onCheckedChange = { trafficLogEnabled = it; prefs.trafficLogEnabled = it }
        )

        Spacer(modifier = Modifier.height(48.dp))

        // --- ENVIRONMENTAL ---
        SectionTitle("ENVIRONMENTAL")
        
        FeatureItem(
            title = "WiFi Environment",
            subtitle = "BSSID & Signal mapping",
            checked = wifiScanEnabled,
            onCheckedChange = { wifiScanEnabled = it; prefs.wifiScanEnabled = it }
        )
        Spacer(modifier = Modifier.height(24.dp))
        FeatureItem(
            title = "Bluetooth Scan",
            subtitle = "BLE device discovery",
            checked = bluetoothScanEnabled,
            onCheckedChange = { bluetoothScanEnabled = it; prefs.bluetoothScanEnabled = it }
        )
        Spacer(modifier = Modifier.height(24.dp))
        FeatureItem(
            title = "IMU Sensors",
            subtitle = "Motion, Pressure, Magnetic",
            checked = sensorEnabled,
            onCheckedChange = { sensorEnabled = it; prefs.sensorEnabled = it }
        )

        Spacer(modifier = Modifier.height(64.dp))

        // --- Terminate Button ---
        Button(
            onClick = {
                LocationService.stop(context)
                onDisconnect()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFEBEE),
                contentColor = EnkiRed
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Text("TERMINATE AGENT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = EnkiBlue,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 24.dp)
    )
}

@Composable
fun FeatureItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = Color.Gray, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = EnkiBlue
            )
        )
    }
}

private fun formatTimeAgo(epochMs: Long): String {
    if (epochMs == 0L) return "Never"
    val diff = System.currentTimeMillis() - epochMs
    val seconds = diff / 1000
    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}
