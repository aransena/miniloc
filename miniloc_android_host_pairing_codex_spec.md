# miniloc Android Foreground Location Service — Codex Spec

## 1. Purpose

Create a minimal native Android app called **miniloc**.

`miniloc` turns an Android phone into a local-network location sensor. A separate **host device** on the same LAN discovers the phone, authenticates using a QR-based soft pairing token, and polls the phone for the latest cached location fix.

The app must not be tied to any specific product, platform, research system, or brand. It should be reusable anywhere a host computer needs accurate phone-derived location data over a local network.

---

## 2. Core Concept

```text
Android phone running miniloc
  - foreground location service
  - fused location provider
  - local HTTP server on fixed port
  - cached latest location fix
  - QR-scanned host pairing token

Host device
  - displays QR pairing payload
  - sweeps local subnet
  - calls /id_ping to identify miniloc phones
  - calls authenticated /loc_ping to retrieve location
```

The phone should not continuously push location by default. Instead, it maintains a hot cached location fix, and the host polls it.

---

## 3. Non-Goals

Do not implement:

- Product-specific naming or branding.
- Cloud services.
- User accounts.
- Maps.
- Analytics.
- mDNS / NSD discovery.
- Complex UI.
- Background-only location tracking without a foreground service.
- Silent remote start of tracking without local phone confirmation.

---

## 4. Required Android Behaviour

`miniloc` must use a native Android foreground service for location collection.

The service must:

- Request fused location updates using `FusedLocationProviderClient`.
- Run as a foreground service with a persistent notification.
- Use `foregroundServiceType="location"` in the manifest.
- Continue collecting location when the phone screen is locked.
- Keep the latest valid location fix in memory.
- Optionally maintain a small rolling buffer for recent samples.
- Expose the latest cached fix through a local HTTP API.

Do not request location only when `/loc_ping` is called. `/loc_ping` must return the most recent cached fix from the active foreground service.

---

## 5. Minimum Ubuntu Development Setup

Target a no-Android-Studio workflow suitable for Codex and command-line development.

Install dependencies:

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk unzip wget git gradle
```

Install Android command-line tools:

```bash
mkdir -p ~/Android/Sdk/cmdline-tools
cd /tmp
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*_latest.zip
mkdir -p ~/Android/Sdk/cmdline-tools/latest
mv cmdline-tools/* ~/Android/Sdk/cmdline-tools/latest/
```

Add SDK paths:

```bash
cat >> ~/.bashrc <<'EOF_BASHRC'
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
EOF_BASHRC

source ~/.bashrc
```

Install SDK packages:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Build should work with:

```bash
./gradlew assembleDebug
```

Install should work with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 6. Project Structure

```text
miniloc-android/
  settings.gradle.kts
  build.gradle.kts
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/miniloc/app/
        MainActivity.kt
        LocationForegroundService.kt
        MinilocHttpServer.kt
        PairingStore.kt
        LocationCache.kt
        Models.kt
      res/values/
        strings.xml
        styles.xml
```

Use native Kotlin Android.

Avoid Compose for MVP. Use a simple `Activity` with basic Android views.

---

## 7. Suggested Dependencies

Use the smallest practical dependency set.

Required:

```kotlin
implementation("androidx.core:core-ktx:1.13.1")
implementation("com.google.android.gms:play-services-location:21.3.0")
```

For local HTTP server, use either:

```kotlin
implementation("org.nanohttpd:nanohttpd:2.3.1")
```

or a similarly small embedded HTTP server.

For QR scanning, use ML Kit barcode scanning or another minimal camera/QR library:

```kotlin
implementation("com.google.mlkit:barcode-scanning:17.3.0")
```

---

## 8. Android Permissions

`AndroidManifest.xml` must include:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
```

The service declaration must include:

```xml
<service
    android:name=".LocationForegroundService"
    android:exported="false"
    android:foregroundServiceType="location" />
```

Runtime permissions:

- Request precise location.
- Request notification permission on Android 13+.
- Request camera permission before QR scan.

Design for possible future Android local-network permission requirements, but do not block MVP on this unless required by the target SDK/device.

---

## 9. Pairing Model

Use a generic **host-device pairing model**.

Definitions:

```text
Host device:
  The local computer, recorder, controller, or edge device that wants location data.

miniloc device:
  The Android phone running the miniloc app and foreground location service.
```

The host displays a QR code containing a short-lived pairing payload.

Example QR payload:

```json
{
  "type": "miniloc_pair",
  "version": 1,
  "host_id": "host-7K3P",
  "host_name": "Field Recorder 7K3P",
  "pairing_token": "base64url-256-bit-random",
  "issued_at": "2026-05-15T14:20:00Z",
  "expires_in_s": 600,
  "miniloc_port": 47654
}
```

The user opens `miniloc`, scans the QR code, and confirms:

```text
Share this phone's location with:
Field Recorder 7K3P

[Start sharing]
```

After confirmation, `miniloc` stores:

```text
host_id
host_name
pairing_token
pairing expiry / issued time
miniloc_port
```

Then it starts:

```text
foreground location service
local HTTP server bound to 0.0.0.0:47654
```

The active pairing may persist across app restarts, but the user must be able to forget it from the phone UI.

---

## 10. Local Network Server

`miniloc` must run a local HTTP server bound to:

```text
0.0.0.0:47654
```

Use constant:

```kotlin
const val MINILOC_PORT = 47654
```

All responses must be JSON.

Endpoints:

```text
GET  /id_ping
GET  /status
GET  /loc_ping
POST /forget_pairing
```

Optional future endpoints:

```text
POST /start
POST /stop
```

For MVP, `/start` and `/stop` should remain local-phone UI actions only. The host should not be able to silently start location sharing.

---

## 11. Authentication Rules

`/id_ping` is public and unauthenticated.

It must reveal no location and no sensitive user data.

`/status`, `/loc_ping`, and `/forget_pairing` require authentication.

Authentication for MVP:

```http
X-Miniloc-Host-Id: host-7K3P
X-Miniloc-Token: base64url-256-bit-random
```

A request is authorized only if both values match the active pairing.

Future stronger authentication may use HMAC:

```http
X-Miniloc-Host-Id: host-7K3P
X-Miniloc-Timestamp: 1778850000123
X-Miniloc-Signature: hmac_sha256(pairing_secret, method + path + timestamp + body_hash)
```

For MVP, plain token-header authentication is acceptable on a private local network.

---

## 12. Endpoint Definitions

### 12.1 `GET /id_ping`

Purpose: allow host subnet sweep to detect miniloc.

Authentication: none.

Example response:

```json
{
  "type": "id_pong",
  "app": "miniloc",
  "version": "0.1.2",
  "instance_id": "miniloc-4N9K",
  "paired_host_id": "host-7K3P",
  "auth_required": true,
  "port": 47654
}
```

If unpaired:

```json
{
  "type": "id_pong",
  "app": "miniloc",
  "version": "0.1.2",
  "instance_id": "miniloc-4N9K",
  "paired_host_id": null,
  "auth_required": true,
  "port": 47654
}
```

### 12.2 `GET /status`

Purpose: allow authenticated host to check service state.

Authentication: required.

Example response:

```json
{
  "type": "status",
  "ok": true,
  "app": "miniloc",
  "version": "0.1.2",
  "paired_host_id": "host-7K3P",
  "foreground_service_active": true,
  "http_server_active": true,
  "location_permission": "granted",
  "notification_permission": "granted",
  "last_fix_age_ms": 315,
  "last_fix_accuracy_m": 3.2,
  "battery_pct": 73
}
```

### 12.3 `GET /loc_ping`

Purpose: return latest cached location fix.

Authentication: required.

Must not block waiting for a new fix. It returns the latest cached fix and its age.

Valid response:

```json
{
  "type": "loc_pong",
  "ok": true,
  "host_id": "host-7K3P",
  "phone_wall_time_ms": 1778850000123,
  "elapsed_realtime_ns": 482991234567,
  "age_ms": 220,
  "lat": 51.4601234,
  "lon": -0.2154321,
  "horizontal_accuracy_m": 3.2,
  "altitude_m": 21.4,
  "vertical_accuracy_m": 8.5,
  "speed_mps": 1.42,
  "speed_accuracy_mps": 0.3,
  "bearing_deg": 187.0,
  "bearing_accuracy_deg": 12.0,
  "provider": "fused",
  "mock": false,
  "battery_pct": 73
}
```

Stale/no-fix response:

```json
{
  "type": "loc_pong",
  "ok": false,
  "fix_status": "stale",
  "last_fix_age_ms": 14500
}
```

Unauthorized response:

```json
{
  "ok": false,
  "error": "unauthorized"
}
```

### 12.4 `POST /forget_pairing`

Purpose: clear active pairing.

Authentication: required, but the phone UI must also provide a local forget-pairing action that does not require host access.

Response:

```json
{
  "ok": true,
  "pairing_cleared": true
}
```

---

## 13. Host Discovery Behaviour

The host discovers `miniloc` using subnet sweep only.

No mDNS/NSD for MVP.

Recommended discovery algorithm:

```text
1. Determine local IPv4 subnet.
2. For each candidate IP, send GET http://<ip>:47654/id_ping.
3. Use short timeout, e.g. 100-300 ms.
4. Run requests concurrently with bounded concurrency.
5. Accept only responses where app == "miniloc".
6. Prefer devices where paired_host_id matches local host_id.
7. Poll /status and /loc_ping with token headers.
```

Example host polling request:

```http
GET http://<phone-ip>:47654/loc_ping
X-Miniloc-Host-Id: host-7K3P
X-Miniloc-Token: base64url-256-bit-random
```

Recommended polling rates:

```text
standby/status: 0.2 Hz
normal location recording: 1 Hz
high-detail route recording: 5 Hz
```

---

## 14. Android App UI

Keep the UI minimal.

Required screens/states:

```text
Unpaired:
  - app name: miniloc
  - explanation: "Share this phone's location with a paired local host."
  - button: Scan pairing QR
  - optional debug text field: Paste pairing payload

Pairing confirmation:
  - host_name
  - host_id
  - expiry status
  - button: Start sharing
  - button: Cancel

Active:
  - paired host name
  - service status
  - latest accuracy
  - latest fix age
  - phone IP address if available
  - port
  - button: Stop sharing
  - button: Forget pairing
```

Foreground notification:

```text
Title: miniloc active
Text: Sharing location with <host_name>
Ongoing: true
```

The user must always be able to stop sharing from the phone.

---

## 15. Location Collection Details

Use `FusedLocationProviderClient.requestLocationUpdates()`.

Initial default location request:

```text
priority: PRIORITY_HIGH_ACCURACY
interval: 1000 ms
min update interval: 500 ms
max update delay: 2000 ms
```

The cached location model must include:

```text
phone_wall_time_ms
elapsed_realtime_ns
lat
lon
horizontal_accuracy_m
altitude_m, nullable
vertical_accuracy_m, nullable
speed_mps, nullable
speed_accuracy_mps, nullable
bearing_deg, nullable
bearing_accuracy_deg, nullable
provider
mock flag
battery_pct
```

Also expose:

```text
age_ms = current elapsed realtime - location elapsed realtime
```

The host should use `age_ms` to decide whether a location fix is fresh enough.

---

## 16. Privacy and Safety Rules

The app must be transparent and locally controlled.

Rules:

- Never return location from unauthenticated endpoints.
- Never return location from `/id_ping`.
- Do not silently start location sharing from a network request.
- Show a persistent notification while location collection is active.
- Allow the user to stop sharing at any time from the phone.
- Allow the user to forget the active pairing.
- Bind only to local interfaces; do not contact the public internet.
- Do not include cloud telemetry or analytics.

---

## 17. Minimum Acceptance Tests

### Build and Install

```text
- Project builds with ./gradlew assembleDebug on Ubuntu CLI.
- APK installs using adb install -r.
- App launches on Android phone.
```

### Permissions

```text
- App requests location permission.
- App requests notification permission on Android 13+.
- App requests camera permission before QR scan.
```

### Pairing

```text
- App can parse a miniloc_pair JSON payload.
- App displays host_name and host_id before starting.
- App stores active pairing after user confirmation.
- App can forget pairing.
```

### Foreground Location Service

```text
- Foreground notification appears when sharing starts.
- Location updates continue after screen lock.
- Latest cached location updates over time.
```

### Local HTTP Server

```text
- Server binds to 0.0.0.0:47654.
- /id_ping responds without auth.
- /id_ping never returns location.
- /loc_ping rejects missing/incorrect token.
- /loc_ping returns latest cached fix with correct token.
- /status returns service state with correct token.
```

### Host Discovery

```text
- A separate machine on the same LAN can discover the phone by sweeping port 47654.
- Host can identify miniloc via /id_ping.
- Host can poll /loc_ping at 1 Hz.
```

---

## 18. Simple Test Host Script

Create a small Python script for local testing.

Expected behaviour:

```text
python miniloc_discover.py --subnet 192.168.4.0/24 --host-id host-7K3P --token <token>
```

The script should:

```text
1. Sweep subnet on port 47654.
2. Call /id_ping.
3. Print discovered miniloc candidates.
4. Poll /loc_ping once per second for selected candidate.
5. Print JSON responses.
```

Use `httpx` or `aiohttp`.

---

## 19. Suggested Codex Implementation Order

1. Create minimal Gradle Android project.
2. Add manifest permissions and foreground service declaration.
3. Implement `LocationCache` data model.
4. Implement `LocationForegroundService` using fused location provider.
5. Implement minimal `MainActivity` with Start/Stop buttons.
6. Implement pairing payload parser and `PairingStore`.
7. Implement `MinilocHttpServer` with `/id_ping`, `/status`, `/loc_ping`.
8. Add token-header authentication.
9. Add simple QR scan or debug paste input.
10. Add test host discovery script.
11. Add README with Ubuntu build/install instructions.

---

## 20. Codex Prompt

Use this prompt to continue implementation:

```text
You are working on a minimal native Android Kotlin app called miniloc.

Goal:
Build an Android app that turns a phone into a local-network location sensor for a paired host device.

Important constraints:
- Do not use product-specific naming or references.
- Native Kotlin Android.
- Command-line build on Ubuntu using ./gradlew assembleDebug.
- No Android Studio assumptions.
- No Compose for MVP.
- No cloud services, maps, accounts, analytics, or mDNS.
- Use a foreground location service with foregroundServiceType="location".
- Use FusedLocationProviderClient to keep a cached latest location fix.
- Run a local HTTP server bound to 0.0.0.0:47654.
- Host discovery is by subnet sweep calling /id_ping.
- /id_ping is public but never returns location.
- /loc_ping and /status require X-Miniloc-Host-Id and X-Miniloc-Token headers.
- Pairing is done by scanning or pasting a QR JSON payload containing host_id, host_name, pairing_token, expiry, and miniloc_port.
- User must locally confirm before sharing starts.
- The phone UI must always allow Stop Sharing and Forget Pairing.

Implement the app incrementally and keep the project simple.
```
