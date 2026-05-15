# miniloc

`miniloc` is a minimal native Android app that turns a phone into a local-network location sensor for one paired host device.

The app uses camera QR pairing, a foreground fused-location service, and a local HTTP server on port `47654`. It does not use cloud services, login, maps, analytics, mDNS, or product-specific naming.

## Build

From this repository:

```bash
./gradlew assembleDebug
```

The wrapper bootstraps Gradle, Android command-line tools, SDK 35, and a workspace-local JDK if the host machine does not already provide the required tooling.

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or build, install to the connected phone, grant runtime permissions, and launch:

```bash
tools/deploy_android.sh
```

For a specific phone serial:

```bash
tools/deploy_android.sh --device 143bdfa2
```

## Pairing

Install host script dependencies:

```bash
python3 -m pip install --user httpx 'qrcode[pil]'
```

Generate a short-lived pairing QR on the host:

```bash
python3 tools/miniloc_pairing_qr.py --host-name "Field Recorder 7K3P"
```

Open `miniloc` on the phone, tap `Scan pairing QR`, scan `miniloc_pairing_qr.png`, confirm the host name and host ID, then tap `Start sharing`.

The QR payload format is:

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

## Discovery And Polling

Discover phones on a subnet:

```bash
python3 tools/miniloc_discover.py --subnet 192.168.4.0/24 --no-poll
```

Discover and poll the paired phone at 1 Hz:

```bash
python3 tools/miniloc_discover.py \
  --subnet 192.168.4.0/24 \
  --host-id host-7K3P \
  --token '<pairing_token>'
```

`tools/miniloc_discover.py` and `tools/miniloc_poll.py --subnet ...` treat the phone IP as temporary. They confirm the phone with authenticated `/status`, remember `instance_id`, and rediscover on the same subnet if polling the cached IP fails.

For high-detail polling at 5 Hz, match the app's faster cached-location request:

```bash
python3 tools/miniloc_discover.py \
  --subnet 192.168.4.0/24 \
  --host-id host-7K3P \
  --token '<pairing_token>' \
  --interval 0.2
```

Poll a known phone IP:

```bash
python3 tools/miniloc_poll.py 192.168.4.23 \
  --host-id host-7K3P \
  --token '<pairing_token>'
```

Poll with automatic same-subnet rediscovery if the phone IP changes:

```bash
python3 tools/miniloc_poll.py 192.168.4.23 \
  --subnet 192.168.4.0/24 \
  --host-id host-7K3P \
  --token '<pairing_token>'
```

After the first successful run, the poller stores `last_seen_ip` and `instance_id` in `~/.cache/miniloc/last_device.json`, so this also works:

```bash
python3 tools/miniloc_poll.py \
  --subnet 192.168.4.0/24 \
  --host-id host-7K3P \
  --token '<pairing_token>'
```

The Android service requests high-accuracy fused location updates every `200 ms`, with a `250 ms` max batching delay. Android may still deliver slower updates depending on hardware, signal quality, battery saver, thermal state, screen state, and whether precise location is granted.

To inspect whether new fixes are actually arriving, use compact polling diagnostics:

```bash
python3 tools/miniloc_poll.py 192.168.4.23 \
  --host-id host-7K3P \
  --token '<pairing_token>' \
  --interval 0.2 \
  --compact
```

`age_ms` is the age of the latest cached phone fix at response time. Smaller is better. If you poll once per second and see values like `122`, `347`, `556`, the phone is receiving newer fixes between polls; if the cache were stuck, `age_ms` would rise by about `1000 ms` on each poll. Use `fix_sequence` or compact `fix_delta_ms` to measure the actual phone-side fix cadence.

`delivery_latency_ms` is the delay between Android's fix timestamp and when miniloc received that fix from the fused-location callback. If this grows or `source_batch_size` is greater than `1`, Android is batching fixes before delivering them to the app. miniloc sorts each callback batch by timestamp and only lets newer fixes replace the latest cached fix.

## HTTP API

The phone binds its server to `0.0.0.0:47654`.

Public endpoint:

```text
GET /id_ping
```

Authenticated endpoints require:

```http
X-Miniloc-Host-Id: host-7K3P
X-Miniloc-Token: <pairing_token>
```

Endpoints:

```text
GET  /status
GET  /loc_ping
GET  /loc_history
POST /forget_pairing
```

`/id_ping` never returns location. Unauthenticated `/loc_ping` returns HTTP `401`.

`/loc_ping` returns the latest cached fix and a `recent_fixes` array containing the last 10 timestamped fixes. `/loc_history` returns the same rolling list without duplicating the latest-fix top-level fields.

## Troubleshooting

- Keep the phone and host on the same LAN.
- Confirm the phone granted camera, precise location, and notification permissions.
- Confirm Android shows the persistent `miniloc active` foreground-service notification.
- Disable VPN/client isolation on the Wi-Fi network if the host cannot reach the phone IP.
- Use the phone UI to stop sharing or forget the pairing at any time.
- If `./gradlew assembleDebug` fails while downloading dependencies, rerun it with working internet access.
