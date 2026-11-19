---
name: ai-companion
description: Automate Android device interactions via the local AI Companion HTTP server and `companion` CLI. Use to inspect the on-screen UI hierarchy, find and click elements, tap coordinates, swipe/scroll, type text, capture screenshots, read notifications, and launch apps or deep links.
---

# AI Companion Skill

Automate and control Android devices through the local AI Companion HTTP API running on port `18790`.

## 1. Quick Start & CLI Entrypoints

- **Local Endpoint**: `http://127.0.0.1:18790`
- **Remote / ADB Endpoint**: If connecting from a computer, forward the port first:
  ```bash
  adb forward tcp:18790 tcp:18790
  ```
- **Global CLI (`companion`)**:
  Use `companion` directly from `PATH` (delegates to `companion.py` when `python3` is installed, with a bash/curl fallback). Run `companion -h` or `companion <command> -h` for full options.
- **Fallback Script Paths** (if `companion` is not in `PATH`):
  - `~/.gemini/config/skills/ai-companion/scripts/companion.py` (Python 3 CLI — supports `--find`, `--click`, and all flags)
  - `~/.agents/skills/ai-companion/scripts/companion.sh` (Bash wrapper / curl fallback)

### Health Check
Always verify the companion is active before executing tasks:
```bash
companion health
# or curl -s http://127.0.0.1:18790/health
```
Ensure `"accessibility": true` in the returned JSON.

---

## 2. CLI Command Reference

Run `companion -h` for top-level help or `companion <command> -h` for command-specific flags.

| Command | Arguments & Flags | Description |
| :--- | :--- | :--- |
| `health` | — | Check server health, accessibility, and notification listener status |
| `device` | — | Get screen resolution (`widthPixels`, `heightPixels`), model, and Android SDK |
| `ui` | `[true\|false] [--find <query>] [--click] [--depth N] [--no-prune]` | Dump UI tree, search nodes by text/description/viewId, and optionally click the first match |
| `tap` | `<x> <y>` | Tap exact screen coordinates |
| `longpress` | `<x> <y> [--duration 800]` | Long press at coordinates |
| `swipe` | `<x1> <y1> <x2> <y2> [--duration 300]` | Drag from `(x1, y1)` to `(x2, y2)` |
| `scroll` | `<up\|down\|left\|right> [--id <viewId>]` | Scroll screen or target container |
| `type` | `<text> [--id <viewId>]` | Enter text into currently focused input or target `viewId` |
| `press` | `<back\|home\|recents\|power\|notifications\|quick_settings>` | Trigger system navigation or hardware action |
| `launch` | `<package_or_uri> [--package pkg] [--uri uri] [--action act]` | Launch an app by package name or open a deep-link URI |
| `packages` | `[query] [--query <q>] [--limit 100]` | List or search installed app package names |
| `screenshot` | `[output.jpg] [--format jpeg\|png\|webp] [--quality 80]` | Capture screen pixels to a file |
| `notifications` | `[--dismiss <key>] [--dismiss-all]` | Read active notifications or dismiss them |
| `clipboard` | `[text]` | Get clipboard contents or set clipboard text |
| `sms` | `[--limit 25] [--send-to <phone> --message <text>]` | Read recent SMS messages or send an SMS |
| `contacts` | `[query] [--query <q>] [--limit 50]` | Search device contacts |
| `calls` | `[limit] [--limit 25]` | Read recent call logs |
| `stop` | — | Stop the companion foreground service |

---

## 3. Choosing Between UI Hierarchy & Screenshots

| Strategy | When to Use | Advantages |
| :--- | :--- | :--- |
| **UI Hierarchy (`companion ui`)** | **Default choice** for apps, settings, forms, chat apps, and system UI. | Fast (<50ms), exact bounds/centers, `--find` & `--click` support, no OCR needed. |
| **Screenshot (`companion screenshot`)** | Games, custom canvases, image viewers, maps, or visual verification. | Shows true pixel rendering when accessibility nodes are obscured. |

---

## 4. Standard Automation Workflows

### Flow 1: Find Installed App & Launch (Package or Deep Link)
1. **Find package name if unknown**:
   ```bash
   companion packages instagram
   ```
2. **Launch by package name or deep-link URI**:
   ```bash
   companion launch com.instagram.android
   # or open a URI / deep link directly:
   companion launch "https://instagram.com/zulk.a"
   # or specify both package and URI explicitly:
   companion launch --package com.instagram.android --uri "https://instagram.com/zulk.a"
   ```

### Flow 2: Locate & Click Elements Quickly
1. **Find and click in one command**:
   ```bash
   companion ui --find "Search" --click
   ```
2. **Or inspect matching bounds/centers manually**:
   ```bash
   companion ui --find "Send"
   companion tap <center_x> <center_y>
   ```
   Node bounds format is `"left,top,right,bottom"`; `companion ui --find` automatically computes `"center": [cx, cy]`.

### Flow 3: Form Input & Typing
1. Tap the text field first (or target its `viewId` directly):
   ```bash
   companion type "My Message" --id com.whatsapp:id/entry
   ```
2. If typing into an already focused input without specifying `--id`:
   ```bash
   companion type "My Message"
   ```
3. Hide keyboard if necessary:
   ```bash
   companion press back
   ```

### Flow 4: Scrolling & Lists
```bash
companion scroll down
# Or precise swipe gesture (e.g., swipe up to scroll content down)
companion swipe 720 2000 720 600 --duration 300
```

### Flow 5: Reading Notifications & OTP Codes
```bash
companion notifications
companion sms --limit 5
```

---

## 5. Key Navigation Reference

Use `companion press <key>` for hardware and system navigation:
- `back`: Back button / dismiss modal or keyboard
- `home`: Return to Android launcher home screen
- `recents`: App switcher / recent apps overview
- `notifications`: Pull down the notification drawer
- `quick_settings`: Pull down system quick settings tiles
- `power`: Screen lock / wake

---

## 6. Robustness Guidelines
- **Always Prune Empty Nodes**: `companion ui` prunes empty layout containers by default (`prune=true`). Pass `--no-prune` only if structural hierarchy debugging is required.
- **Dynamic Content**: After tapping buttons or launching apps that trigger transitions, pause `0.5–2s` before querying `companion ui` again.
- **Coordinate Scaling**: Always use the screen dimensions reported by `companion device` and the node centers returned by `companion ui --find`.
