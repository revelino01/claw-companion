# 🦞 Claw Companion

Android companion app for [OpenClaw](https://github.com/openclaw/openclaw) — replaces ADB with a local HTTP API for accessibility, gestures, screenshots, notifications, and more.

## Features

| API | Method | Description |
|-----|--------|-------------|
| `/` | GET | Health check & status |
| `/ui` | GET | Full accessibility tree as JSON |
| `/ui/find` | POST | Find nodes by viewId or text |
| `/ui/click` | POST | Click by viewId or coordinates |
| `/tap` | POST | Tap at x,y coordinates |
| `/swipe` | POST | Swipe gesture |
| `/longpress` | POST | Long press at x,y |
| `/scroll` | POST | Scroll up/down by viewId or gesture |
| `/press` | POST | Global key (back, home, recents, etc.) |
| `/type` | POST | Set text on a field |
| `/intent` | POST | Launch app by package or intent |
| `/notifications` | GET | Get recent notifications |
| `/notifications/dismiss` | POST | Dismiss a notification |
| `/sms` | GET | Read SMS messages |
| `/sms/send` | POST | Send SMS |
| `/contacts` | GET | Search contacts |
| `/calls` | GET | Read call log |
| `/clipboard` | GET/POST | Read/write clipboard |
| `/device` | GET | Device info (screen size, model, SDK) |
| `/packages` | GET | List installed packages |
| `/screenshot` | GET | Screenshot (requires MediaProjection) |

## Setup

1. Install the APK
2. Enable **Accessibility Service** for Claw Companion
3. Enable **Notification Access** for Claw Companion
4. Enable **Draw Over Other Apps** (for future features)
5. Grant SMS, Contacts, Call Log permissions
6. Start the service — it runs on `http://localhost:18790`

## Usage from OpenClaw

```bash
# Get UI tree
curl -s http://localhost:18790/ui | jq

# Tap coordinates
curl -s -X POST http://localhost:18790/tap -H 'Content-Type: application/json' -d '{"x":540,"y":1200}'

# Find and click a button
curl -s -X POST http://localhost:18790/ui/click -H 'Content-Type: application/json' -d '{"viewId":"com.android.settings:id/button"}'

# Press back
curl -s -X POST http://localhost:18790/press -H 'Content-Type: application/json' -d '{"key":"back"}'

# Read notifications
curl -s http://localhost:18790/notifications | jq

# Launch an app
curl -s -X POST http://localhost:18790/intent -H 'Content-Type: application/json' -d '{"package":"com.google.android.googlequicksearchbox"}'
```

## Building

The project builds via GitHub Actions. Push to `main` to trigger a debug build, or use workflow_dispatch with `release=true` for a signed release APK.

## Requirements

- Android 8.0+ (API 26)
- No root required
- Permissions: Accessibility, Notification Access, SMS, Contacts, Call Log