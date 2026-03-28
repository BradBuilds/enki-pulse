package com.enki.connect.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.enki.connect.data.api.EnkiApiClient
import com.enki.connect.data.prefs.EnkiPrefs
import com.enki.connect.ui.theme.EnkiBlue
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun QrPairingScreen(
    prefs: EnkiPrefs,
    apiClient: EnkiApiClient,
    onPaired: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var status by remember { mutableStateOf("SCAN ENKI NODE QR CODE") }
    var isActivating by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var manualServer by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun handleQrPayload(json: String) {
        if (isActivating) return
        isActivating = true
        status = "ESTABLISHING HANDSHAKE..."

        scope.launch {
            try {
                val qr = JSONObject(json)
                val server = qr.getString("server")
                val token = qr.getString("token")
                val deviceId = qr.getString("device_id")
                val tenantId = qr.getString("tenant_id")

                prefs.savePairing(server, token, deviceId, tenantId)

                val result = apiClient.activateDevice(token, "android")
                if (result != null && result.optString("status") == "activated") {
                    status = "NODE CONNECTED"
                    onPaired()
                } else {
                    status = "PAIRING FAILED"
                    prefs.clearPairing()
                    isActivating = false
                }
            } catch (e: Exception) {
                status = "PROTOCOL ERROR"
                prefs.clearPairing()
                isActivating = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            "ENKI",
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "PULSE AGENT PAIRING",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(64.dp))

        if (!showManualEntry) {
            // QR Scanner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                if (hasCameraPermission) {
                    QrCameraPreview(
                        onQrDetected = { handleQrPayload(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Scanner overlay lines (Enki style)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(40.dp)
                    ) {
                        // Corner decorations
                        val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        val length = 24.dp
                        val thickness = 2.dp
                        
                        // Top Left
                        Box(Modifier.size(length, thickness).background(borderColor).align(Alignment.TopStart))
                        Box(Modifier.size(thickness, length).background(borderColor).align(Alignment.TopStart))
                        
                        // Top Right
                        Box(Modifier.size(length, thickness).background(borderColor).align(Alignment.TopEnd))
                        Box(Modifier.size(thickness, length).background(borderColor).align(Alignment.TopEnd))
                        
                        // Bottom Left
                        Box(Modifier.size(length, thickness).background(borderColor).align(Alignment.BottomStart))
                        Box(Modifier.size(thickness, length).background(borderColor).align(Alignment.BottomStart))
                        
                        // Bottom Right
                        Box(Modifier.size(length, thickness).background(borderColor).align(Alignment.BottomEnd))
                        Box(Modifier.size(thickness, length).background(borderColor).align(Alignment.BottomEnd))
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("PERMISSION REQUIRED", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                status,
                textAlign = TextAlign.Center,
                color = if (isActivating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            TextButton(onClick = { showManualEntry = true }) {
                Text("MANUAL CONFIGURATION", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // Manual entry
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "NODE PARAMETERS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                EnkiTextField(
                    value = manualServer,
                    onValueChange = { manualServer = it },
                    label = "SERVER URL"
                )

                Spacer(modifier = Modifier.height(12.dp))

                EnkiTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    label = "PAIRING TOKEN"
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val json = JSONObject().apply {
                            put("server", manualServer.trim())
                            put("token", manualToken.trim())
                            put("device_id", "manual_agent")
                            put("tenant_id", "default")
                        }.toString()
                        handleQrPayload(json)
                    },
                    enabled = manualServer.isNotBlank() && manualToken.isNotBlank() && !isActivating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (isActivating) "CONNECTING..." else "ESTABLISH CONNECTION", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = { showManualEntry = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("BACK TO SCANNER", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EnkiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = EnkiBlue,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedLabelColor = EnkiBlue
        )
    )
}

@Composable
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun QrCameraPreview(
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var detected by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { analysis ->
                        analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                            if (detected) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage, imageProxy.imageInfo.rotationDegrees
                                )
                                val scanner = BarcodeScanning.getClient()
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        for (barcode in barcodes) {
                                            barcode.rawValue?.let { value ->
                                                if (value.contains("server") && value.contains("token")) {
                                                    detected = true
                                                    onQrDetected(value)
                                                }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer
                    )
                } catch (e: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
}
