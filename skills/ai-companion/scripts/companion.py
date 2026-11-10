#!/usr/bin/env python3
"""
AI Companion CLI Helper
A zero-dependency tool for AI agents to interact with the Android AI Companion HTTP server.
Default server: http://127.0.0.1:18790 (configurable via --url or AI_COMPANION_URL).
"""

import sys
import os
import json
import argparse
import urllib.request
import urllib.error
import urllib.parse

DEFAULT_BASE_URL = os.environ.get("AI_COMPANION_URL", "http://127.0.0.1:18790")

def make_request(base_url, endpoint, method="GET", data=None, raw_response=False):
    url = f"{base_url.rstrip('/')}/{endpoint.lstrip('/')}"
    headers = {}
    body = None

    if data is not None:
        body = json.dumps(data).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            content = resp.read()
            if raw_response:
                return content
            try:
                return json.loads(content.decode("utf-8"))
            except json.JSONDecodeError:
                return {"success": True, "raw": content.decode("utf-8", errors="replace")}
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(err_body)
        except Exception:
            return {"success": False, "error": f"HTTP {e.code}: {e.reason}", "detail": err_body}
    except Exception as e:
        return {"success": False, "error": str(e)}

def parse_bounds(bounds_str):
    """Parses 'left,top,right,bottom' into center x, y and tuple."""
    try:
        parts = [int(p.strip()) for p in bounds_str.split(",")]
        if len(parts) == 4:
            left, top, right, bottom = parts
            center_x = (left + right) // 2
            center_y = (top + bottom) // 2
            return {"center_x": center_x, "center_y": center_y, "box": parts}
    except Exception:
        pass
    return None

def search_nodes(node, query, query_type="any"):
    """Recursively search for nodes matching text or viewId."""
    results = []
    text = node.get("text", "")
    desc = node.get("contentDescription", "")
    view_id = node.get("viewId", "")
    q = query.lower()

    matched = False
    if query_type in ("any", "text") and (q in text.lower() or q in desc.lower()):
        matched = True
    elif query_type in ("any", "id") and q in view_id.lower():
        matched = True

    if matched:
        b_info = parse_bounds(node.get("bounds", ""))
        results.append({
            "text": text,
            "contentDescription": desc,
            "viewId": view_id,
            "className": node.get("className", ""),
            "bounds": node.get("bounds", ""),
            "clickable": node.get("clickable", False),
            "center": (b_info["center_x"], b_info["center_y"]) if b_info else None
        })

    for child in node.get("children", []):
        results.extend(search_nodes(child, query, query_type))
    return results

def main():
    parser = argparse.ArgumentParser(description="AI Companion Device Automation CLI")
    parser.add_argument("--url", default=DEFAULT_BASE_URL, help=f"Server base URL (default: {DEFAULT_BASE_URL})")

    subparsers = parser.add_subparsers(dest="command", required=True)

    # health
    subparsers.add_parser("health", help="Check companion health and service status")

    # device
    subparsers.add_parser("device", help="Get device screen resolution, model, and OS version")

    # ui
    ui_parser = subparsers.add_parser("ui", help="Dump or query the accessibility UI hierarchy")
    ui_parser.add_argument("--find", help="Find node by text, description, or viewId")
    ui_parser.add_argument("--click", action="store_true", help="Click the first found match")
    ui_parser.add_argument("--depth", type=int, default=50, help="Max tree depth (default: 50)")
    ui_parser.add_argument("--no-prune", action="store_true", help="Do not prune empty container nodes")

    # tap
    tap_parser = subparsers.add_parser("tap", help="Tap screen coordinates")
    tap_parser.add_argument("x", type=int, help="X coordinate")
    tap_parser.add_argument("y", type=int, help="Y coordinate")

    # swipe
    swipe_parser = subparsers.add_parser("swipe", help="Perform a swipe gesture")
    swipe_parser.add_argument("x1", type=int, help="Start X")
    swipe_parser.add_argument("y1", type=int, help="Start Y")
    swipe_parser.add_argument("x2", type=int, help="End X")
    swipe_parser.add_argument("y2", type=int, help="End Y")
    swipe_parser.add_argument("--duration", type=int, default=300, help="Duration in ms (default: 300)")

    # scroll
    scroll_parser = subparsers.add_parser("scroll", help="Scroll the screen or a container")
    scroll_parser.add_argument("direction", choices=["up", "down", "left", "right"], help="Scroll direction")
    scroll_parser.add_argument("--id", dest="view_id", help="Target viewId container to scroll")

    # type
    type_parser = subparsers.add_parser("type", help="Type text into focused field or viewId")
    type_parser.add_argument("text", help="Text to input")
    type_parser.add_argument("--id", dest="view_id", help="Optional target viewId")

    # press
    press_parser = subparsers.add_parser("press", help="Press a navigation or hardware key")
    press_parser.add_argument("key", choices=["back", "home", "recents", "power", "notifications", "quick_settings"])

    # launch
    launch_parser = subparsers.add_parser("launch", help="Launch an app package")
    launch_parser.add_argument("package", help="Package name (e.g. com.whatsapp, com.android.settings)")

    # screenshot
    ss_parser = subparsers.add_parser("screenshot", help="Capture a device screenshot")
    ss_parser.add_argument("output", nargs="?", default="screenshot.jpg", help="Output file path (default: screenshot.jpg)")
    ss_parser.add_argument("--format", choices=["jpeg", "png", "webp"], default="jpeg", help="Image format")
    ss_parser.add_argument("--quality", type=int, default=80, help="Quality 1-100 (default: 80)")

    # notifications
    notif_parser = subparsers.add_parser("notifications", help="Read or manage notifications")
    notif_parser.add_argument("--dismiss", help="Notification key to dismiss")
    notif_parser.add_argument("--dismiss-all", action="store_true", help="Dismiss all clearable notifications")

    # clipboard
    clip_parser = subparsers.add_parser("clipboard", help="Get or set device clipboard")
    clip_parser.add_argument("text", nargs="?", help="Text to set. If omitted, gets clipboard")

    # sms
    sms_parser = subparsers.add_parser("sms", help="Read or send SMS")
    sms_parser.add_argument("--limit", type=int, default=25, help="Number of messages to read")
    sms_parser.add_argument("--send-to", help="Phone number to send SMS to")
    sms_parser.add_argument("--message", help="SMS text content")

    # contacts
    contacts_parser = subparsers.add_parser("contacts", help="Search device contacts")
    contacts_parser.add_argument("--query", "-q", default="", help="Contact name query")
    contacts_parser.add_argument("--limit", type=int, default=50, help="Max contacts to return")

    # calls
    calls_parser = subparsers.add_parser("calls", help="Read call logs")
    calls_parser.add_argument("--limit", type=int, default=25, help="Max entries to return")

    # stop
    subparsers.add_parser("stop", help="Remotely stop the companion background service")

    args = parser.parse_args()
    base_url = args.url

    # Execute Command
    if args.command == "health":
        res = make_request(base_url, "/health")
        print(json.dumps(res, indent=2))

    elif args.command == "device":
        res = make_request(base_url, "/device")
        print(json.dumps(res, indent=2))

    elif args.command == "ui":
        prune = "false" if args.no_prune else "true"
        res = make_request(base_url, f"/ui?depth={args.depth}&prune={prune}")
        if not res.get("success"):
            print(json.dumps(res, indent=2))
            sys.exit(1)

        tree = res.get("data", {})
        if args.find:
            matches = search_nodes(tree, args.find)
            if not matches:
                print(json.dumps({"success": False, "found": 0, "message": f"No matches found for '{args.find}'"}, indent=2))
                sys.exit(1)
            output = {"success": True, "found": len(matches), "matches": matches}
            print(json.dumps(output, indent=2))

            if args.click:
                target = matches[0]
                if target.get("center"):
                    cx, cy = target["center"]
                    click_res = make_request(base_url, "/tap", method="POST", data={"x": cx, "y": cy})
                    print(f"Clicked center ({cx}, {cy}): {click_res.get('success')}")
                elif target.get("viewId"):
                    click_res = make_request(base_url, "/ui/click", method="POST", data={"viewId": target["viewId"]})
                    print(f"Clicked viewId {target['viewId']}: {click_res.get('success')}")
        else:
            print(json.dumps(tree, indent=2))

    elif args.command == "tap":
        res = make_request(base_url, "/tap", method="POST", data={"x": args.x, "y": args.y})
        print(json.dumps(res, indent=2))

    elif args.command == "swipe":
        res = make_request(base_url, "/swipe", method="POST", data={
            "x1": args.x1, "y1": args.y1, "x2": args.x2, "y2": args.y2, "duration": args.duration
        })
        print(json.dumps(res, indent=2))

    elif args.command == "scroll":
        res = make_request(base_url, "/scroll", method="POST", data={
            "direction": args.direction, "viewId": args.view_id
        })
        print(json.dumps(res, indent=2))

    elif args.command == "type":
        data = {"text": args.text}
        if args.view_id:
            data["viewId"] = args.view_id
        res = make_request(base_url, "/type", method="POST", data=data)
        print(json.dumps(res, indent=2))

    elif args.command == "press":
        res = make_request(base_url, "/press", method="POST", data={"key": args.key})
        print(json.dumps(res, indent=2))

    elif args.command == "launch":
        res = make_request(base_url, "/intent", method="POST", data={"package": args.package})
        print(json.dumps(res, indent=2))

    elif args.command == "screenshot":
        endpoint = f"/screenshot?format={args.format}&quality={args.quality}"
        raw = make_request(base_url, endpoint, raw_response=True)
        if isinstance(raw, dict) and not raw.get("success", True):
            print(json.dumps(raw, indent=2))
            sys.exit(1)
        with open(args.output, "wb") as f:
            f.write(raw)
        print(f"Screenshot saved to {args.output} ({len(raw)} bytes)")

    elif args.command == "notifications":
        if args.dismiss_all:
            res = make_request(base_url, "/notifications/dismiss/all", method="POST")
        elif args.dismiss:
            res = make_request(base_url, "/notifications/dismiss", method="POST", data={"key": args.dismiss})
        else:
            res = make_request(base_url, "/notifications")
        print(json.dumps(res, indent=2))

    elif args.command == "clipboard":
        if args.text is not None:
            res = make_request(base_url, "/clipboard", method="POST", data={"text": args.text})
        else:
            res = make_request(base_url, "/clipboard")
        print(json.dumps(res, indent=2))

    elif args.command == "sms":
        if args.send_to and args.message:
            res = make_request(base_url, "/sms/send", method="POST", data={"phone": args.send_to, "message": args.message})
        else:
            res = make_request(base_url, f"/sms?limit={args.limit}")
        print(json.dumps(res, indent=2))

    elif args.command == "contacts":
        q = urllib.parse.quote(args.query)
        res = make_request(base_url, f"/contacts?q={q}&limit={args.limit}")
        print(json.dumps(res, indent=2))

    elif args.command == "calls":
        res = make_request(base_url, f"/calls?limit={args.limit}")
        print(json.dumps(res, indent=2))

    elif args.command == "stop":
        res = make_request(base_url, "/stop", method="POST")
        print(json.dumps(res, indent=2))

if __name__ == "__main__":
    main()
