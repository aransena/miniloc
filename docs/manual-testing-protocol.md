# miniloc Manual Testing Protocol

Version: `0.2`

Use this protocol before treating a build as ready for field use. Record the
results in the release notes or issue tracker for the build under test.

## Test Environment

- Android phone model:
- Android version:
- App version and APK path:
- Host OS:
- Wi-Fi/LAN SSID or network description:
- Phone IP:
- Host IP:
- Subnet used for discovery:
- Tester:
- Test date:

## Prerequisites

- Phone and host are on the same LAN.
- Android developer options and USB debugging are enabled if using
  `tools/deploy_android.sh`.
- Host has Python 3, `httpx`, `zeroconf`, and `qrcode[pil]` installed for helper tools.
- The phone has no old miniloc pairing, or the tester is prepared to tap
  `Forget pairing`.

## Build And Install

1. Run `./gradlew assembleDebug`.
2. Install with `tools/deploy_android.sh` or `adb install -r`.
3. Launch miniloc.
4. Grant precise location and notification permissions if prompted. Background
   location can be granted from app settings to validate restart resilience.
5. Confirm the app opens to the Open LAN sharing screen.

Pass criteria:

- Debug APK builds successfully.
- App installs and launches.
- Open LAN sharing starts automatically after required Android permissions are granted.
- No crash or Android permission error is shown on first open.

## Default Open LAN Flow

1. Launch miniloc.
2. Grant precise location and notification permissions if prompted. Background
   location can be granted from app settings to validate restart resilience.
3. Discover with mDNS and subnet fallback:

   ```bash
   python3 tools/miniloc_discover.py --subnet <subnet> --no-poll
   ```

4. Poll without credentials:

   ```bash
   python3 tools/miniloc_poll.py <phone-ip> --once
   ```

Pass criteria:

- Open LAN sharing starts automatically after app launch and required Android permission grants.
- Android shows the persistent `miniloc active` foreground-service notification.
- `/id_ping` reports `access_mode=open_lan` and `auth_required=false`.
- `/status` reports `availability_wake_lock_active=true`,
  `availability_wifi_lock_active=true`, and
  `availability_multicast_lock_active=true`.
- `/status` reports `network_publish_error=null`; for long locked-screen tests,
  `battery_optimization_ignored=true` is preferred.
- `/status` reports `power_save_mode_active=false`; Battery Saver can throttle
  locked-screen location and local-network reachability.
- `/status`, `/loc_ping`, and `/loc_history` return HTTP `200` without pairing headers.
- `/forget_pairing` is not available without paired credentials.
- Tapping `Stop sharing` disables Open LAN read access for the current app session.

## Pairing And Permission Flow

1. Generate a QR payload:

   ```bash
   python3 tools/miniloc_pairing_qr.py --host-id TEST
   ```

   The QR text printed to stdout must use `ML1:<host_id>:<pairing_token>`.

2. In miniloc, tap `Pair with QR instead`.
3. Grant camera permission if prompted.
4. Scan `miniloc_pairing_qr.png`.
5. Confirm the displayed host name and host ID match the generated payload.
6. Tap `Start sharing`.
7. Grant precise location and notification permissions if prompted. Background
   location can be granted from app settings to validate restart resilience.

Pass criteria:

- Malformed QR messages are rejected with an error.
- Valid QR payload shows a confirmation screen before sharing starts.
- Sharing only starts after local confirmation.
- Android shows the persistent `miniloc active` foreground-service notification.

## Discovery And Authentication

1. Discover without polling:

   ```bash
   python3 tools/miniloc_discover.py --subnet <subnet> --no-poll
   ```

2. Confirm `/id_ping` returns `app=miniloc`, `type=id_pong`, `instance_id`,
   `access_mode`, `paired_host_id`, `auth_required`, and `port`, but no
   latitude, longitude, or location fields.
3. Poll with missing or wrong credentials:

   ```bash
   curl -i http://<phone-ip>:47654/loc_ping
   ```

4. Poll with the generated host ID and token:

   ```bash
   python3 tools/miniloc_poll.py <phone-ip> \
     --host-id <host-id> \
     --token <pairing-token> \
     --once
   ```

Pass criteria:

- `/id_ping` is reachable and location-free.
- In paired mode, unauthenticated `/loc_ping` returns HTTP `401`.
- Authenticated `/status` and `/loc_ping` return HTTP `200`.
- `/loc_ping` returns either `ok=true` with a recent fix or `ok=false` with
  `fix_status=no_fix` while the phone is still acquiring location.

## Location Quality And Continuity

1. Poll compact diagnostics at 5 Hz:

   ```bash
   python3 tools/miniloc_poll.py <phone-ip> \
     --host-id <host-id> \
     --token <pairing-token> \
     --interval 0.2 \
     --compact
   ```

2. Walk with the phone for at least 60 seconds.
3. Lock the screen and continue polling for at least 60 seconds.
4. Record typical `age_ms`, `delivery_latency_ms`, `source_batch_size`,
   `fix_sequence`, and whether `same_fix` becomes stuck.

Pass criteria:

- `fix_sequence` advances during normal movement.
- `age_ms` remains acceptable for the deployment target.
- Direct-IP polling continues while the phone is locked.
- `/status` keeps reporting active availability locks, `network_publish_error=null`,
  `power_save_mode_active=false`, and a recent `network_last_refresh_age_ms`
  while the phone is locked.
- Android may batch updates, but `age_ms` must not grow indefinitely while
  sharing is active and location services are available.

## IP Change And Rediscovery

1. Start polling with `--subnet` and no explicit IP after the first successful
   cache write:

   ```bash
   python3 tools/miniloc_poll.py \
     --subnet <subnet> \
     --host-id <host-id> \
     --token <pairing-token> \
     --compact
   ```

2. Toggle Wi-Fi on the phone or reconnect to the same LAN.
3. Continue polling.

Pass criteria:

- Poller confirms candidates with authenticated `/status`.
- Poller rediscoveries the phone if the IP changes.
- Cached IP is treated as a hint, not identity.

## Pairing Expiry

1. Generate a short-lived payload:

   ```bash
   python3 tools/miniloc_pairing_qr.py \
     --host-id EXPY
   ```

2. Pair and start sharing.
3. Confirm authenticated `/status` works immediately after sharing starts.
4. Wait at least 605 seconds after scanning the QR.
5. Call authenticated `/status` again.
6. Open the app and inspect the UI.

Pass criteria:

- After expiry, authenticated endpoints return HTTP `401`.
- The foreground service stops automatically.
- Cached location fixes are cleared.
- The UI returns to the unpaired state.

## Stop And Forget

1. Pair again with a normal payload.
2. Tap `Stop sharing` from the notification.
3. Confirm `/status` reports `foreground_service_active=false` or is
   unreachable if the HTTP server has stopped.
4. Start sharing again from the app.
5. Tap `Forget pairing`.
6. Try authenticated `/status` with the old token.

Pass criteria:

- `Stop sharing` stops collection and the HTTP server.
- `Start sharing` resumes only from the phone UI.
- `Forget pairing` clears credentials and cached fixes.
- Old credentials no longer authorize any endpoint.

## Release Signing Smoke Test

1. Create or provide a release keystore outside the repo.
2. Export:

   ```bash
   export MINILOC_RELEASE_STORE_FILE=/absolute/path/miniloc-release.jks
   export MINILOC_RELEASE_STORE_PASSWORD='<store-password>'
   export MINILOC_RELEASE_KEY_ALIAS='<key-alias>'
   export MINILOC_RELEASE_KEY_PASSWORD='<key-password>'
   ```

3. Run `./gradlew packageSignedRelease`.
4. Confirm `app/build/outputs/apk/release/app-release.apk` exists and is signed.

Pass criteria:

- `packageSignedRelease` fails clearly when signing variables are missing.
- With a valid keystore, the release APK is signed and installable.

## Failure Report Template

```text
build:
phone:
android_version:
network:
step:
expected:
actual:
logs_or_command_output:
screenshots_or_notes:
```
