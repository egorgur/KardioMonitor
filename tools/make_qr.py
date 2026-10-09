#!/usr/bin/env python3
"""QR-код для приложения KardioMonitor: MAC-адрес и ключ браслета.

  python tools/make_qr.py --mac E7:19:F3:6C:04:9E --key 0x<32 hex-символа> [--out band.svg]

Без --out печатает QR в терминал. Ключ можно передать через MIBAND_AUTH_KEY.
QR даёт полный доступ к браслету — выдавайте его только пациенту.
"""

import argparse
import os
import re
import sys

import qrcode
import qrcode.image.svg


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mac", required=True)
    parser.add_argument("--key", default=os.environ.get("MIBAND_AUTH_KEY"))
    parser.add_argument("--out", help="сохранить в SVG")
    args = parser.parse_args()

    mac = args.mac.upper()
    key = (args.key or "").lower().removeprefix("0x")
    if not re.fullmatch(r"([0-9A-F]{2}:){5}[0-9A-F]{2}", mac):
        parser.error("неверный MAC-адрес")
    if not re.fullmatch(r"[0-9a-f]{32}", key):
        parser.error("ключ должен состоять из 32 hex-символов")

    qr = qrcode.QRCode(border=2)
    qr.add_data(f"kardio;{mac};{key}")
    if args.out:
        qr.make_image(image_factory=qrcode.image.svg.SvgPathImage).save(args.out)
        print(f"Сохранено: {args.out}")
    else:
        qr.print_ascii(invert=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
