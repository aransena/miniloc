#!/usr/bin/env python3
"""Poll a miniloc phone, rediscovering it on the same subnet when its IP changes."""

from __future__ import annotations

import argparse
import ipaddress
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import time

import httpx


DEFAULT_PORT = 47654
DEFAULT_CACHE_PATH = Path.home() / ".cache" / "miniloc" / "last_device.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ip", nargs="?", help="Phone IP address. If omitted, use --cache and/or --subnet.")
    parser.add_argument("--host-id", help="Paired host ID. Omit for Open LAN devices.")
    parser.add_argument("--token", help="Paired host token. Omit for Open LAN devices.")
    parser.add_argument("--subnet", help="Same-subnet IPv4 range for rediscovery, e.g. 192.168.4.0/24.")
    parser.add_argument("--instance-id", help="Expected miniloc instance_id. Defaults to cached value when available.")
    parser.add_argument("--cache", default=str(DEFAULT_CACHE_PATH), help="JSON cache for last_seen_ip and instance_id.")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="miniloc HTTP port.")
    parser.add_argument("--timeout", type=float, default=0.25, help="Per-host /id_ping timeout during rediscovery.")
    parser.add_argument("--concurrency", type=int, default=128, help="Maximum concurrent rediscovery probes.")
    parser.add_argument("--poll-timeout", type=float, default=2.0, help="Authenticated endpoint timeout in seconds.")
    parser.add_argument("--interval", type=float, default=1.0, help="Polling interval in seconds.")
    parser.add_argument("--once", action="store_true", help="Poll once and exit.")
    parser.add_argument("--compact", action="store_true", help="Print one-line timing diagnostics instead of full JSON.")
    return parser.parse_args()


def print_compact(body: dict, status_code: int, previous: dict | None) -> None:
    now_ms = int(time.time() * 1000)
    sequence = body.get("fix_sequence")
    elapsed_ns = body.get("elapsed_realtime_ns")
    age_ms = body.get("age_ms")
    fix_delta_ms = None
    same_fix = None

    if previous and elapsed_ns is not None and previous.get("elapsed_realtime_ns") is not None:
        fix_delta_ms = (int(elapsed_ns) - int(previous["elapsed_realtime_ns"])) / 1_000_000.0
    if previous and sequence is not None and previous.get("fix_sequence") is not None:
        same_fix = sequence == previous["fix_sequence"]

    print(
        " ".join(
            [
                f"status={status_code}",
                f"ok={body.get('ok')}",
                f"seq={sequence}",
                f"same_fix={same_fix}",
                f"age_ms={age_ms}",
                f"delivery_latency_ms={body.get('delivery_latency_ms')}",
                f"fix_delta_ms={fix_delta_ms}",
                f"source_batch_size={body.get('source_batch_size')}",
                f"recent_count={len(body.get('recent_fixes') or [])}",
                f"phone_wall_time_ms={body.get('phone_wall_time_ms')}",
                f"cache_received_wall_time_ms={body.get('cache_received_wall_time_ms')}",
                f"host_received_wall_time_ms={now_ms}",
            ]
        )
    )


def load_cache(path: str) -> dict:
    cache_path = Path(path).expanduser()
    if not cache_path.exists():
        return {}
    try:
        data = json.loads(cache_path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


def save_cache(path: str, ip: str, status: dict, port: int, host_id: str) -> None:
    cache_path = Path(path).expanduser()
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(
        json.dumps(
            {
                "last_seen_ip": ip,
                "instance_id": status.get("instance_id"),
                "host_id": host_id,
                "port": int(status.get("port") or port),
                "updated_wall_time_ms": int(time.time() * 1000),
            },
            indent=2,
        ),
        encoding="utf-8",
    )


def auth_headers(args: argparse.Namespace) -> dict[str, str]:
    if args.host_id and args.token:
        return {
            "X-Miniloc-Host-Id": args.host_id,
            "X-Miniloc-Token": args.token,
        }
    return {}


def status_for_ip(ip: str, args: argparse.Namespace, port: int | None = None) -> dict | None:
    base_url = f"http://{ip}:{port or args.port}"
    try:
        response = httpx.get(f"{base_url}/status", headers=auth_headers(args), timeout=args.poll_timeout)
    except httpx.RequestError:
        return None
    if response.status_code == 401:
        if not args.host_id:
            return None
        raise SystemExit(f"Authentication failed for {ip}; check --host-id and --token.")
    if response.status_code != 200:
        return None
    try:
        body = response.json()
    except Exception:
        return None
    if body.get("app") != "miniloc" or body.get("ok") is not True:
        return None
    if args.host_id and body.get("paired_host_id") != args.host_id:
        return None
    if not args.host_id and body.get("auth_required") is not False:
        return None
    if args.instance_id and body.get("instance_id") != args.instance_id:
        return None
    return body


def probe_id(ip: str, args: argparse.Namespace) -> tuple[str, dict] | None:
    try:
        response = httpx.get(f"http://{ip}:{args.port}/id_ping", timeout=args.timeout)
        response.raise_for_status()
        body = response.json()
    except Exception:
        return None
    if body.get("type") != "id_pong" or body.get("app") != "miniloc":
        return None
    return ip, body


def discover_candidates(args: argparse.Namespace) -> list[tuple[str, dict]]:
    if not args.subnet:
        return []
    network = ipaddress.ip_network(args.subnet, strict=False)
    workers = max(1, args.concurrency)
    candidates: list[tuple[str, dict]] = []
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(probe_id, str(ip), args) for ip in network.hosts()]
        for future in as_completed(futures):
            result = future.result()
            if result is not None:
                candidates.append(result)
    candidates.sort(
        key=lambda item: (
            item[1].get("instance_id") != args.instance_id if args.instance_id else False,
            item[1].get("paired_host_id") != args.host_id if args.host_id else item[1].get("auth_required") is not False,
            item[0],
        )
    )
    return candidates


def resolve_phone(args: argparse.Namespace, ip_hint: str | None) -> tuple[str, dict]:
    if ip_hint:
        status = status_for_ip(ip_hint, args)
        if status is not None:
            save_cache(args.cache, ip_hint, status, args.port, args.host_id or "open_lan")
            return ip_hint, status

    if not args.subnet:
        raise SystemExit("No reachable cached IP. Pass --subnet for same-subnet rediscovery.")

    for ip, id_body in discover_candidates(args):
        if args.instance_id and id_body.get("instance_id") != args.instance_id:
            continue
        if args.host_id and id_body.get("paired_host_id") not in (args.host_id, None):
            continue
        if not args.host_id and id_body.get("auth_required") is not False:
            continue
        status = status_for_ip(ip, args, port=int(id_body.get("port") or args.port))
        if status is not None:
            save_cache(args.cache, ip, status, int(status.get("port") or args.port), args.host_id or "open_lan")
            return ip, status

    raise SystemExit(f"No readable miniloc device found on {args.subnet}.")


def main() -> int:
    args = parse_args()
    if args.concurrency <= 0:
        raise SystemExit("--concurrency must be greater than zero.")
    if args.timeout <= 0 or args.poll_timeout <= 0:
        raise SystemExit("--timeout and --poll-timeout must be greater than zero.")
    if bool(args.host_id) != bool(args.token):
        raise SystemExit("--host-id and --token must be provided together.")

    cache = load_cache(args.cache)
    cache_key = args.host_id or "open_lan"
    if cache.get("host_id") != cache_key:
        cache = {}
    if not args.instance_id:
        args.instance_id = cache.get("instance_id")

    ip_hint = args.ip or cache.get("last_seen_ip")
    current_ip, status_body = resolve_phone(args, ip_hint)
    current_port = int(status_body.get("port") or args.port)
    base_url = f"http://{current_ip}:{current_port}"
    with httpx.Client(headers=auth_headers(args), timeout=args.poll_timeout) as client:
        if args.compact:
            print(
                " ".join(
                    [
                        "endpoint=/status",
                        "status=200",
                        f"ip={current_ip}",
                        f"instance_id={status_body.get('instance_id')}",
                        f"service_active={status_body.get('foreground_service_active')}",
                        f"last_fix_sequence={status_body.get('last_fix_sequence')}",
                        f"last_fix_age_ms={status_body.get('last_fix_age_ms')}",
                    ]
                )
            )
        else:
            print(json.dumps({"endpoint": "/status", "ip": current_ip, "status_code": 200, "body": status_body}, indent=2))
        previous: dict | None = None
        while True:
            try:
                response = client.get(f"{base_url}/loc_ping")
            except httpx.RequestError:
                print(f"Connection to {current_ip} failed; rediscovering on {args.subnet}...")
                current_ip, status_body = resolve_phone(args, None)
                current_port = int(status_body.get("port") or args.port)
                base_url = f"http://{current_ip}:{current_port}"
                previous = None
                continue
            try:
                body = response.json()
            except Exception:
                body = {"raw": response.text}
            if response.status_code == 401:
                raise SystemExit("Authentication failed; check --host-id and --token.")
            if args.compact:
                print_compact(body, response.status_code, previous)
            else:
                print(json.dumps({"endpoint": "/loc_ping", "status_code": response.status_code, "body": body}, indent=2))
            if body.get("ok"):
                previous = body
            if args.once:
                return 0
            time.sleep(args.interval)


if __name__ == "__main__":
    raise SystemExit(main())
