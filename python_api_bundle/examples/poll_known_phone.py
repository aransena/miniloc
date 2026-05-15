#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json

from miniloc_api import MinilocAuth, MinilocClient


def main() -> int:
    parser = argparse.ArgumentParser(description="Poll an already known miniloc phone IP.")
    parser.add_argument("ip")
    parser.add_argument("--host-id", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--port", type=int, default=47654)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--interval", type=float, default=1.0)
    args = parser.parse_args()

    client = MinilocClient(args.ip, MinilocAuth(args.host_id, args.token), port=args.port)
    print(json.dumps(client.status(), indent=2))
    for response in client.poll_locations(interval_s=args.interval, limit=1 if args.once else None):
        print(json.dumps(response, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
