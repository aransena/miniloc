#!/usr/bin/env python3
"""Generate a miniloc host pairing payload and QR image."""

from __future__ import annotations

import argparse
import base64
import json
import secrets
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_PORT = 47654


def random_code(length: int = 4) -> str:
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(length))


def token() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode("ascii")


def build_payload(args: argparse.Namespace) -> dict[str, object]:
    code = random_code()
    host_id = args.host_id or f"host-{code}"
    host_name = args.host_name or f"Host {code}"
    return {
        "type": "miniloc_pair",
        "version": 1,
        "host_id": host_id,
        "host_name": host_name,
        "pairing_token": args.token or token(),
        "issued_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "expires_in_s": args.expires_in_s,
        "miniloc_port": args.port,
    }


def write_qr(payload_json: str, output_path: Path) -> None:
    try:
        import qrcode
    except ImportError as exc:
        raise SystemExit(
            "The qrcode package is required to write a QR image. "
            "Install it with: python3 -m pip install --user 'qrcode[pil]'"
        ) from exc

    image = qrcode.make(payload_json)
    image.save(output_path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-id", help="Host identifier to place in the pairing QR, e.g. host-7K3P.")
    parser.add_argument("--host-name", help="Human-readable host name shown on the phone before sharing.")
    parser.add_argument("--token", help="Existing base64url token. Defaults to a new 256-bit random token.")
    parser.add_argument("--expires-in-s", type=int, default=600, help="Pairing payload lifetime in seconds.")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="miniloc HTTP port.")
    parser.add_argument("--output", default="miniloc_pairing_qr.png", help="QR image output path.")
    parser.add_argument("--no-qr", action="store_true", help="Only print the JSON payload.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.expires_in_s <= 0:
        raise SystemExit("--expires-in-s must be greater than zero.")
    if not 1 <= args.port <= 65535:
        raise SystemExit("--port must be between 1 and 65535.")

    payload = build_payload(args)
    payload_json = json.dumps(payload, separators=(",", ":"))
    print(payload_json)

    if not args.no_qr:
        output_path = Path(args.output)
        write_qr(payload_json, output_path)
        print(f"QR written to {output_path}")
        print(f"Host ID: {payload['host_id']}")
        print(f"Token: {payload['pairing_token']}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
