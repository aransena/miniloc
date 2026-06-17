from __future__ import annotations

import base64
import ipaddress
import json
import re
import secrets
import socket
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from threading import Event
from typing import Any, Iterator


DEFAULT_PORT = 47654
DEFAULT_TIMEOUT_S = 2.0
MINILOC_MDNS_SERVICE_TYPE = "_miniloc._tcp.local."
PAIRING_HOST_ID_RE = re.compile(r"[A-Z0-9][A-Z0-9-]{0,31}")
PAIRING_TOKEN_RE = re.compile(r"[A-Z2-7]{16,64}")


class MinilocError(RuntimeError):
    """Base error for miniloc client failures."""


class MinilocHTTPError(MinilocError):
    """Raised when miniloc returns a non-success HTTP status."""

    def __init__(self, status_code: int, body: dict[str, Any] | None = None):
        self.status_code = status_code
        self.body = body or {}
        super().__init__(f"miniloc HTTP {status_code}: {self.body}")


class MinilocUnauthorized(MinilocHTTPError):
    """Raised when host ID/token authentication fails."""


@dataclass(frozen=True)
class MinilocAuth:
    host_id: str
    token: str

    def headers(self) -> dict[str, str]:
        return {
            "X-Miniloc-Host-Id": self.host_id,
            "X-Miniloc-Token": self.token,
        }


@dataclass(frozen=True)
class MinilocDevice:
    ip: str
    port: int
    instance_id: str | None
    paired_host_id: str | None
    raw: dict[str, Any]
    access_mode: str | None = None
    auth_required: bool | None = None
    discovery_source: str = "subnet"

    @property
    def base_url(self) -> str:
        return f"http://{self.ip}:{self.port}"


class MinilocClient:
    """Synchronous client for a single miniloc phone."""

    def __init__(
        self,
        ip: str,
        auth: MinilocAuth | None = None,
        port: int = DEFAULT_PORT,
        timeout_s: float = DEFAULT_TIMEOUT_S,
    ):
        self.ip = ip
        self.port = port
        self.auth = auth
        self.timeout_s = timeout_s

    @property
    def base_url(self) -> str:
        return f"http://{self.ip}:{self.port}"

    def id_ping(self) -> dict[str, Any]:
        """Call public /id_ping. This never returns location."""
        return self._request("GET", "/id_ping", auth_required=False)

    def status(self) -> dict[str, Any]:
        """Call /status. Open LAN mode does not require auth; paired mode does."""
        return self._request("GET", "/status", auth_required=False)

    def loc_ping(self) -> dict[str, Any]:
        """Call /loc_ping and return the phone's latest cached fix response."""
        return self._request("GET", "/loc_ping", auth_required=False)

    def loc_history(self) -> dict[str, Any]:
        """Call /loc_history and return the rolling list of recent fixes."""
        return self._request("GET", "/loc_history", auth_required=False)

    def forget_pairing(self) -> dict[str, Any]:
        """Call authenticated /forget_pairing."""
        return self._request("POST", "/forget_pairing", auth_required=True)

    def poll_locations(self, interval_s: float = 1.0, limit: int | None = None) -> Iterator[dict[str, Any]]:
        """Yield /loc_ping responses at a fixed interval.

        Responses where ok is false, such as no_fix or stale, are yielded to the caller.
        Authentication failures raise MinilocUnauthorized.
        """
        count = 0
        while limit is None or count < limit:
            yield self.loc_ping()
            count += 1
            if limit is None or count < limit:
                time.sleep(interval_s)

    def _request(self, method: str, path: str, auth_required: bool) -> dict[str, Any]:
        headers = {"Accept": "application/json"}
        if self.auth is not None:
            headers.update(self.auth.headers())
        elif auth_required:
            raise MinilocError(f"{path} requires MinilocAuth")

        request = urllib.request.Request(
            f"{self.base_url}{path}",
            method=method,
            headers=headers,
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_s) as response:
                return _decode_json(response.read())
        except urllib.error.HTTPError as error:
            body = _read_error_json(error)
            if error.code == 401:
                raise MinilocUnauthorized(error.code, body) from error
            raise MinilocHTTPError(error.code, body) from error
        except urllib.error.URLError as error:
            raise MinilocError(f"Could not reach miniloc at {self.base_url}: {error}") from error


@dataclass(frozen=True)
class MinilocResolution:
    ip: str
    port: int
    instance_id: str | None
    status: dict[str, Any]
    device: MinilocDevice | None = None


class MinilocResolver:
    """Resolve a paired miniloc phone on a same-subnet network.

    The cached IP is a hint, not identity. The resolver confirms candidates with
    authenticated /status and then updates cached_ip.
    """

    def __init__(
        self,
        subnet: str,
        auth: MinilocAuth,
        port: int = DEFAULT_PORT,
        cached_ip: str | None = None,
        instance_id: str | None = None,
        timeout_s: float = DEFAULT_TIMEOUT_S,
        discovery_timeout_s: float = 0.25,
        max_workers: int = 128,
    ):
        self.subnet = subnet
        self.auth = auth
        self.port = port
        self.cached_ip = cached_ip
        self.instance_id = instance_id
        self.timeout_s = timeout_s
        self.discovery_timeout_s = discovery_timeout_s
        self.max_workers = max_workers

    def resolve(self) -> MinilocResolution:
        """Return a confirmed phone location, rediscovering if the cached IP fails."""
        if self.cached_ip:
            resolution = self._confirm_ip(self.cached_ip, self.port, None)
            if resolution is not None:
                return resolution

        devices = discover_miniloc(
            self.subnet,
            port=self.port,
            timeout_s=self.discovery_timeout_s,
            max_workers=self.max_workers,
            preferred_host_id=self.auth.host_id,
        )

        if self.instance_id:
            devices.sort(key=lambda device: device.instance_id != self.instance_id)

        for device in devices:
            resolution = self._confirm_ip(device.ip, device.port, device)
            if resolution is not None:
                return resolution

        raise MinilocError(f"No authenticated miniloc device found on {self.subnet}")

    def client(self) -> MinilocClient:
        resolution = self.resolve()
        return MinilocClient(
            resolution.ip,
            auth=self.auth,
            port=resolution.port,
            timeout_s=self.timeout_s,
        )

    def status(self) -> dict[str, Any]:
        return self._call_with_rediscovery("status")

    def loc_ping(self) -> dict[str, Any]:
        return self._call_with_rediscovery("loc_ping")

    def loc_history(self) -> dict[str, Any]:
        return self._call_with_rediscovery("loc_history")

    def poll_locations(self, interval_s: float = 1.0, limit: int | None = None) -> Iterator[dict[str, Any]]:
        count = 0
        while limit is None or count < limit:
            yield self.loc_ping()
            count += 1
            if limit is None or count < limit:
                time.sleep(interval_s)

    def _call_with_rediscovery(self, method_name: str) -> dict[str, Any]:
        if not self.cached_ip:
            self.resolve()
        assert self.cached_ip is not None
        client = MinilocClient(
            self.cached_ip,
            auth=self.auth,
            port=self.port,
            timeout_s=self.timeout_s,
        )
        try:
            return getattr(client, method_name)()
        except MinilocUnauthorized:
            raise
        except MinilocHTTPError:
            raise
        except MinilocError:
            self.cached_ip = None
            self.resolve()
            assert self.cached_ip is not None
            return getattr(
                MinilocClient(
                    self.cached_ip,
                    auth=self.auth,
                    port=self.port,
                    timeout_s=self.timeout_s,
                ),
                method_name,
            )()

    def _confirm_ip(self, ip: str, port: int, device: MinilocDevice | None) -> MinilocResolution | None:
        client = MinilocClient(ip, auth=self.auth, port=port, timeout_s=self.timeout_s)
        try:
            status = client.status()
        except MinilocUnauthorized:
            raise
        except MinilocError:
            return None

        if status.get("app") != "miniloc" or status.get("ok") is not True:
            return None
        if status.get("paired_host_id") != self.auth.host_id:
            return None
        status_instance_id = status.get("instance_id") or (device.instance_id if device else None)
        if self.instance_id and status_instance_id != self.instance_id:
            return None

        self.cached_ip = ip
        self.port = int(status.get("port") or port)
        self.instance_id = status_instance_id
        return MinilocResolution(
            ip=ip,
            port=self.port,
            instance_id=self.instance_id,
            status=status,
            device=device,
        )


def discover_miniloc(
    subnet: str,
    port: int = DEFAULT_PORT,
    timeout_s: float = 0.25,
    max_workers: int = 128,
    preferred_host_id: str | None = None,
) -> list[MinilocDevice]:
    """Sweep an IPv4 subnet for phones responding to /id_ping."""
    network = ipaddress.ip_network(subnet, strict=False)
    workers = max(1, max_workers)
    candidates: list[MinilocDevice] = []

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(_probe_ip, str(ip), port, timeout_s): str(ip)
            for ip in network.hosts()
        }
        for future in as_completed(futures):
            device = future.result()
            if device is not None:
                candidates.append(device)

    candidates.sort(key=lambda device: device.ip)
    if preferred_host_id is None:
        return candidates
    return sorted(
        candidates,
        key=lambda device: device.paired_host_id != preferred_host_id,
    )


def discover_miniloc_mdns(
    timeout_s: float = 2.0,
    service_type: str = MINILOC_MDNS_SERVICE_TYPE,
) -> list[MinilocDevice]:
    """Discover miniloc devices advertised over mDNS/DNS-SD.

    This optional helper requires the third-party ``zeroconf`` package. It verifies
    each mDNS result with /id_ping before returning it.
    """
    try:
        from zeroconf import ServiceBrowser, Zeroconf
    except ImportError as error:
        raise MinilocError("mDNS discovery requires: python3 -m pip install zeroconf") from error

    devices: list[MinilocDevice] = []
    seen: set[tuple[str, int]] = set()
    finished = Event()

    class Listener:
        def add_service(self, zeroconf: Any, type_: str, name: str) -> None:
            info = zeroconf.get_service_info(type_, name, timeout=int(timeout_s * 1000))
            if info is None:
                return
            for ip in _service_addresses(info):
                port = int(getattr(info, "port", DEFAULT_PORT) or DEFAULT_PORT)
                key = (ip, port)
                if key in seen:
                    continue
                seen.add(key)
                client = MinilocClient(ip=ip, port=port, timeout_s=DEFAULT_TIMEOUT_S)
                try:
                    body = client.id_ping()
                except MinilocError:
                    continue
                device = _device_from_id_ping(ip, port, body, discovery_source="mdns")
                if device is not None:
                    devices.append(device)

        def update_service(self, zeroconf: Any, type_: str, name: str) -> None:
            self.add_service(zeroconf, type_, name)

        def remove_service(self, zeroconf: Any, type_: str, name: str) -> None:
            return None

    zeroconf = Zeroconf()
    try:
        ServiceBrowser(zeroconf, service_type, Listener())
        finished.wait(timeout_s)
    finally:
        zeroconf.close()

    return sorted(devices, key=lambda device: (device.ip, device.port))


def discover_miniloc_auto(
    subnet: str | None = None,
    port: int = DEFAULT_PORT,
    timeout_s: float = 0.25,
    max_workers: int = 128,
    preferred_host_id: str | None = None,
    mdns_timeout_s: float = 2.0,
) -> list[MinilocDevice]:
    """Try mDNS discovery first, then optional subnet /id_ping scanning."""
    devices: list[MinilocDevice] = []
    try:
        devices.extend(discover_miniloc_mdns(timeout_s=mdns_timeout_s))
    except MinilocError:
        pass
    if subnet:
        devices.extend(
            discover_miniloc(
                subnet,
                port=port,
                timeout_s=timeout_s,
                max_workers=max_workers,
                preferred_host_id=preferred_host_id,
            )
        )

    deduped: dict[tuple[str, int], MinilocDevice] = {}
    for device in devices:
        deduped.setdefault((device.ip, device.port), device)
    result = sorted(deduped.values(), key=lambda device: (device.ip, device.port))
    if preferred_host_id is None:
        return result
    return sorted(result, key=lambda device: device.paired_host_id != preferred_host_id)


def build_pairing_payload(
    host_id: str,
    token: str | None = None,
) -> dict[str, Any]:
    """Build host pairing data for QR rendering by the host app."""
    host_id = host_id.strip()
    if not host_id:
        raise ValueError("host_id must be non-empty")
    if not PAIRING_HOST_ID_RE.fullmatch(host_id):
        raise ValueError("host_id must use A-Z, 0-9, or '-' and be at most 32 characters")
    pairing_token = token or _random_token()
    if not PAIRING_TOKEN_RE.fullmatch(pairing_token):
        raise ValueError("token must be uppercase base32 without padding")
    return {
        "scheme": "ML1",
        "host_id": host_id,
        "pairing_token": pairing_token,
    }


def pairing_payload_message(payload: dict[str, Any]) -> str:
    """Serialize pairing data as the miniloc QR message."""
    return f"{payload.get('scheme', 'ML1')}:{payload['host_id']}:{payload['pairing_token']}"


def _probe_ip(ip: str, port: int, timeout_s: float) -> MinilocDevice | None:
    client = MinilocClient(ip=ip, port=port, timeout_s=timeout_s)
    try:
        body = client.id_ping()
    except MinilocError:
        return None
    return _device_from_id_ping(ip, port, body, discovery_source="subnet")


def _device_from_id_ping(
    ip: str,
    port: int,
    body: dict[str, Any],
    discovery_source: str,
) -> MinilocDevice | None:
    if body.get("type") != "id_pong" or body.get("app") != "miniloc":
        return None
    return MinilocDevice(
        ip=ip,
        port=int(body.get("port") or port),
        instance_id=body.get("instance_id"),
        paired_host_id=body.get("paired_host_id"),
        raw=body,
        access_mode=body.get("access_mode"),
        auth_required=body.get("auth_required"),
        discovery_source=discovery_source,
    )


def _service_addresses(info: Any) -> list[str]:
    parsed_addresses = getattr(info, "parsed_addresses", None)
    if callable(parsed_addresses):
        return [address for address in parsed_addresses() if _is_ipv4(address)]
    addresses = getattr(info, "addresses", []) or []
    return [
        socket.inet_ntoa(address)
        for address in addresses
        if isinstance(address, bytes) and len(address) == 4
    ]


def _is_ipv4(address: str) -> bool:
    try:
        ipaddress.ip_address(address)
    except ValueError:
        return False
    return "." in address


def _decode_json(raw: bytes) -> dict[str, Any]:
    try:
        decoded = json.loads(raw.decode("utf-8"))
    except Exception as error:
        raise MinilocError(f"Response was not valid JSON: {raw[:200]!r}") from error
    if not isinstance(decoded, dict):
        raise MinilocError(f"Response JSON was not an object: {decoded!r}")
    return decoded


def _read_error_json(error: urllib.error.HTTPError) -> dict[str, Any]:
    try:
        return _decode_json(error.read())
    except MinilocError:
        return {"ok": False, "error": "http_error"}


def _random_token() -> str:
    return base64.b32encode(secrets.token_bytes(16)).rstrip(b"=").decode("ascii")
