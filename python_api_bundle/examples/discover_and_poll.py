#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json

from miniloc_api import MinilocAuth, MinilocClient, MinilocResolver, discover_miniloc_auto


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover miniloc phones and poll either paired or Open LAN devices.")
    parser.add_argument("--subnet", help="IPv4 subnet fallback, e.g. 192.168.4.0/24")
    parser.add_argument("--host-id", help="Paired host ID. Omit for Open LAN devices.")
    parser.add_argument("--token", help="Paired token. Omit for Open LAN devices.")
    parser.add_argument("--last-seen-ip", help="Optional cached IP hint.")
    parser.add_argument("--instance-id", help="Optional expected miniloc instance_id.")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--interval", type=float, default=1.0)
    args = parser.parse_args()

    if bool(args.host_id) != bool(args.token):
        raise SystemExit("--host-id and --token must be provided together.")

    if args.host_id and args.token:
        if not args.subnet:
            raise SystemExit("--subnet is required for paired rediscovery.")
        resolver = MinilocResolver(
            subnet=args.subnet,
            auth=MinilocAuth(args.host_id, args.token),
            cached_ip=args.last_seen_ip,
            instance_id=args.instance_id,
        )
        resolution = resolver.resolve()
        print(json.dumps({"ip": resolution.ip, "instance_id": resolution.instance_id, "status": resolution.status}, indent=2))
        for response in resolver.poll_locations(interval_s=args.interval, limit=1 if args.once else None):
            print(json.dumps(response, indent=2))
        return 0

    devices = discover_miniloc_auto(subnet=args.subnet)
    open_devices = [device for device in devices if device.auth_required is False]
    if args.instance_id:
        open_devices = [device for device in open_devices if device.instance_id == args.instance_id]
    if not open_devices:
        raise SystemExit("No Open LAN miniloc device found.")

    device = open_devices[0]
    client = MinilocClient(device.ip, port=device.port)
    print(json.dumps({"ip": device.ip, "instance_id": device.instance_id, "status": client.status()}, indent=2))
    for response in client.poll_locations(interval_s=args.interval, limit=1 if args.once else None):
        print(json.dumps(response, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
