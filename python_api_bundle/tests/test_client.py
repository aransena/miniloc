from __future__ import annotations

import json
import unittest
from io import BytesIO
from unittest.mock import patch
import urllib.error

from miniloc_api import (
    DEFAULT_PORT,
    MinilocAuth,
    MinilocClient,
    MinilocDevice,
    MinilocError,
    MinilocResolver,
    MinilocUnauthorized,
    build_pairing_payload,
    discover_miniloc,
    pairing_payload_json,
)
from miniloc_api import client as client_module


class FakeResponse:
    def __init__(self, body: dict):
        self.body = body

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self.body).encode("utf-8")


class ClientContractTests(unittest.TestCase):
    def test_build_pairing_payload_is_compact_qr_json(self) -> None:
        payload = build_pairing_payload(
            host_id="host-7K3P",
            host_name="Field Recorder 7K3P",
            token="fixed-token",
            expires_in_s=600,
        )

        self.assertEqual(payload["type"], "miniloc_pair")
        self.assertEqual(payload["version"], 1)
        self.assertEqual(payload["pairing_token"], "fixed-token")
        self.assertEqual(payload["miniloc_port"], DEFAULT_PORT)

        encoded = pairing_payload_json(payload)
        self.assertNotIn(", ", encoded)
        self.assertNotIn(": ", encoded)
        self.assertEqual(json.loads(encoded), payload)

    def test_build_pairing_payload_validates_lifetime_and_port(self) -> None:
        with self.assertRaises(ValueError):
            build_pairing_payload("host", "Host", expires_in_s=0)
        with self.assertRaises(ValueError):
            build_pairing_payload("host", "Host", port=0)

    def test_authenticated_endpoint_requires_auth_object(self) -> None:
        client = MinilocClient("192.168.4.23")

        with self.assertRaises(MinilocError):
            client.status()

    def test_authenticated_request_sends_host_headers(self) -> None:
        seen: dict[str, object] = {}

        def fake_urlopen(request, timeout):
            seen["url"] = request.full_url
            seen["method"] = request.get_method()
            seen["timeout"] = timeout
            seen["headers"] = {
                key.lower(): value
                for key, value in request.header_items()
            }
            return FakeResponse({"type": "status", "ok": True})

        client = MinilocClient(
            "192.168.4.23",
            auth=MinilocAuth("host-7K3P", "secret-token"),
            timeout_s=1.5,
        )
        with patch.object(client_module.urllib.request, "urlopen", fake_urlopen):
            body = client.status()

        self.assertEqual(body["type"], "status")
        self.assertEqual(seen["url"], "http://192.168.4.23:47654/status")
        self.assertEqual(seen["method"], "GET")
        self.assertEqual(seen["timeout"], 1.5)
        self.assertEqual(seen["headers"]["x-miniloc-host-id"], "host-7K3P")
        self.assertEqual(seen["headers"]["x-miniloc-token"], "secret-token")

    def test_unauthorized_status_maps_to_pairing_error(self) -> None:
        def fake_urlopen(request, timeout):
            body = BytesIO(json.dumps({"ok": False, "error": "unauthorized"}).encode("utf-8"))
            raise urllib.error.HTTPError(
                request.full_url,
                401,
                "Unauthorized",
                hdrs=None,
                fp=body,
            )

        client = MinilocClient("192.168.4.23", auth=MinilocAuth("host", "wrong"))
        with patch.object(client_module.urllib.request, "urlopen", fake_urlopen):
            with self.assertRaises(MinilocUnauthorized):
                client.status()

    def test_discovery_filters_non_miniloc_responses(self) -> None:
        def fake_probe_ip(ip: str, port: int, timeout_s: float):
            if ip == "192.168.4.2":
                return MinilocDevice(
                    ip=ip,
                    port=port,
                    instance_id="miniloc-AAAA",
                    paired_host_id="host-A",
                    raw={"app": "miniloc"},
                )
            return None

        with patch.object(client_module, "_probe_ip", fake_probe_ip):
            devices = discover_miniloc(
                "192.168.4.0/30",
                preferred_host_id="host-A",
                max_workers=2,
            )

        self.assertEqual([device.ip for device in devices], ["192.168.4.2"])

    def test_resolver_rediscovers_when_cached_ip_is_stale(self) -> None:
        auth = MinilocAuth("host-A", "secret")
        discovered = MinilocDevice(
            ip="192.168.4.9",
            port=DEFAULT_PORT,
            instance_id="miniloc-ABCD",
            paired_host_id="host-A",
            raw={"app": "miniloc"},
        )

        def fake_status(self):
            if self.ip == "192.168.4.8":
                raise MinilocError("cached IP is stale")
            return {
                "app": "miniloc",
                "ok": True,
                "paired_host_id": "host-A",
                "instance_id": "miniloc-ABCD",
                "port": DEFAULT_PORT,
            }

        with (
            patch.object(client_module, "discover_miniloc", return_value=[discovered]) as discover,
            patch.object(client_module.MinilocClient, "status", fake_status),
        ):
            resolver = MinilocResolver(
                subnet="192.168.4.0/24",
                auth=auth,
                cached_ip="192.168.4.8",
                instance_id="miniloc-ABCD",
            )
            resolution = resolver.resolve()

        self.assertEqual(resolution.ip, "192.168.4.9")
        self.assertEqual(resolver.cached_ip, "192.168.4.9")
        discover.assert_called_once()


if __name__ == "__main__":
    unittest.main()
