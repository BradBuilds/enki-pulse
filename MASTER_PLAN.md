# Enki Pulse — Universal Device Agent Master Plan

## Vision

Enki Pulse is not one app. It's three agents that turn any device into a sensor for your private Enki node:

1. **Enki Pulse Mobile** (Android/iOS) — GPS, photos, traffic logs, notifications, SMS, sensors
2. **Enki Pulse Desktop** (Linux/Mac/Windows daemon) — shell history, file activity, process timeline, network connections, clipboard, USB events, window focus tracking
3. **Enki Pulse Browser** (Chrome/Firefox extension) — every URL visited, time on page, full page archival, downloads, screenshots

All three pair via QR code scan → same protocol → same batch upload → same signal pipeline. A VM running the desktop agent is just another device on your Enki node.

```
PHONE (Enki Pulse Mobile)          YOUR ENKI NODE
+---------------------------+         +---------------------------+
| GPS pings (live, 5s-1h)  |         |                           |
| Network traffic log (VPN) |--batch->| POST /signals/ingest      |
| Web URLs (accessibility)  |         |                           |
| App usage timeline        |         | Every signal gets:        |
| Photos (WiFi-only full)   |         | - LDU-T (raw stored)      |
| Notifications (all apps)  |         | - Parsed + classified     |
| SMS / Call log            |         | - Card auto-generated     |
| WiFi/BT environment      |         | - Observables indexed     |
| Sensors (accel/baro/step) |         | - Detection rules run     |
| Remote commands           |         | - Map pins placed         |
+---------------------------+         | - AI can query it all     |
                                      |                           |
LAPTOP/VM (Enki Pulse Desktop)      |                           |
+---------------------------+         |                           |
| Shell/terminal history    |         |                           |
| File create/modify/delete |--batch->|                           |
| App focus + window title  |         |                           |
| Process executions        |         |                           |
| Network connections       |         | "What was I doing at 3pm  |
| DNS query log             |         |  yesterday?"              |
| Git commits (all repos)   |         |                           |
| Clipboard history         |         | "Show my browsing history |
| USB device events         |         |  overlaid on my GPS track"|
| System login/logout       |         |                           |
| Screenshots (periodic)    |         | "What file did I edit     |
+---------------------------+         |  after that meeting?"     |
                                      |                           |
BROWSER (Enki Pulse Extension)      |                           |
+---------------------------+         |                           |
| Every URL + title + time  |--batch->|                           |
| Time spent per page       |         |                           |
| Full page HTML archive    |         |                           |
| Page screenshots          |         |                           |
| Downloads                 |         |                           |
| Search queries            |         |                           |
| Form submissions (opt-in) |         |                           |
+---------------------------+         +---------------------------+
```

## Shared Protocol: Batch Upload

All three agents use the same data-efficient protocol:

### Tier 1: Live (send immediately, tiny payloads)
- GPS pings: ~200 bytes each, always live
- Battery/screen state: ~50 bytes

### Tier 2: Batched (collect locally, upload every 1-5 minutes)
- Traffic logs, sensor readings, WiFi scans, notifications, app usage, shell history, file events, web visits
- Collect in local buffer, compress, POST as single JSON array
- Delta encoding: only send what changed (new WiFi SSID, not same ones)
- Dead-zone suppression: skip GPS if phone hasn't moved 10m, skip sensors if stationary

### Tier 3: Deferred (queue for WiFi)
- Photos (full resolution), video, audio, page archives, screenshots
- Send 50KB thumbnail on cellular immediately, queue full file for WiFi
- Desktop agent: always sends (wired connection assumed)

### Tier 4: On-Demand (triggered from Enki UI)
- Remote camera, audio recording, full sensor snapshot, contact sync, SMS history
- Desktop: screenshot on demand, full process list, connection dump

### Data Budget

| Agent | Cellular/Day | WiFi/Day | Storage/Year |
|-------|-------------|----------|-------------|
| Mobile (30s GPS, all logs) | ~2 MB | ~150 MB (photos) | ~55 GB |
| Desktop (all features) | N/A | ~50-200 MB | ~50-70 GB |
| Browser (URLs + metadata) | N/A | ~5 MB | ~2 GB |
| Browser (full page archive) | N/A | ~200-500 MB | ~100-150 GB |

---

# PART 1: ENKI CONNECT MOBILE (Android)

## Pairing

1. Open app → camera activates → scan QR code from Enki UI
2. QR contains: `{server, token, device_id, tenant_id}`
3. App calls `POST /devices/pair/activate` with `{token, platform: "android"}`
4. Server returns config: `{gps_interval_s, upload_photos, features_enabled, endpoints}`
5. Credentials stored in `EncryptedSharedPreferences`
6. Manual entry fallback: paste server URL + token

## Screen 1: QR Pairing

Camera viewfinder with "Scan QR from your Enki server" instruction.
Manual entry button at bottom.

## Screen 2: Dashboard

- Connection status (green/red) + server URL + last sync + signal count
- GPS interval slider: 5s (live) → 30s → 1m → 5m → 15m → 30m → 1h
- Feature toggles (each starts/stops a service):
  - GPS Tracking [ON]
  - Network Traffic Log [ON] — the VPN-based DNS/connection logger
  - Auto-upload Photos [ON] — full res on WiFi, thumbnail on cellular
  - Notification Capture [ON] — all app notifications
  - App Usage Tracking [ON] — which apps, when, how long
  - WiFi Environment Scan [OFF]
  - Bluetooth Scan [OFF]
  - Sensor Data [OFF] — accelerometer, barometer, steps
  - SMS/Call Log Sync [OFF]
- Battery impact estimate based on active features
- Disconnect button

## Screen 3: Settings

- Server URL, device ID, tenant ID (read-only)
- Per-sensor intervals (WiFi scan: 60s, BT: 120s, sensors: 300s)
- Photo quality: original / compressed
- Notification filter: all apps / selected apps / exclude banking
- Upload mode: any network / WiFi only for large files
- Offline buffer size (default 500 items, max 5000)
- Command poll interval: 30s

## Background Services

### Service 1: LocationService (foreground service, always-on when GPS enabled)

```
FusedLocationProviderClient with configurable interval
On each location:
  POST /signals/geo/ping
  {lat, lon, altitude, speed, bearing, accuracy, battery, timestamp}

Dead-zone: skip if moved < 10m from last sent position
Signal type: signal.geospatial.position_report
```

### Service 2: TrafficLogger (VPN service — the killer feature)

Uses Android `VpnService` API (same technique as PCAPdroid) to intercept all network traffic on-device. No root needed. No remote VPN server.

```
Creates local TUN interface
Reads IP packets from file descriptor
Extracts:
  - DNS queries: domain, query type, response IP, timestamp
  - TLS SNI: hostname from ClientHello
  - Connection flows: src app, dest IP, dest port, bytes sent/recv, duration
  - App attribution: maps UID to package name

Batches every 5 minutes:
  POST /signals/ingest
  Content-Type: application/json
  {
    "batch_type": "traffic_log",
    "dns_queries": [
      {"ts": "...", "domain": "whatsapp.net", "response": "157.240.1.52", "app": "com.whatsapp"}
    ],
    "flows": [
      {"ts": "...", "app": "WhatsApp", "dest": "157.240.1.52:443", "proto": "TLS",
       "sni": "mmg-fna.whatsapp.net", "sent": 2348, "recv": 14720, "dur_ms": 1250}
    ]
  }

Signal types:
  signal.device.dns_query — each DNS resolution
  signal.device.network_flow — each connection with app attribution
```

This gives you a complete digital activity timeline: what apps were active, what servers they contacted, when, how much data. Combined with GPS, you know WHERE + WHAT at every moment.

### Service 3: WebURLTracker (AccessibilityService)

Reads the URL bar from Chrome/Firefox/Samsung Browser/etc. when the browser is in foreground.

```
On TYPE_WINDOW_STATE_CHANGED:
  If package is a browser:
    Read URL bar text via AccessibilityNodeInfo
    Record: {url, timestamp, browser_app}

On TYPE_WINDOW_STATE_CHANGED for any app:
  Record: {package_name, class_name, timestamp}
  → builds app usage timeline

Batch every 5 minutes with traffic log
Signal type: signal.device.web_visit (URL + title + duration)
Signal type: signal.device.app_usage (app + duration)
```

Browser-specific node IDs for URL bar:
- Chrome: `com.android.chrome:id/url_bar`
- Firefox: `org.mozilla.firefox:id/mozac_browser_toolbar_url_view`
- Samsung Internet: `com.sec.android.app.sbrowser:id/location_bar_edit_text`
- Edge: `com.microsoft.emmx:id/url_bar`

### Service 4: PhotoWatcher (ContentObserver)

```
Observes MediaStore.Images for new entries
On new photo:
  Read JPEG bytes from content URI
  If on WiFi: POST full resolution to /signals/ingest (Content-Type: image/jpeg)
  If on cellular:
    Generate 50KB thumbnail
    POST thumbnail immediately
    Queue full resolution for WiFi upload

Track uploaded content URIs to prevent duplicates
Signal type: signal.content.image (parser extracts EXIF GPS, camera model)
```

### Service 5: NotificationCapture (NotificationListenerService)

```
On onNotificationPosted():
  Extract from StatusBarNotification:
    - package name + app name
    - title (EXTRA_TITLE)
    - text (EXTRA_TEXT, EXTRA_BIG_TEXT)
    - category (message, email, social, etc.)
    - post time
    - action labels

  Apply filter (skip excluded apps like banking)
  Add to batch buffer

Batch every 5 minutes
Signal type: signal.communications.notification
```

Payload:
```json
{
  "app_package": "com.whatsapp",
  "app_name": "WhatsApp",
  "title": "John",
  "text": "Hey, are you coming tonight?",
  "category": "msg",
  "timestamp": "2026-03-25T10:15:05Z"
}
```

### Service 6: SensorCollector (WorkManager periodic)

```
Every N seconds (configurable, default 300s):
  Read hardware sensors:
    - Accelerometer (x, y, z) — motion detection
    - Gyroscope (x, y, z) — orientation
    - Barometer (hPa) — altitude/weather
    - Light (lux) — indoor/outdoor
    - Proximity (cm) — in pocket/face down
    - Step counter (cumulative) — activity
    - Magnetometer (x, y, z) — compass heading
    - Battery (level, charging, temperature)

  POST as batch JSON
  Signal type: signal.device.sensor_reading
```

### Service 7: NetworkScanner (WorkManager periodic)

```
WiFi scan (every 60s):
  WifiManager.startScan() → getScanResults()
  Each: {ssid, bssid, rssi, frequency, capabilities}
  Connected: {ssid, bssid, ip, gateway}

  Delta mode: only send if SSIDs changed from last scan

Bluetooth scan (every 120s):
  BluetoothLeScanner.startScan()
  Each: {name, address, rssi, bondState}

Cell info:
  TelephonyManager.getAllCellInfo()
  {type, carrier, mcc, mnc, cid, lac, rssi}

Signal type: signal.device.network_scan
```

### Service 8: TelephonySyncWorker (periodic, every 60s)

The radio/RF intelligence layer. Captures cell tower identifiers and signal strength — the "radio environment" that GPS can't see.

```
TelephonyManager.getAllCellInfo() → list of CellInfo objects

For each cell tower visible to the phone:
  LTE: CellIdentityLte → {mcc, mnc, tac, ci, pci, earfcn}
       CellSignalStrengthLte → {rsrp, rsrq, rssi, cqi, snr}
  5G NR: CellIdentityNr → {mcc, mnc, tac, nci, pci, nrarfcn}
         CellSignalStrengthNr → {ssRsrp, ssRsrq, ssSinr, csiRsrp}
  GSM: CellIdentityGsm → {mcc, mnc, lac, cid, arfcn}
       CellSignalStrengthGsm → {rssi, ber, ta}

Also capture:
  TelephonyManager.getNetworkOperatorName() → carrier name
  TelephonyManager.getDataNetworkType() → LTE/NR/GSM/CDMA
  SubscriptionManager → SIM state, slot, carrier per SIM
  ServiceState → roaming, emergency only, in service

Signal type: signal.device.cell_environment
```

Payload:
```json
{
    "type": "cell_environment",
    "timestamp": "2026-03-26T10:25:01Z",
    "carrier": "T-Mobile",
    "network_type": "LTE",
    "sim_state": "ready",
    "roaming": false,
    "serving_cell": {
        "type": "LTE",
        "mcc": 310, "mnc": 260,
        "tac": 8529, "ci": 41256, "pci": 134,
        "rsrp": -87, "rsrq": -9, "rssi": -62
    },
    "neighbor_cells": [
        {"type": "LTE", "pci": 135, "earfcn": 2100, "rsrp": -95},
        {"type": "LTE", "pci": 267, "earfcn": 66986, "rsrp": -102}
    ]
}
```

Why this matters:
- Track movement even when GPS is off (cell tower triangulation)
- Detect IMSI catchers / Stingrays (unexpected tower IDs, abnormal signal patterns)
- Map RF coverage (which carriers serve which areas)
- Correlate signal drops with physical locations (dead zones, interference)
- Identify indoor/underground positioning when GPS is unavailable

### Service 9: CommsSync (on-demand via remote command)


```
SMS: ContentResolver query content://sms
  Each: {address, body, date, type (inbox/sent/draft), read}
  Signal type: signal.communications.sms

Call log: ContentResolver query content://call_log/calls
  Each: {number, date, duration, type (in/out/missed), cached_name, geocoded_location}
  Signal type: signal.communications.call_log

Contacts: ContentResolver query ContactsContract
  Each: {display_name, phone_numbers[], emails[], addresses[], organization}
  Signal type: signal.personal.contacts

Calendar: ContentResolver query CalendarContract
  Each: {title, dtstart, dtend, location, description, attendees[]}
  Signal type: signal.personal.calendar_event
```

### Service 9: CommandExecutor (WorkManager periodic, every 30s)

```
Poll: GET /devices/{device_id}/config
Response includes commands[] array

Execute each command:
  take_photo     → CameraX capture → upload JPEG
  record_video   → CameraX N seconds → upload on WiFi
  record_audio   → MediaRecorder N seconds → upload on WiFi
  wifi_scan      → trigger immediate scan → upload
  bt_scan        → trigger immediate scan → upload
  sensor_snapshot→ read all sensors → upload
  get_location   → high-accuracy GPS fix → upload
  sound_alarm    → MediaPlayer max volume
  flash_light    → CameraManager torch mode
  display_message→ overlay notification
  get_contacts   → trigger CommsSync contacts
  get_sms        → trigger CommsSync SMS
  get_call_log   → trigger CommsSync call log

Report: POST /devices/{device_id}/command/{cmd_id}/complete
```

### Service 10: OfflineQueue (Room database)

```
All uploads go through the queue:
  1. Service adds item to Room DB
  2. Upload worker processes queue (FIFO)
  3. If server unreachable: retry with exponential backoff
  4. NetworkCallback triggers flush on connectivity restored
  5. WiFi-only items wait for WiFi

Schema:
  id, endpoint, content_type, body_json, body_file_path,
  created_at, retry_count, max_retries, wifi_only, status

Auto-prune: items older than 7 days
Max queue: configurable (default 500, max 5000)
```

### Service 11: EmailSync (WorkManager periodic, WiFi-only)

Uses the phone's existing Google account — no API keys or passwords needed.

```
1. AccountManager.getAccountsByType("com.google")
   → Returns accounts already on the phone: ["brad@gmail.com"]

2. AccountManager.getAuthToken(account, "oauth2:https://mail.google.com/", ...)
   → User sees one-time prompt: "Enki Pulse wants access to Gmail" → Allow
   → Returns OAuth2 token

3. Connect to imap.gmail.com:993 with XOAUTH2 SASL mechanism
   → JavaMail/Jakarta Mail: session.getStore("imaps")
   → Authenticate with: user=brad@gmail.com, auth=Bearer <token>

4. Fetch messages since last watermark (UID):
   → folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false))
   → Or: UIDFolder.getMessagesByUID(lastUID + 1, UIDFolder.LASTUID)

5. For each message, extract:
   - from, to, cc, bcc (InternetAddress[])
   - subject, date, message-id, in-reply-to
   - body text (text/plain part)
   - body HTML (text/html part)
   - attachments: filename, size, mime_type
   - labels/folders

6. POST to /signals/ingest as JSON (body + metadata)
   Attachments uploaded separately as raw bytes (WiFi only)

7. Update watermark (last UID processed)

Run: every 15 minutes, WiFi only
Signal type: signal.communications.email
```

Email payload:
```json
{
    "type": "email",
    "from": "john@acme.com",
    "to": ["brad@gmail.com"],
    "cc": [],
    "subject": "Re: Q3 Budget Review",
    "date": "2026-03-25T10:15:00Z",
    "body_preview": "Please review the attached spreadsheet before Friday...",
    "body_text": "Full email body...",
    "has_attachments": true,
    "attachments": [
        {"filename": "Q3_Budget.xlsx", "size": 245000, "mime_type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}
    ],
    "labels": ["INBOX", "IMPORTANT"],
    "message_id": "<abc123@mail.gmail.com>",
    "thread_id": "thread_789",
    "account": "brad@gmail.com"
}
```

For non-Google accounts (Outlook, Yahoo, iCloud):
- User provides IMAP host + app password once in Settings
- Standard IMAP login (no AccountManager needed)
- Same extraction pipeline

## Android Permissions

| Permission | Feature | When Requested |
|-----------|---------|---------------|
| `CAMERA` | QR scan + remote photo | First launch, photo toggle |
| `ACCESS_FINE_LOCATION` | GPS | GPS toggle |
| `ACCESS_BACKGROUND_LOCATION` | GPS while closed | After fine location |
| `READ_MEDIA_IMAGES` | Photo upload | Photo toggle |
| `RECORD_AUDIO` | Remote audio | Command execution |
| `READ_CONTACTS` | Contact sync | Command execution |
| `READ_SMS` | SMS sync | SMS toggle |
| `READ_CALL_LOG` | Call history | Command execution |
| `ACCESS_WIFI_STATE` | WiFi scan | WiFi toggle |
| `BLUETOOTH_SCAN` | BT scan | BT toggle |
| `BODY_SENSORS` | Health | Health toggle |
| `FOREGROUND_SERVICE` | Background GPS | Always |
| `POST_NOTIFICATIONS` | Service notification | First service start |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | All-app notifications | System settings redirect |
| `BIND_ACCESSIBILITY_SERVICE` | URL/app tracking | System settings redirect |
| `BIND_VPN_SERVICE` | Traffic logging | VPN consent dialog |
| `GET_ACCOUNTS` | List Google accounts | Email sync toggle |
| `USE_CREDENTIALS` | Get Gmail OAuth token | Email sync toggle |
| `READ_CALENDAR` | Calendar sync | Calendar toggle |

All requested incrementally — only when user enables the specific feature.

## Build Phases

### Phase A: Core MVP (1-2 days)
- [ ] Android project (Kotlin, Jetpack Compose, min SDK 26)
- [ ] QR scanner (CameraX + ML Kit Barcode)
- [ ] Credential storage (EncryptedSharedPreferences)
- [ ] Activation flow (POST /devices/pair/activate)
- [ ] Dashboard screen (status, slider, toggles)
- [ ] LocationService (foreground, configurable interval)
- [ ] OfflineQueue (Room DB + NetworkCallback)
- [ ] Batch upload worker

### Phase B: Traffic Logger (1-2 days)
- [ ] VpnService implementation (TUN interface)
- [ ] DNS query extraction
- [ ] TLS SNI extraction
- [ ] Connection flow tracking with app attribution (UID → package)
- [ ] 5-minute batch upload

### Phase C: Media + Notifications (1 day)
- [ ] PhotoWatcher (ContentObserver on MediaStore)
- [ ] Thumbnail generation for cellular upload
- [ ] WiFi-only queue for full resolution
- [ ] NotificationListenerService
- [ ] App filter (include/exclude lists)

### Phase D: Accessibility + App Tracking (1 day)
- [ ] AccessibilityService for URL capture
- [ ] Browser URL bar reading (Chrome, Firefox, Samsung, Edge)
- [ ] App usage timeline (foreground tracking)
- [ ] UsageStatsManager integration

### Phase E: Sensors + Network (1 day)
- [ ] SensorCollector (accel, baro, steps, light, magnetic)
- [ ] WiFi scanner with delta mode
- [ ] BT/BLE scanner
- [ ] Cell tower info
- [ ] Batch compression

### Phase F: Email + Calendar Sync (1 day)
- [ ] AccountManager Google account discovery
- [ ] OAuth token acquisition (XOAUTH2 for Gmail)
- [ ] IMAP sync with UID watermarking
- [ ] Email body + header extraction
- [ ] Attachment detection + WiFi-only upload
- [ ] Non-Google IMAP settings screen (host, port, app password)
- [ ] Calendar sync via CalendarContract content provider

### Phase G: Commands + Comms (1 day)
- [ ] Command polling + executor
- [ ] Remote camera (front/back)
- [ ] Remote audio/video recording
- [ ] Sound alarm + flashlight
- [ ] SMS/Call log/Contacts sync

### Phase H: Polish (1 day)
- [ ] Battery usage estimation per feature
- [ ] Settings screen (all intervals, filters)
- [ ] Data usage tracking
- [ ] Queue management UI
- [ ] App icon + branding
- [ ] APK signing for sideload

---

# PART 2: ENKI CONNECT DESKTOP (Linux/Mac/Windows)

A lightweight daemon/service that logs system activity and sends it to Enki. Written in Python (cross-platform) or Rust (performance).

## Pairing

Same as mobile: scan QR or paste token. On headless VMs: `enki-connect --pair --token=ABC123 --server=https://my-enki.com`

## Data Sources

### Shell History Watcher
```
Watch: ~/.bash_history, ~/.zsh_history, ~/.local/share/fish/fish_history
On change: parse new lines, extract timestamp + command + working directory
Enhanced: PROMPT_COMMAND hook logs exit code, duration, PWD

Signal type: signal.device.shell_command
Payload: {command, cwd, exit_code, duration_ms, shell, timestamp}
```

### File System Monitor
```
Linux: inotify (or fanotify for mount-wide)
macOS: FSEvents
Windows: ReadDirectoryChangesW / USN Journal

Watch configurable directories (default: ~/Documents, ~/Downloads, ~/Desktop)
Events: CREATE, MODIFY, DELETE, RENAME
Each: {path, event, size, timestamp}

Signal type: signal.device.file_event
Payload: {path, filename, event, size_bytes, directory, timestamp}
```

### Window Focus Tracker
```
Linux/X11: xprop -root -spy _NET_ACTIVE_WINDOW → xdotool getactivewindow getwindowname
Linux/Wayland: swaymsg -t subscribe -m '["window"]' (Sway) or Hyprland IPC
macOS: NSWorkspace.didActivateApplicationNotification
Windows: SetWinEventHook(EVENT_SYSTEM_FOREGROUND)

Each: {app_name, window_title, timestamp, duration_ms}
Signal type: signal.device.app_focus
Payload: {app_name, window_title, bundle_id, pid, start_time, duration_s}
```

### Process Logger
```
Linux: auditd with execve rule, or cn_proc netlink connector
macOS: Endpoint Security framework (es_new_client)
Windows: Sysmon Event ID 1, or ETW Microsoft-Windows-Kernel-Process

Each: {executable, cmdline, pid, ppid, uid, cwd, timestamp}
Signal type: signal.device.process_exec
```

### Network Connection Logger
```
Linux: ss -tunap polling every 30s, or eBPF tcp_connect tracepoint
macOS: lsof -i polling, or nettop
Windows: Sysmon Event ID 3, or ETW TCPIP provider

Each: {process, dest_ip, dest_port, protocol, state, bytes}
Signal type: signal.device.network_connection
```

### DNS Query Logger
```
Linux: journalctl -u systemd-resolved -f, or eBPF DNS tracing
macOS: log show --predicate 'subsystem == "com.apple.dnssd"'
Windows: ETW Microsoft-Windows-DNS-Client

Each: {domain, query_type, response_ip, process, timestamp}
Signal type: signal.device.dns_query
```

### Git Activity Watcher
```
Global git hook: git config --global core.hooksPath ~/.enki/git-hooks/
post-commit hook: log SHA, author, message, repo path, files changed, timestamp

Also: inotify on .git/refs/ and .git/HEAD across known repos

Signal type: signal.device.git_commit
Payload: {repo, branch, sha, author, message, files_changed, insertions, deletions}
```

### Clipboard Logger
```
Linux/X11: XFixes extension SelectionNotify events
Linux/Wayland: wl-paste --watch
macOS: NSPasteboard.general changeCount polling
Windows: AddClipboardFormatListener (WM_CLIPBOARDUPDATE)

Each: {content_preview (first 500 chars), content_type (text/image/file), app_source, timestamp}
Signal type: signal.device.clipboard
Payload: {preview, content_type, source_app, char_count, timestamp}
```

### USB Device Events
```
Linux: pyudev monitor with filter_by('usb')
macOS: IOKit IOServiceAddMatchingNotification
Windows: WMI Win32_USBHub InstanceCreation/Deletion events

Each: {vendor, product, serial, action (connected/disconnected), timestamp}
Signal type: signal.device.usb_event
```

### System Events
```
Linux: journald — login, logout, sudo, cron, service start/stop, reboot
macOS: Unified Log — user login, screen lock/unlock, sleep/wake
Windows: Security Event Log 4624/4625/4634/4800/4801

Signal type: signal.device.system_event
```

### Periodic Screenshot
```
Every N minutes (configurable, default 5 min), or on window focus change:
Linux: grim (Wayland) or scrot (X11)
macOS: screencapture -x
Windows: BitBlt screen capture

Compress to JPEG quality 60 (~100-300KB)
Upload on WiFi only
Signal type: signal.content.screenshot
```

## Desktop Build Phases

### Phase DA: Core (1 day)
- [ ] Python daemon with config file
- [ ] QR/token pairing via CLI or system tray
- [ ] Batch upload worker with offline queue (SQLite)
- [ ] Shell history watcher
- [ ] File system monitor (watchdog library)

### Phase DB: Activity Tracking (1 day)
- [ ] Window focus tracker (cross-platform)
- [ ] Process logger
- [ ] Network connection polling
- [ ] DNS query capture

### Phase DC: Extra Sources (1 day)
- [ ] Git hook installer + watcher
- [ ] Clipboard monitor
- [ ] USB event listener
- [ ] System event logger
- [ ] Periodic screenshots

---

# PART 3: ENKI CONNECT BROWSER (Chrome/Firefox Extension)

Manifest V3 extension that logs all browsing activity and optionally archives full pages.

## What It Captures

### Always Active (lightweight, ~5MB/day)
- **Every URL** visited with title, timestamp, referrer
- **Time on page** (focus time, not just navigation — tracks tab active state)
- **Search queries** (parsed from Google, Bing, DuckDuckGo URL params)
- **Downloads** (filename, URL, size, timestamp)
- **Tab lifecycle** (created, activated, closed, with timestamps)

### Optional: Full Page Archive (~200-500MB/day)
- **Complete HTML** via SingleFile-style inlining (CSS, images as data URIs)
- **Page screenshot** via `tabs.captureVisibleTab()`
- **Page text extract** via content script `document.body.innerText`

## Extension Architecture

```
Background Service Worker (Manifest V3):
  - chrome.webNavigation.onCompleted → log URL + timestamp
  - chrome.tabs.onActivated → track active tab (time-on-page start)
  - chrome.tabs.onUpdated → URL/title changes
  - chrome.downloads.onCreated → log downloads
  - Every 5 minutes: batch POST to Enki server

Content Script (injected into all pages):
  - Extract page text (document.body.innerText)
  - Extract meta tags (description, og:title, og:image)
  - Optionally: full page HTML capture (document.documentElement.outerHTML)
  - Send to background worker via chrome.runtime.sendMessage

Popup (extension icon click):
  - Connection status
  - Today's stats (pages visited, time browsing)
  - Toggle: full page archival on/off
  - Link to Enki server
```

## Signal Types

```json
{
  "type": "web_visit",
  "url": "https://www.nytimes.com/2026/03/25/technology/ai-regulation.html",
  "title": "New AI Regulation Framework Proposed",
  "domain": "nytimes.com",
  "referrer": "https://news.google.com",
  "visit_start": "2026-03-25T10:17:30Z",
  "visit_end": "2026-03-25T10:22:15Z",
  "duration_seconds": 285,
  "search_query": null,
  "content_preview": "The Biden administration today unveiled a new framework...",
  "device_id": "browser_chrome_laptop"
}
```

Signal type: `signal.device.web_visit`
Observables extracted: URLs, domains, email addresses found on page

## Browser Build Phases

### Phase BA: Core (1 day)
- [ ] Manifest V3 setup (Chrome + Firefox)
- [ ] webNavigation listener → URL/title/timestamp
- [ ] Tab focus tracking → time-on-page
- [ ] Download tracking
- [ ] Search query extraction (Google/Bing/DDG)
- [ ] 5-minute batch POST to Enki
- [ ] Popup with connection status

### Phase BB: Page Content (1 day)
- [ ] Content script for text extraction
- [ ] Meta tag extraction
- [ ] Optional full HTML capture (SingleFile-style)
- [ ] Screenshot capture (captureVisibleTab)
- [ ] WiFi-only upload for heavy content

---

# PART 4: SERVER-SIDE ADDITIONS

## New Signal Types for GDU Vocabulary

```
# Mobile traffic logging
signal.device.dns_query            — DNS resolution (domain + response + app)
signal.device.network_flow         — connection metadata (app + dest + bytes + duration)
signal.device.web_visit            — browser URL + title + duration
signal.device.app_usage            — app foreground time
signal.device.sensor_reading       — accelerometer, barometer, steps, light
signal.device.network_scan         — WiFi SSIDs, BT devices, cell towers

# Mobile communications
signal.communications.notification — app notification (WhatsApp, email, bank, etc.)
signal.communications.sms          — text message
signal.communications.call_log     — phone call record
signal.personal.contacts           — contact list sync
signal.personal.calendar_event     — calendar entry

# Desktop activity
signal.device.shell_command        — terminal command + exit code + duration
signal.device.file_event           — file create/modify/delete
signal.device.app_focus            — window title + app + duration
signal.device.process_exec         — process execution with cmdline
signal.device.network_connection   — outbound connection with process name
signal.device.git_commit           — commit with SHA, message, files
signal.device.clipboard            — clipboard content change
signal.device.usb_event            — USB device connect/disconnect
signal.device.system_event         — login, logout, lock, sleep, reboot

# Media (from any agent)
signal.content.image               — photo with EXIF (existing)
signal.content.audio               — audio recording
signal.content.video               — video recording
signal.content.screenshot          — periodic or on-demand screen capture
```

## New Card Template Presets

| Signal Type | Card Display |
|------------|-------------|
| dns_query | Table of domains + response IPs + apps |
| network_flow | Table with app name, destination, bytes, duration |
| web_visit | Title + URL link + domain + time spent |
| app_usage | Bar chart of app durations |
| notification | Email-style: app icon + title + text |
| sms | Email-style: phone number + message body |
| shell_command | Monospace command + exit code badge |
| file_event | File path + event type badge (created/modified/deleted) |
| app_focus | App name + window title + duration |
| git_commit | Repo + branch + message + files changed |
| network_scan | Table of SSIDs with signal strength bars |
| sensor_reading | Gauges for barometer + steps; graph for accelerometer |

## Command Queue System

New endpoints for remote device control:

```
POST /devices/{device_id}/commands
  Body: {type: "take_photo", params: {camera: "back"}}
  → Queues command, returns command_id

GET /devices/{device_id}/config
  → Returns: {config: {...}, commands: [{id, type, params, created_at}]}

POST /devices/{device_id}/commands/{cmd_id}/complete
  Body: {status: "success", signal_id: "sig_..."}
  → Marks command as executed

GET /devices/{device_id}/commands
  → List all commands with status (pending, completed, failed)
```

UI: "Send Command" dropdown on each device card in /devices page.

---

# Tech Stack Summary

| Agent | Language | Key Dependencies |
|-------|---------|-----------------|
| Mobile | Kotlin | CameraX, ML Kit, FusedLocation, Room, OkHttp, Jetpack Compose, Health Connect |
| Desktop | Python | watchdog, psutil, pyudev, python-xlib/pynput, requests |
| Browser | TypeScript | Chrome Extension APIs (Manifest V3), SingleFile core |
| All | — | Same batch upload protocol, same QR pairing, same Enki API |

## Distribution

| Agent | How Users Get It |
|-------|-----------------|
| Mobile (Android) | APK sideload or F-Droid |
| Mobile (iOS) | TestFlight (future) |
| Desktop (Linux) | pip install, or systemd service, or Docker |
| Desktop (macOS) | pip install, or brew, or launchd plist |
| Desktop (Windows) | pip install, or MSI installer, or Windows Service |
| Browser | Chrome Web Store or Firefox Add-ons (or manual .crx sideload) |
