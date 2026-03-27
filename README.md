# Enki Pulse — Universal Device Agent

Enki Pulse is a high-performance, universal signal agent designed to turn any Android device into a comprehensive sensor node for your private Enki server. 

## Vision
Enki Pulse provides real-time telemetry and deep digital activity tracking, bridging the gap between physical location and digital behavior. It is designed to match the high-tech, data-heavy aesthetic of the Enki Command Center.

## Key Features

### 📡 SIGINT Core
- **GPS Intelligence:** Continuous, high-precision coordinate streaming with configurable reporting intervals (5s to 1h). Includes dead-zone suppression to save battery and data.
- **Traffic Analysis:** On-device VPN-based network logging. Captures DNS queries, TLS SNI hostnames, and connection flows with per-app attribution.

### 🌐 Intelligence Channels
- **App Usage Tracking:** Build a complete timeline of which apps are in focus and for how long.
- **Notification Capture:** Real-time capture of incoming alerts from all apps (with sensitive app filtering).
- **Web URL Tracking:** (Accessibility-based) Captures visited URLs from major browsers.
- **Media Watcher:** Automatically detects and uploads new photos/videos (WiFi-optimized).
- **Email Intelligence:** Seamless sync with Gmail and other IMAP accounts.

### 🌡️ Environmental Telemetry
- **WiFi/Bluetooth Scan:** Maps the local RF environment, logging nearby BSSIDs and BLE devices.
- **IMU Sensors:** Real-time stream of Accelerometer, Barometer, Magnetometer, and Step data.

## Deployment & Setup

### Requirements
- Android 8.0 (API 26) or higher.
- A functioning Enki Node (for live connection).

### Installation
1. Generate the APK: `./gradlew assembleDebug`
2. Sideload the APK to your Android device.
3. Open the app and scan the Pairing QR code from your Enki Server UI.
4. **Manual Fallback:** Tap "Manual Configuration" to enter your server URL and Pairing Token.

## Protocol & Security
- **Handshake:** Uses a secure token-based activation flow.
- **Storage:** All credentials and sensitive tokens are stored in Android's `EncryptedSharedPreferences`.
- **Offline Resilience:** Includes a local Room-based signal queue that automatically flushes when connectivity is restored.
- **Network:** Default trust for enkisystems.com (Let's Encrypt). Supports local cleartext for dev testing.

---
*Built for the Enki Ecosystem. Secure. Private. Persistent.*
