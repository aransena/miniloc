# miniloc Python API Bundle

Version: `0.2.2`

This directory is a copy-in Python client for consuming data from an Android phone running `miniloc`.

Use this bundle in another project by copying the `miniloc_api/` package into that project, or by keeping this whole directory and adding it to `PYTHONPATH`.

## Agent Instructions

When working in the target project:

- Treat `miniloc` as a local-network phone location sensor.
- Do not add cloud services, account flows, maps, analytics, cross-network discovery, or product-specific naming for this integration.
- Do not call paired `/loc_ping`, `/status`, or `/forget_pairing` without the paired host credentials.
- Open LAN devices advertise `auth_required=false`; read-only `/status`, `/loc_ping`, and `/loc_history` may be called without credentials in that mode.
- Never expect `/id_ping` to include location. It is intentionally public and location-free.
- Do not remotely start Android location sharing from Python. Open LAN sharing starts from launching the phone app; QR pairing is an optional restricted mode.
- Use `age_ms` and `ok` from `/loc_ping` before trusting a fix.
- Handle `ok: false` responses such as `no_fix` and `stale` as normal runtime states, not transport failures.
- Treat `MinilocUnauthorized` as a pairing or credential problem.

## Files

```text
miniloc_api/
  __init__.py
  client.py
examples/
  build_pairing_payload.py
  discover_open_lan.py
  discover_and_poll.py
  poll_known_phone.py
README.agent.md
VERSION
../schemas/
../fixtures/api/
../openapi/
../contracts/
../tools/
```

The HTTP client and subnet scanner use only Python standard library modules.
Optional mDNS discovery requires `zeroconf`. The adjacent contract, schema,
fixture, and OpenAPI directories are included in the dist bundle so copied
integrations can run the contract tests without the full Android source tree.

## Minimal Integration

```python
from miniloc_api import MinilocAuth, MinilocClient

client = MinilocClient(
    ip="192.168.4.23",
    auth=MinilocAuth(host_id="7K3P", token="pairing-token"),
)

status = client.status()
location = client.loc_ping()
history = client.loc_history()

if location.get("ok") and location.get("age_ms", 999999) < 5000:
    lat = location["lat"]
    lon = location["lon"]
```

For robust same-subnet operation across phone IP changes, prefer `MinilocResolver`:

```python
from miniloc_api import MinilocAuth, MinilocResolver

resolver = MinilocResolver(
    subnet="192.168.4.0/24",
    auth=MinilocAuth("7K3P", "pairing-token"),
    cached_ip="192.168.4.23",      # optional hint
    instance_id="miniloc-4N9K",    # optional stable phone identity
)

location = resolver.loc_ping()
print(resolver.cached_ip, resolver.instance_id)
```

The resolver tries the cached IP first, confirms candidates with authenticated `/status`, then sweeps the subnet if the cached IP is stale.

Store `resolver.cached_ip` and `resolver.instance_id` in the host project after a successful resolve. Do not treat IP as identity.

## Discovery

```python
from miniloc_api import discover_miniloc, discover_miniloc_auto

devices = discover_miniloc("192.168.4.0/24", preferred_host_id="7K3P")
for device in devices:
    print(device.ip, device.paired_host_id, device.instance_id)

open_devices = [device for device in discover_miniloc_auto("192.168.4.0/24") if device.auth_required is False]
```

`discover_miniloc_auto` tries optional mDNS/DNS-SD discovery for
`_miniloc._tcp.local` first, then falls back to subnet `/id_ping` when a subnet
is supplied. mDNS support requires `python3 -m pip install zeroconf`.

## Polling

```python
from miniloc_api import MinilocAuth, MinilocClient

client = MinilocClient("192.168.4.23", MinilocAuth("7K3P", "pairing-token"))

for response in client.poll_locations(interval_s=1.0):
    if not response.get("ok"):
        print("No usable fix:", response)
        continue
    print(response["lat"], response["lon"], response["age_ms"])
```

For high-detail reads, use `interval_s=0.2`. The phone app requests 5 Hz fused-location updates, but Android may still deliver slower fixes depending on sensor/GNSS conditions and power policy.

`age_ms` is the age of the latest cached phone fix at response time. It should usually stay low if new fixes are arriving. Use `fix_sequence` and `elapsed_realtime_ns` deltas between responses to distinguish new fixes from repeated polls of the same cached fix.

`/loc_ping` includes `recent_fixes`, the phone's rolling list of the last 10 timestamped fixes. Use `client.loc_history()` when the target project wants only that rolling list.

Use `delivery_latency_ms` and `source_batch_size` to diagnose Android fused-location batching. `delivery_latency_ms` is the delay between Android's fix timestamp and miniloc receiving that fix; `source_batch_size > 1` means several fixes arrived in one callback batch.

## Pairing Messages

If the host project already has a QR renderer, use:

```python
from miniloc_api.client import build_pairing_payload, pairing_payload_message

payload = build_pairing_payload(
    host_id="7K3P",
)
qr_text = pairing_payload_message(payload)
token = payload["pairing_token"]
```

Display `qr_text` as a QR code. It uses the ultra-compact
`ML1:<host_id>:<pairing_token>` format. The phone app scans this message, shows
the host ID, and starts sharing only after local confirmation.

## Endpoint Contract

Public:

```text
GET /id_ping
```

Authenticated with headers:

```http
X-Miniloc-Host-Id: <host_id>
X-Miniloc-Token: <pairing_token>
```

Protected:

```text
GET  /status
GET  /loc_ping
GET  /loc_history
POST /forget_pairing
```

Default port: `47654`.

Open LAN mode:

```text
Phone user launches miniloc and grants required Android permissions.
Consumers discover _miniloc._tcp.local or scan a subnet.
Consumers verify /id_ping has auth_required=false.
Consumers call /status, /loc_ping, and /loc_history without headers.
```

## Example Commands

From this bundle directory:

```bash
PYTHONPATH=. python3 examples/discover_and_poll.py \
  --subnet 192.168.4.0/24 \
  --host-id 7K3P \
  --token '<pairing_token>' \
  --once
```

```bash
PYTHONPATH=. python3 examples/poll_known_phone.py 192.168.4.23 \
  --host-id 7K3P \
  --token '<pairing_token>' \
  --once
```

```bash
PYTHONPATH=. python3 examples/discover_open_lan.py \
  --subnet 192.168.4.0/24 \
  --once
```
