package com.enki.connect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enki.connect.data.prefs.EnkiPrefs
import com.enki.connect.ui.theme.EnkiBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: EnkiPrefs,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text("SYSTEM SETTINGS", 
                        fontSize = 18.sp, 
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EnkiBlue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // --- Identity Section ---
            SettingsSectionTitle("AGENT IDENTITY")
            InfoRow("SERVER NODE", prefs.serverUrl)
            InfoRow("DEVICE ID", prefs.deviceId)
            InfoRow("TENANT ID", prefs.tenantId)

            Spacer(modifier = Modifier.height(32.dp))

            // --- Performance Section ---
            SettingsSectionTitle("TELEMETRY CONTROL")
            
            var wifiOnly by remember { mutableStateOf(prefs.wifiOnlyForLargeFiles) }
            ToggleSetting(
                "WiFi Only for Media",
                "Defer large uploads to WiFi networks",
                wifiOnly
            ) {
                wifiOnly = it
                prefs.wifiOnlyForLargeFiles = it
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Placeholder for intervals
            Text("Reporting Intervals", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Fine-tune individual sensor frequency", color = Color.Gray, fontSize = 12.sp)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            IntervalItem("WiFi Scan", "60s")
            IntervalItem("Bluetooth Scan", "120s")
            IntervalItem("Sensor Stream", "300s")

            Spacer(modifier = Modifier.height(32.dp))

            // --- Storage Section ---
            SettingsSectionTitle("LOCAL BUFFER")
            Text("Offline Queue Limit: 500 items", fontSize = 14.sp)
            Text("Agent will prune older signals if limit is reached", color = Color.Gray, fontSize = 12.sp)

            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                "ENKI PULSE v0.1.0-STABLE",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 10.sp,
                color = Color.LightGray,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SettingsSectionTitle(text: String) {
    Text(
        text,
        color = EnkiBlue,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 16.dp)
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ToggleSetting(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = EnkiBlue)
        )
    }
}

@Composable
fun IntervalItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = EnkiBlue)
    }
}
