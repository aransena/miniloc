#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json

from miniloc_api.client import build_pairing_payload, pairing_payload_message


def main() -> int:
    parser = argparse.ArgumentParser(description="Print a miniloc pairing payload for host-side QR rendering.")
    parser.add_argument("--host-id", required=True)
    parser.add_argument("--token")
    parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args()

    payload = build_pairing_payload(
        host_id=args.host_id,
        token=args.token,
    )
    if args.pretty:
        print(json.dumps(payload, indent=2))
    else:
        print(pairing_payload_message(payload))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
