"""WIFIcam PC client. Python standard library only.

Examples:
    python pc_client.py 'http://192.168.1.157:8765/?token=XYZ' cameras
    python pc_client.py 'http://192.168.1.157:8765/?token=XYZ' photo --output frame.jpg
    python pc_client.py 'http://192.168.1.157:8765/?token=XYZ' set --iso 400 --shutter-us 10000 --manual
    python pc_client.py 'http://192.168.1.157:8765/?token=XYZ' burst --count 8 --interval 0.5
"""
import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description="Control an Android Camera2 phone over local Wi-Fi")
    p.add_argument("url", help="Complete URL displayed by WIFIcam on the phone")
    commands = p.add_subparsers(dest="action", required=True)
    commands.add_parser("cameras")
    commands.add_parser("state")
    snap = commands.add_parser("photo", help="Take a high-resolution JPEG")
    snap.add_argument("--output", default="photo.jpg")
    preview = commands.add_parser("preview", help="Save current video frame")
    preview.add_argument("--output", default="preview.jpg")
    change = commands.add_parser("set", help="Apply manual sensor controls")
    change.add_argument("--iso", type=int)
    change.add_argument("--shutter-us", type=int)
    change.add_argument("--focus", type=float)
    change.add_argument("--zoom", type=float)
    change.add_argument("--awb", type=int, help="Camera2 white balance mode integer")
    change.add_argument("--manual", action="store_true", help="Enable manual shutter + ISO")
    change.add_argument("--manual-focus", action="store_true")
    choice = commands.add_parser("camera", help="Switch to advertised camera ID")
    choice.add_argument("id", help="ID returned by cameras, e.g. 0 or 0@2")
    choice.add_argument("--width", type=int, default=1280, choices=[640, 1280, 1920])
    burst = commands.add_parser("burst", help="Save consecutive full-resolution captures")
    burst.add_argument("--count", type=int, default=10)
    burst.add_argument("--interval", type=float, default=0.5)
    burst.add_argument("--dir", default="captures")
    args = p.parse_args()
    parsed = urllib.parse.urlsplit(args.url)
    if parsed.scheme != "http" or not parsed.hostname:
        p.error("Provide the local http:// URL shown on your phone")
    token = urllib.parse.parse_qs(parsed.query).get("token", [""])[0]
    if not token:
        p.error("WIFIcam URL must contain ?token=...")
    base = f"{parsed.scheme}://{parsed.netloc}"

    def request(path, params=None, post=False):
        query = urllib.parse.urlencode({"token": token, **(params or {})})
        req = urllib.request.Request(base + path + "?" + query, method="POST" if post else "GET")
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.read()

    def save(path, destination):
        jpeg = request(path)
        dest = Path(destination)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(jpeg)
        print(f"{dest}: {len(jpeg):,} bytes")

    if args.action in ("cameras", "state"):
        print(json.dumps(json.loads(request("/api/" + args.action)), indent=2))
    elif args.action == "photo":
        save("/photo", args.output)
    elif args.action == "preview":
        save("/snapshot", args.output)
    elif args.action == "camera":
        print(request("/api/camera", {"id": args.id, "width": args.width}, post=True).decode())
    elif args.action == "set":
        options = {}
        for name, key in (("iso", "iso"), ("shutter_us", "shutterUs"), ("focus", "focus"), ("zoom", "zoom"), ("awb", "awbMode")):
            value = getattr(args, name)
            if value is not None:
                options[key] = value
        if args.manual: options["manualExposure"] = "true"
        if args.manual_focus: options["manualFocus"] = "true"
        print(request("/api/set", options, post=True).decode())
    elif args.action == "burst":
        if args.count < 1 or args.count > 1000 or args.interval < 0:
            p.error("Count must be 1..1000, interval >= 0")
        root = Path(args.dir)
        root.mkdir(parents=True, exist_ok=True)
        for n in range(args.count):
            name = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
            save("/photo", root / f"{n:04d}_{name}.jpg")
            if n < args.count - 1: time.sleep(args.interval)


if __name__ == "__main__":
    main()
