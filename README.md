# 🤖 AI Companion

A lightweight, high-performance Android companion app for autonomous AI agents (OpenClaw, Claude Computer Use, AutoGPT, LangChain, local LLMs, Termux agents, etc.) — provides a fast, secure local HTTP API for accessibility hierarchy inspection, UI automation gestures, screenshot capture, notifications, clipboard, SMS, calls, and device control.

## Overview

AI Companion runs entirely on your Android device as a foreground service with a local HTTP server bound to `127.0.0.1:18790`. It enables local AI agents (such as agents running directly in Termux or Python) or remote AI agents (via ADB port-forwarding or reverse proxy) to interact with Android applications just like a human user without requiring root.

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
| `/settings/system` | GET/POST/DELETE | System settings (read/write/delete) |
| `/settings/secure` | GET/POST/DELETE | Secure settings — requires `WRITE_SECURE_SETTINGS` via ADB |
| `/settings/global` | GET/POST/DELETE | Global settings — requires `WRITE_SECURE_SETTINGS` via ADB |
| `/stop` | POST | Stop foreground service remotely |

## Setup

1. Install the APK.
2. Enable **Accessibility Service** for AI Companion.
3. Enable **Notification Access** for AI Companion.
4. Enable **Draw Over Other Apps** (Overlay).
5. Grant SMS, Contacts, and Call Log permissions when prompted.
6. *(Optional)* For secure/global settings editing:
   ```bash
   adb shell pm grant ai.openclaw.companion android.permission.WRITE_SECURE_SETTINGS
   ```
7. Start the service in the app — tapping **Start** launches the local HTTP server and prompts for screen capture permission:
   * If approved, the screenshot endpoint is active.
   * If declined, the server continues running normally with screenshots disabled. Simply toggle the service off and on again to re-prompt.

## ⚡ One-Line Skill Installation

Install the AI Companion skill and global `companion` CLI across your AI harnesses (**Antigravity**, **OpenClaw**, **Hermes Agent**, and **Pi Agent**) with a single command:

### Linux / macOS / Android Termux (Bash)
```bash
curl -fsSL https://raw.githubusercontent.com/revelino01/claw-companion/main/install.sh | bash
```

### Windows (PowerShell)
```powershell
irm https://raw.githubusercontent.com/revelino01/claw-companion/main/install.ps1 | iex
```

### Windows (Command Prompt - CMD)
```cmd
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/revelino01/claw-companion/main/install.ps1 | iex"
```

The installer automatically:
1. Deploys the skill definition ([`SKILL.md`](skills/ai-companion/SKILL.md)) and API schemas to:
   - **Antigravity**: `~/.gemini/antigravity-cli/skills/ai-companion` and `~/.agents/skills/ai-companion`
   - **OpenClaw**: `~/.openclaw/skills/ai-companion`
   - **Hermes Agent**: `~/.hermes/skills/ai-companion`
   - **Pi Agent**: `~/.pi/skills/ai-companion`
2. Installs the `companion` command globally into your `PATH` so your agent or terminal can execute automation tasks directly:
   ```bash
   companion health
   companion ui true
   companion tap 540 1200
   companion screenshot screen.jpg
   ```

## AI Agent Skill Guide

This repository includes a ready-to-use AI Agent Skill in [`skills/ai-companion/`](skills/ai-companion/) for autonomous agents, featuring zero-dependency CLI wrappers:

- **Agent Playbook**: [`skills/ai-companion/SKILL.md`](skills/ai-companion/SKILL.md)
- **API Reference**: [`skills/ai-companion/references/api_reference.md`](skills/ai-companion/references/api_reference.md)
- **Bash & curl CLI**: [`skills/ai-companion/scripts/companion.sh`](skills/ai-companion/scripts/companion.sh)
- **Python CLI**: [`skills/ai-companion/scripts/companion.py`](skills/ai-companion/scripts/companion.py)
- **Windows Batch CLI**: [`skills/ai-companion/scripts/companion.cmd`](skills/ai-companion/scripts/companion.cmd)

## Usage

The server securely binds to `127.0.0.1:18790`.

### On-Device (e.g. Termux or Local Python)
AI agents running locally on the device can query the API directly:
```bash
curl -s http://localhost:18790/health | jq
```

### Over ADB / USB / Wi-Fi
If connecting an agent running on an external computer:
```bash
adb forward tcp:18790 tcp:18790
curl -s http://localhost:18790/health | jq
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
curl -s -X POST http://localhost:18790/type -H 'Content-Type: application/json' -d '{"text":"Hello from AI Companion!"}'

# Press back or home
curl -s -X POST http://localhost:18790/press -H 'Content-Type: application/json' -d '{"key":"back"}'
curl -s -X POST http://localhost:18790/press -H 'Content-Type: application/json' -d '{"key":"home"}'

# Read notifications
curl -s http://localhost:18790/notifications | jq

# Get a screenshot (JPEG default, or lossless PNG)
curl -s http://localhost:18790/screenshot --output screenshot.jpg
curl -s "http://localhost:18790/screenshot?format=png" --output screenshot.png

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
