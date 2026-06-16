from __future__ import annotations

import json
import unittest
from io import BytesIO
from pathlib import Path
from typing import Any
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


REPO_ROOT = Path(__file__).resolve().parents[2]


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise AssertionError(f"{path} did not contain a JSON object")
    return value


def validate_json_schema(instance: Any, schema: dict[str, Any], schema_path: Path, path: str = "$") -> None:
    if "$ref" in schema:
        ref_path = (schema_path.parent / schema["$ref"]).resolve()
        validate_json_schema(instance, load_json(ref_path), ref_path, path)
        return

    for subschema in schema.get("allOf", []):
        validate_json_schema(instance, subschema, schema_path, path)

    if "if" in schema and matches_json_schema(instance, schema["if"], schema_path):
        validate_json_schema(instance, schema.get("then", {}), schema_path, path)

    if "const" in schema and instance != schema["const"]:
        raise AssertionError(f"{path}: expected const {schema['const']!r}, got {instance!r}")

    if "enum" in schema and instance not in schema["enum"]:
        raise AssertionError(f"{path}: expected one of {schema['enum']!r}, got {instance!r}")

    expected_type = schema.get("type")
    if expected_type is not None and not json_type_matches(instance, expected_type):
        raise AssertionError(f"{path}: expected type {expected_type!r}, got {type(instance).__name__}")

    if isinstance(instance, dict):
        for key in schema.get("required", []):
            if key not in instance:
                raise AssertionError(f"{path}: missing required key {key!r}")
        properties = schema.get("properties", {})
        for key, subschema in properties.items():
            if key in instance:
                validate_json_schema(instance[key], subschema, schema_path, f"{path}.{key}")
        if schema.get("additionalProperties") is False:
            extra = set(instance) - set(properties)
            if extra:
                raise AssertionError(f"{path}: unexpected keys {sorted(extra)!r}")

    if isinstance(instance, list):
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            raise AssertionError(f"{path}: expected at most {schema['maxItems']} items, got {len(instance)}")
        item_schema = schema.get("items")
        if item_schema is not None:
            for index, item in enumerate(instance):
                validate_json_schema(item, item_schema, schema_path, f"{path}[{index}]")

    if isinstance(instance, str) and "minLength" in schema and len(instance) < schema["minLength"]:
        raise AssertionError(f"{path}: expected minLength {schema['minLength']}, got {len(instance)}")

    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            raise AssertionError(f"{path}: expected >= {schema['minimum']}, got {instance}")
        if "maximum" in schema and instance > schema["maximum"]:
            raise AssertionError(f"{path}: expected <= {schema['maximum']}, got {instance}")


def matches_json_schema(instance: Any, schema: dict[str, Any], schema_path: Path) -> bool:
    try:
        validate_json_schema(instance, schema, schema_path)
    except AssertionError:
        return False
    return True


def json_type_matches(instance: Any, expected_type: str | list[str]) -> bool:
    if isinstance(expected_type, list):
        return any(json_type_matches(instance, item) for item in expected_type)
    if expected_type == "null":
        return instance is None
    if expected_type == "boolean":
        return isinstance(instance, bool)
    if expected_type == "integer":
        return isinstance(instance, int) and not isinstance(instance, bool)
    if expected_type == "number":
        return isinstance(instance, (int, float)) and not isinstance(instance, bool)
    if expected_type == "string":
        return isinstance(instance, str)
    if expected_type == "object":
        return isinstance(instance, dict)
    if expected_type == "array":
        return isinstance(instance, list)
    raise AssertionError(f"Unsupported schema type {expected_type!r}")


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
        schema_path = REPO_ROOT / "schemas/pairing-payload.v1.schema.json"
        validate_json_schema(payload, load_json(schema_path), schema_path)

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

    def test_api_fixtures_validate_against_json_schemas(self) -> None:
        cases = [
            ("fixtures/api/pairing-payload.valid.json", "schemas/pairing-payload.v1.schema.json"),
            ("fixtures/api/id-ping.paired.json", "schemas/id-ping-response.v1.schema.json"),
            ("fixtures/api/status.active.json", "schemas/status-response.v1.schema.json"),
            ("fixtures/api/loc-ping.ok.json", "schemas/loc-ping-response.v1.schema.json"),
            ("fixtures/api/loc-ping.no-fix.json", "schemas/loc-ping-response.v1.schema.json"),
            ("fixtures/api/loc-history.ok.json", "schemas/loc-history-response.v1.schema.json"),
            ("fixtures/api/forget-pairing.ok.json", "schemas/forget-pairing-response.v1.schema.json"),
            ("fixtures/api/error.unauthorized.json", "schemas/error-response.v1.schema.json"),
        ]

        for fixture_rel, schema_rel in cases:
            with self.subTest(fixture=fixture_rel):
                fixture_path = REPO_ROOT / fixture_rel
                schema_path = REPO_ROOT / schema_rel
                validate_json_schema(load_json(fixture_path), load_json(schema_path), schema_path)

    def test_api_descriptor_references_schemas_and_examples_for_every_endpoint(self) -> None:
        descriptor = load_json(REPO_ROOT / "contracts/miniloc.api-surface.json")
        for endpoint in descriptor["endpoints"]:
            with self.subTest(endpoint=endpoint["path"]):
                schema_path = (REPO_ROOT / "contracts" / endpoint["response_schema"]).resolve()
                self.assertTrue(schema_path.exists(), schema_path)
                error_schema_path = (REPO_ROOT / "contracts" / endpoint["error_schema"]).resolve()
                self.assertTrue(error_schema_path.exists(), error_schema_path)
                for example in endpoint["examples"]:
                    example_path = (REPO_ROOT / "contracts" / example).resolve()
                    self.assertTrue(example_path.exists(), example_path)

        for schema_ref in descriptor["schemas"].values():
            schema_path = (REPO_ROOT / "contracts" / schema_ref).resolve()
            self.assertTrue(schema_path.exists(), schema_path)
        self.assertTrue((REPO_ROOT / "openapi/miniloc.openapi.json").exists())

    def test_openapi_paths_match_universal_contract_api_surface(self) -> None:
        descriptor = load_json(REPO_ROOT / "contracts/miniloc.api-surface.json")
        openapi = load_json(REPO_ROOT / "openapi/miniloc.openapi.json")

        expected = {
            (endpoint["path"], endpoint["method"].lower(), endpoint["response_schema"])
            for endpoint in descriptor["endpoints"]
        }
        actual = set()
        for path, operations in openapi["paths"].items():
            for method, operation in operations.items():
                response_schema = operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"]
                actual.add((path, method, response_schema))

        self.assertEqual(actual, expected)
        self.assertEqual(
            openapi["x-miniloc-pairing"]["schema"]["$ref"],
            descriptor["schemas"]["pairing_payload"],
        )


if __name__ == "__main__":
    unittest.main()
