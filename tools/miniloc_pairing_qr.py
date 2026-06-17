#!/usr/bin/env python3
"""Generate a miniloc host pairing message and QR image."""

from __future__ import annotations

import argparse
import secrets
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "python_api_bundle"))

from miniloc_api import build_pairing_payload, pairing_payload_message


def random_code(length: int = 4) -> str:
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(length))


def build_payload(args: argparse.Namespace) -> dict[str, object]:
    code = random_code()
    host_id = args.host_id or code
    return build_pairing_payload(
        host_id=host_id,
        token=args.token,
    )


def write_qr(
    payload_json: str,
    output_path: Path,
    error_correction_level: str,
    box_size: int,
    border: int,
) -> tuple[int, tuple[int, int]]:
    try:
        import qrcode
        from qrcode.constants import ERROR_CORRECT_H, ERROR_CORRECT_L, ERROR_CORRECT_M, ERROR_CORRECT_Q
    except ImportError as exc:
        raise SystemExit(
            "The qrcode package is required to write a QR image. "
            "Install it with: python3 -m pip install --user 'qrcode[pil]'"
        ) from exc

    error_correction = {
        "L": ERROR_CORRECT_L,
        "M": ERROR_CORRECT_M,
        "Q": ERROR_CORRECT_Q,
        "H": ERROR_CORRECT_H,
    }[error_correction_level]
    qr = qrcode.QRCode(
        version=None,
        error_correction=error_correction,
        box_size=box_size,
        border=border,
    )
    qr.add_data(payload_json)
    qr.make(fit=True)
    image = qr.make_image(fill_color="black", back_color="white")
    image.save(output_path)
    return qr.modules_count, image.size


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-id", help="Host identifier to place in the pairing QR, e.g. 7K3P.")
    parser.add_argument("--token", help="Existing uppercase base32 token. Defaults to a new 128-bit random token.")
    parser.add_argument("--output", default="miniloc_pairing_qr.png", help="QR image output path.")
    parser.add_argument(
        "--error-correction",
        choices=("L", "M", "Q", "H"),
        default="L",
        help="QR error correction level. L gives the lowest module density.",
    )
    parser.add_argument("--box-size", type=int, default=20, help="Pixels per QR module.")
    parser.add_argument("--border", type=int, default=4, help="Quiet-zone modules around the QR code.")
    parser.add_argument("--no-qr", action="store_true", help="Only print the QR message.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.box_size <= 0:
        raise SystemExit("--box-size must be greater than zero.")
    if args.border < 4:
        raise SystemExit("--border must be at least 4 modules.")

    payload = build_payload(args)
    payload_message = pairing_payload_message(payload)

    if not args.no_qr:
        output_path = Path(args.output)
        modules, image_size = write_qr(
            payload_message,
            output_path,
            args.error_correction,
            args.box_size,
            args.border,
        )
        print(f"QR written to {output_path}")
        print(
            f"QR format: ML1, error correction: {args.error_correction}, "
            f"message bytes: {len(payload_message.encode('utf-8'))}, "
            f"modules: {modules}x{modules}, image: {image_size[0]}x{image_size[1]} px"
        )
        print(f"Host ID: {payload['host_id']}")
        print(f"Token: {payload['pairing_token']}")
    print(payload_message)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
