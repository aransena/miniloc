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
- Host has Python 3, `httpx`, and `qrcode[pil]` installed for helper tools.
- The phone has no old miniloc pairing, or the tester is prepared to tap
  `Forget pairing`.

## Build And Install

1. Run `./gradlew assembleDebug`.
2. Install with `tools/deploy_android.sh` or `adb install -r`.
3. Launch miniloc.
4. Confirm the app opens to the unpaired screen.

Pass criteria:

- Debug APK builds successfully.
- App installs and launches.
- No crash or Android permission error is shown on first open.

## Pairing And Permission Flow

1. Generate a QR payload:

   ```bash
   python3 tools/miniloc_pairing_qr.py --host-name "Manual Test Host"
   ```

2. In miniloc, tap `Scan pairing QR`.
3. Grant camera permission if prompted.
4. Scan `miniloc_pairing_qr.png`.
5. Confirm the displayed host name and host ID match the generated payload.
6. Tap `Start sharing`.
7. Grant precise location and notification permissions if prompted.

Pass criteria:

- Expired or malformed QR payloads are rejected with an error.
- Valid QR payload shows a confirmation screen before sharing starts.
- Sharing only starts after local confirmation.
- Android shows the persistent `miniloc active` foreground-service notification.

## Discovery And Authentication

1. Discover without polling:

   ```bash
   python3 tools/miniloc_discover.py --subnet <subnet> --no-poll
   ```

2. Confirm `/id_ping` returns `app=miniloc`, `type=id_pong`, `instance_id`,
   `paired_host_id`, and `port`, but no latitude, longitude, or location fields.
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
- Unauthenticated `/loc_ping` returns HTTP `401`.
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
- Polling continues while the phone is locked.
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
     --host-name "Expiry Test Host" \
     --expires-in-s 30
   ```

2. Pair and start sharing before the 30 seconds expires.
3. Confirm authenticated `/status` works immediately after sharing starts.
4. Wait until at least 35 seconds after `issued_at`.
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
