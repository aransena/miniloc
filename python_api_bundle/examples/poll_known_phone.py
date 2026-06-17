#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json

from miniloc_api import MinilocAuth, MinilocClient


def main() -> int:
    parser = argparse.ArgumentParser(description="Poll an already known miniloc phone IP.")
    parser.add_argument("ip")
    parser.add_argument("--host-id", help="Paired host ID. Omit for Open LAN devices.")
    parser.add_argument("--token", help="Paired token. Omit for Open LAN devices.")
    parser.add_argument("--port", type=int, default=47654)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--interval", type=float, default=1.0)
    args = parser.parse_args()

    if bool(args.host_id) != bool(args.token):
        raise SystemExit("--host-id and --token must be provided together.")
    auth = MinilocAuth(args.host_id, args.token) if args.host_id and args.token else None
    client = MinilocClient(args.ip, auth, port=args.port)
    print(json.dumps(client.status(), indent=2))
    for response in client.poll_locations(interval_s=args.interval, limit=1 if args.once else None):
        print(json.dumps(response, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
