#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json

from miniloc_api import MinilocClient, discover_miniloc_auto


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover Open LAN miniloc devices and poll one without credentials.")
    parser.add_argument("--subnet", help="Optional IPv4 subnet fallback, e.g. 192.168.4.0/24")
    parser.add_argument("--instance-id", help="Optional expected miniloc instance_id.")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--interval", type=float, default=1.0)
    args = parser.parse_args()

    devices = discover_miniloc_auto(subnet=args.subnet)
    open_devices = [device for device in devices if device.auth_required is False]
    if args.instance_id:
        open_devices = [device for device in open_devices if device.instance_id == args.instance_id]
    if not open_devices:
        raise SystemExit("No Open LAN miniloc device found.")

    device = open_devices[0]
    client = MinilocClient(device.ip, port=device.port)
    print(json.dumps({"ip": device.ip, "port": device.port, "id_ping": device.raw}, indent=2))
    print(json.dumps(client.status(), indent=2))
    for response in client.poll_locations(interval_s=args.interval, limit=1 if args.once else None):
        print(json.dumps(response, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
