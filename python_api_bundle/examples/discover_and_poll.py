#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json

from miniloc_api import MinilocAuth, MinilocResolver


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover miniloc phones and keep polling across IP changes.")
    parser.add_argument("--subnet", required=True, help="IPv4 subnet, e.g. 192.168.4.0/24")
    parser.add_argument("--host-id", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--last-seen-ip", help="Optional cached IP hint.")
    parser.add_argument("--instance-id", help="Optional expected miniloc instance_id.")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--interval", type=float, default=1.0)
    args = parser.parse_args()

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


if __name__ == "__main__":
    raise SystemExit(main())
