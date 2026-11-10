#!/usr/bin/env bash
# AI Companion Shell Helper
# A zero-dependency bash/curl script for AI agents to interact with the Android AI Companion HTTP server.

set -e

BASE_URL="${AI_COMPANION_URL:-http://127.0.0.1:18790}"

usage() {
    cat << 'EOF'
AI Companion CLI Helper (Bash/curl)

Usage: companion.sh <command> [arguments...]

Commands:
  health                     Check companion server health and status
  device                     Get device resolution, model, and OS version
  ui [prune]                 Dump accessibility UI hierarchy (default prune=true)
  tap <x> <y>                Tap screen coordinates
  swipe <x1> <y1> <x2> <y2> [duration_ms]
                             Swipe from (x1,y1) to (x2,y2)
  scroll <up|down|left|right>
                             Scroll screen direction
  press <back|home|recents|power|notifications|quick_settings>
                             Trigger a system navigation/hardware key
  type <text> [viewId]       Type text into focused field (or specific viewId)
  launch <package>           Launch an application (e.g. com.whatsapp)
  screenshot [output.jpg]    Capture device screenshot
  notifications              List recent active notifications
  clipboard [text]           Get or set clipboard contents
  sms [limit]                Read recent SMS messages (default 25)
  contacts [query]           Search contacts
  stop                       Stop companion service remotely
EOF
    exit 1
}

CMD="$1"
shift || usage

case "$CMD" in
    health)
        curl -s "${BASE_URL}/health"
        echo ""
        ;;
    device)
        curl -s "${BASE_URL}/device"
        echo ""
        ;;
    ui)
        PRUNE="${1:-true}"
        curl -s "${BASE_URL}/ui?prune=${PRUNE}"
        echo ""
        ;;
    tap)
        if [ -z "$1" ] || [ -z "$2" ]; then
            echo "Usage: companion.sh tap <x> <y>"
            exit 1
        fi
        curl -s -X POST "${BASE_URL}/tap" \
            -H "Content-Type: application/json" \
            -d "{\"x\":$1,\"y\":$2}"
        echo ""
        ;;
    swipe)
        if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ] || [ -z "$4" ]; then
            echo "Usage: companion.sh swipe <x1> <y1> <x2> <y2> [duration_ms]"
            exit 1
        fi
        DURATION="${5:-300}"
        curl -s -X POST "${BASE_URL}/swipe" \
            -H "Content-Type: application/json" \
            -d "{\"x1\":$1,\"y1\":$2,\"x2\":$3,\"y2\":$4,\"duration\":$DURATION}"
        echo ""
        ;;
    scroll)
        if [ -z "$1" ]; then
            echo "Usage: companion.sh scroll <up|down|left|right>"
            exit 1
        fi
        curl -s -X POST "${BASE_URL}/scroll" \
            -H "Content-Type: application/json" \
            -d "{\"direction\":\"$1\"}"
        echo ""
        ;;
    press)
        if [ -z "$1" ]; then
            echo "Usage: companion.sh press <back|home|recents|power|notifications|quick_settings>"
            exit 1
        fi
        curl -s -X POST "${BASE_URL}/press" \
            -H "Content-Type: application/json" \
            -d "{\"key\":\"$1\"}"
        echo ""
        ;;
    type)
        if [ -z "$1" ]; then
            echo "Usage: companion.sh type <text> [viewId]"
            exit 1
        fi
        TEXT="$1"
        VIEW_ID="$2"
        if [ -n "$VIEW_ID" ]; then
            PAYLOAD=$(printf '{"text":%s,"viewId":%s}' "$(printf '%s' "$TEXT" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')" "$(printf '%s' "$VIEW_ID" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')")
        else
            PAYLOAD=$(printf '{"text":%s}' "$(printf '%s' "$TEXT" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')")
        fi
        curl -s -X POST "${BASE_URL}/type" \
            -H "Content-Type: application/json" \
            -d "$PAYLOAD"
        echo ""
        ;;
    launch)
        if [ -z "$1" ]; then
            echo "Usage: companion.sh launch <package_name>"
            exit 1
        fi
        curl -s -X POST "${BASE_URL}/intent" \
            -H "Content-Type: application/json" \
            -d "{\"package\":\"$1\"}"
        echo ""
        ;;
    screenshot)
        OUT="${1:-screenshot.jpg}"
        curl -s "${BASE_URL}/screenshot" -o "$OUT"
        echo "Screenshot saved to $OUT"
        ;;
    notifications)
        curl -s "${BASE_URL}/notifications"
        echo ""
        ;;
    clipboard)
        if [ -n "$1" ]; then
            PAYLOAD=$(printf '{"text":%s}' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')")
            curl -s -X POST "${BASE_URL}/clipboard" \
                -H "Content-Type: application/json" \
                -d "$PAYLOAD"
        else
            curl -s "${BASE_URL}/clipboard"
        fi
        echo ""
        ;;
    sms)
        LIMIT="${1:-25}"
        curl -s "${BASE_URL}/sms?limit=${LIMIT}"
        echo ""
        ;;
    contacts)
        Q="${1:-}"
        curl -s "${BASE_URL}/contacts?q=${Q}&limit=50"
        echo ""
        ;;
    stop)
        curl -s -X POST "${BASE_URL}/stop"
        echo ""
        ;;
    *)
        usage
        ;;
esac
