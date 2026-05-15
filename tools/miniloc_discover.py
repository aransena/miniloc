#!/usr/bin/env python3
"""Discover miniloc phones on a subnet and optionally poll the selected device."""

from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
from dataclasses import dataclass
from typing import Any

import httpx


DEFAULT_PORT = 47654


@dataclass
class Candidate:
    ip: str
    body: dict[str, Any]


async def probe(client: httpx.AsyncClient, ip: str, port: int, timeout_s: float) -> Candidate | None:
    url = f"http://{ip}:{port}/id_ping"
    try:
        response = await client.get(url, timeout=timeout_s)
        response.raise_for_status()
        body = response.json()
    except Exception:
        return None
    if body.get("app") != "miniloc" or body.get("type") != "id_pong":
        return None
    return Candidate(ip=ip, body=body)


async def discover(args: argparse.Namespace) -> list[Candidate]:
    network = ipaddress.ip_network(args.subnet, strict=False)
    semaphore = asyncio.Semaphore(args.concurrency)
    async with httpx.AsyncClient() as client:
        async def bounded(ip: str) -> Candidate | None:
            async with semaphore:
                return await probe(client, ip, args.port, args.timeout)

        tasks = [bounded(str(ip)) for ip in network.hosts()]
        candidates = [result for result in await asyncio.gather(*tasks) if result is not None]
    return sorted(candidates, key=lambda item: item.ip)


def choose_candidate(candidates: list[Candidate], host_id: str | None) -> Candidate | None:
    if not candidates:
        return None
    if host_id:
        for candidate in candidates:
            if candidate.body.get("paired_host_id") == host_id:
                return candidate
    return candidates[0]


async def confirm_candidate(client: httpx.AsyncClient, candidate: Candidate, args: argparse.Namespace) -> dict[str, Any] | None:
    if not args.host_id or not args.token:
        return None
    if args.instance_id and candidate.body.get("instance_id") != args.instance_id:
        return None
    if candidate.body.get("paired_host_id") not in (args.host_id, None):
        return None

    headers = {
        "X-Miniloc-Host-Id": args.host_id,
        "X-Miniloc-Token": args.token,
    }
    try:
        response = await client.get(
            f"http://{candidate.ip}:{candidate.body.get('port') or args.port}/status",
            headers=headers,
            timeout=args.poll_timeout,
        )
    except httpx.RequestError:
        return None
    if response.status_code == 401:
        raise SystemExit("Authentication failed; check --host-id and --token.")
    if response.status_code != 200:
        return None
    body = response.json()
    if body.get("app") != "miniloc" or body.get("ok") is not True:
        return None
    if body.get("paired_host_id") != args.host_id:
        return None
    if args.instance_id and body.get("instance_id") != args.instance_id:
        return None
    return body


async def rediscover_authenticated(args: argparse.Namespace) -> tuple[Candidate, dict[str, Any]]:
    candidates = await discover(args)
    if args.instance_id:
        candidates.sort(key=lambda candidate: candidate.body.get("instance_id") != args.instance_id)
    else:
        candidates.sort(key=lambda candidate: candidate.body.get("paired_host_id") != args.host_id)

    async with httpx.AsyncClient() as client:
        for candidate in candidates:
            status = await confirm_candidate(client, candidate, args)
            if status is not None:
                return candidate, status
    raise SystemExit(f"No authenticated miniloc device found on {args.subnet}.")


async def poll(candidate: Candidate, args: argparse.Namespace) -> None:
    headers = {
        "X-Miniloc-Host-Id": args.host_id,
        "X-Miniloc-Token": args.token,
    }
    base_url = f"http://{candidate.ip}:{candidate.body.get('port') or args.port}"
    async with httpx.AsyncClient(headers=headers, timeout=args.poll_timeout) as client:
        status = await client.get(f"{base_url}/status")
        print(json.dumps({"endpoint": "/status", "status_code": status.status_code, "body": status.json()}, indent=2))
        while True:
            try:
                response = await client.get(f"{base_url}/loc_ping")
            except httpx.RequestError:
                print(f"Connection to {candidate.ip} failed; rediscovering on {args.subnet}...")
                candidate, status_body = await rediscover_authenticated(args)
                base_url = f"http://{candidate.ip}:{status_body.get('port') or candidate.body.get('port') or args.port}"
                print(json.dumps({"endpoint": "/status", "status_code": 200, "ip": candidate.ip, "body": status_body}, indent=2))
                continue
            try:
                body = response.json()
            except Exception:
                body = {"raw": response.text}
            if response.status_code == 401:
                raise SystemExit("Authentication failed; check --host-id and --token.")
            print(json.dumps({"endpoint": "/loc_ping", "status_code": response.status_code, "body": body}, indent=2))
            if args.once:
                return
            await asyncio.sleep(args.interval)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--subnet", required=True, help="IPv4 subnet to sweep, e.g. 192.168.4.0/24.")
    parser.add_argument("--host-id", help="Host ID used for authenticated polling.")
    parser.add_argument("--token", help="Pairing token used for authenticated polling.")
    parser.add_argument("--instance-id", help="Expected miniloc instance_id to prefer/require during authenticated polling.")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="miniloc HTTP port.")
    parser.add_argument("--timeout", type=float, default=0.25, help="Per-host /id_ping timeout in seconds.")
    parser.add_argument("--concurrency", type=int, default=128, help="Maximum concurrent probes.")
    parser.add_argument("--interval", type=float, default=1.0, help="Polling interval in seconds.")
    parser.add_argument("--poll-timeout", type=float, default=2.0, help="Authenticated endpoint timeout in seconds.")
    parser.add_argument("--no-poll", action="store_true", help="Only print discovered devices.")
    parser.add_argument("--once", action="store_true", help="Poll /loc_ping once instead of continuously.")
    return parser.parse_args()


async def async_main() -> int:
    args = parse_args()
    if args.concurrency <= 0:
        raise SystemExit("--concurrency must be greater than zero.")
    if args.timeout <= 0:
        raise SystemExit("--timeout must be greater than zero.")

    candidates = await discover(args)
    if not candidates:
        print("No miniloc devices found.")
        return 1

    print("Discovered miniloc candidates:")
    for candidate in candidates:
        print(json.dumps({"ip": candidate.ip, **candidate.body}, indent=2))

    if args.no_poll:
        return 0
    if not args.host_id or not args.token:
        print("Skipping polling because --host-id and --token were not both provided.")
        return 0

    selected, status = await rediscover_authenticated(args)
    if not args.instance_id:
        args.instance_id = status.get("instance_id")
    print(f"Polling {selected.ip}:")
    await poll(selected, args)
    return 0


def main() -> int:
    try:
        return asyncio.run(async_main())
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
