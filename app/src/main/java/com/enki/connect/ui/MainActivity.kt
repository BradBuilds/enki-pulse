package com.enki.connect.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.enki.connect.data.api.EnkiApiClient
import com.enki.connect.data.prefs.EnkiPrefs
import com.enki.connect.ui.screens.DashboardScreen
import com.enki.connect.ui.screens.QrPairingScreen
import com.enki.connect.ui.screens.SettingsScreen
import com.enki.connect.ui.theme.EnkiTheme

class MainActivity : ComponentActivity() {

    private lateinit var prefs: EnkiPrefs
    private lateinit var apiClient: EnkiApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = EnkiPrefs(this)
        apiClient = EnkiApiClient(prefs)

        setContent {
            EnkiTheme {
                var isPaired by remember { mutableStateOf(prefs.isPaired) }
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(
                        prefs = prefs,
                        onBack = { showSettings = false }
                    )
                } else if (isPaired) {
                    DashboardScreen(
                        prefs = prefs,
                        apiClient = apiClient,
                        onNavigateToSettings = { showSettings = true },
                        onDisconnect = {
                            prefs.clearPairing()
                            isPaired = false
                        }
                    )
                } else {
                    QrPairingScreen(
                        prefs = prefs,
                        apiClient = apiClient,
                        onPaired = { isPaired = true }
                    )
                }
            }
        }
    }
}
