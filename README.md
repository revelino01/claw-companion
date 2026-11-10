# 🦞 Claw Companion

Android companion app for [OpenClaw](https://github.com/openclaw/openclaw) — replaces ADB with a local HTTP API for accessibility, gestures, screenshots, notifications, and more.

## Features

| API | Method | Description |
|-----|--------|-------------|
| `/` or `/health` | GET | Health check & service status |
| `/ui` | GET | Full accessibility tree as JSON (`?depth=50&prune=true`) |
| `/ui/find` | POST | Find nodes by `viewId` or `text` |
| `/ui/click` | POST | Click by `viewId` or `x,y` coordinates |
| `/tap` | POST | Tap at `x,y` coordinates |
| `/swipe` | POST | Swipe gesture (`x1, y1, x2, y2, duration`) |
| `/longpress` | POST | Long press at `x,y` coordinates |
| `/scroll` | POST | Scroll up/down by `viewId` or directional swipe |
| `/press` | POST | Global key (`back`, `home`, `recents`, `notifications`, `quick_settings`, `power`) |
| `/type` | POST | Set text on field by `viewId`, or type into currently focused input |
| `/intent` | POST | Launch app by package name or action intent |
| `/notifications` | GET | Get recent active notifications |
| `/notifications/dismiss` | POST | Dismiss a single notification by `key` |
| `/notifications/dismiss/all` | POST | Dismiss all notifications |
| `/sms` | GET | Read SMS messages (`?limit=25`) |
| `/sms/send` | POST | Send SMS |
| `/contacts` | GET | Search contacts (`?q=name&limit=50`) |
| `/calls` | GET | Read call logs (`?limit=25`) |
| `/clipboard` | GET/POST | Read or write device clipboard |
| `/device` | GET | Device info (screen resolution, model, SDK level) |
| `/packages` | GET | List installed packages (`?q=name&limit=100`) |
| `/screenshot` | GET | Screenshot capture (`?format=jpeg|png|webp&quality=80`) |
| `/screenshot/grant` | GET | Request screen recording permission |
| `/ocr` | GET | OCR text recognition from current screen (ML Kit) |
| `/settings/system` | GET/POST/DELETE | System settings (read/write/delete) |
| `/settings/secure` | GET/POST/DELETE | Secure settings — requires `WRITE_SECURE_SETTINGS` via ADB |
| `/settings/global` | GET/POST/DELETE | Global settings — requires `WRITE_SECURE_SETTINGS` via ADB |
| `/stop` | POST | Stop foreground service remotely |

## Setup

1. Install the APK.
2. Enable **Accessibility Service** for Claw Companion.
3. Enable **Notification Access** for Claw Companion.
4. Enable **Draw Over Other Apps** (Overlay).
5. Grant SMS, Contacts, and Call Log permissions.
6. *(Optional)* For secure/global settings editing:
   ```bash
   adb shell pm grant ai.openclaw.companion android.permission.WRITE_SECURE_SETTINGS
   ```
7. Start the service in the app — tapping **Start** will launch the local HTTP server and prompt for screen capture permission:
   * If approved, screenshot & OCR endpoints are active.
   * If declined, the server continues running normally with screenshots disabled. Simply toggle the service off and on again to re-prompt.

## Usage from OpenClaw

The server securely binds to `127.0.0.1:18790`. If connecting from a computer via ADB:
```bash
adb forward tcp:18790 tcp:18790
```

### Examples

```bash
# Health check
curl -s http://localhost:18790/ | jq

# Get UI tree (with empty containers pruned for smaller payload)
curl -s "http://localhost:18790/ui?prune=true" | jq

# Tap coordinates
curl -s -X POST http://localhost:18790/tap -H 'Content-Type: application/json' -d '{"x":540,"y":1200}'

# Find and click a button
curl -s -X POST http://localhost:18790/ui/click -H 'Content-Type: application/json' -d '{"viewId":"com.android.settings:id/button"}'

# Type text into currently active input field
curl -s -X POST http://localhost:18790/type -H 'Content-Type: application/json' -d '{"text":"Hello from OpenClaw!"}'

# Press back or home
curl -s -X POST http://localhost:18790/press -H 'Content-Type: application/json' -d '{"key":"back"}'
curl -s -X POST http://localhost:18790/press -H 'Content-Type: application/json' -d '{"key":"home"}'

# Read notifications
curl -s http://localhost:18790/notifications | jq

# Get a screenshot (JPEG default, or lossless PNG)
curl -s http://localhost:18790/screenshot --output screenshot.jpg
curl -s "http://localhost:18790/screenshot?format=png" --output screenshot.png

# OCR: read text from current screen
curl -s http://localhost:18790/ocr | jq

# Launch an app
curl -s -X POST http://localhost:18790/intent -H 'Content-Type: application/json' -d '{"package":"com.whatsapp"}'

# Read / write settings
curl -s "http://localhost:18790/settings/secure?key=wifi_on"
curl -s -X POST http://localhost:18790/settings/system \
  -H 'Content-Type: application/json' -d '{"key":"screen_brightness","value":"128","type":"int"}'
```

## Building

The project builds via GitHub Actions. Push to `main` to trigger a debug build and release APK.

## Requirements

- Android 8.0+ (API 26)
- No root required
- Permissions: Accessibility, Notification Access, SMS, Contacts, Call Log
