#!/usr/bin/env bash
# AI Companion Skill Installer
# One-line installer for AI harnesses: Antigravity, OpenClaw, Hermes Agent, and Pi Agent.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/revelino01/claw-companion/main/install.sh | bash

set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/revelino01/claw-companion/main/skills/ai-companion"
TMP_DIR=$(mktemp -d 2>/dev/null || mktemp -d -t 'ai-companion')
trap 'rm -rf "$TMP_DIR"' EXIT

echo "=========================================="
echo "  🤖 Installing AI Companion Skill        "
echo "=========================================="

# 1. Fetch Skill Files
echo "[1/4] Fetching skill files..."

# If running locally from repo root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || echo "")"
LOCAL_SKILL_DIR=""
if [ -f "${SCRIPT_DIR}/skills/ai-companion/SKILL.md" ]; then
    LOCAL_SKILL_DIR="${SCRIPT_DIR}/skills/ai-companion"
elif [ -f "./skills/ai-companion/SKILL.md" ]; then
    LOCAL_SKILL_DIR="./skills/ai-companion"
fi

if [ -n "$LOCAL_SKILL_DIR" ] && [ -d "$LOCAL_SKILL_DIR" ]; then
    echo "  -> Using local repository files..."
    cp -r "$LOCAL_SKILL_DIR"/* "$TMP_DIR/"
else
    echo "  -> Downloading from GitHub repository..."
    mkdir -p "$TMP_DIR/scripts" "$TMP_DIR/references"
    curl -fsSL "${REPO_RAW}/SKILL.md" -o "$TMP_DIR/SKILL.md"
    curl -fsSL "${REPO_RAW}/references/api_reference.md" -o "$TMP_DIR/references/api_reference.md"
    curl -fsSL "${REPO_RAW}/scripts/companion.sh" -o "$TMP_DIR/scripts/companion.sh"
    curl -fsSL "${REPO_RAW}/scripts/companion.py" -o "$TMP_DIR/scripts/companion.py"
fi

chmod +x "$TMP_DIR/scripts/companion.sh" "$TMP_DIR/scripts/companion.py" 2>/dev/null || true

# 2. Identify Target Directories for Supported AI Harnesses
echo "[2/4] Detecting AI harnesses..."

# Target definitions: (Harness Name | Path)
HARNESS_TARGETS=(
    "Antigravity (Global)"  "$HOME/.gemini/antigravity-cli/skills/ai-companion"
    "Antigravity (.agents)" "$HOME/.agents/skills/ai-companion"
    "OpenClaw"              "$HOME/.openclaw/skills/ai-companion"
    "Hermes Agent"          "$HOME/.hermes/skills/ai-companion"
    "Pi Agent"              "$HOME/.pi/skills/ai-companion"
)

# Also check XDG config paths if main harness folder exists
if [ -d "$HOME/.config/openclaw" ]; then
    HARNESS_TARGETS+=("OpenClaw (XDG)" "$HOME/.config/openclaw/skills/ai-companion")
fi
if [ -d "$HOME/.config/hermes" ]; then
    HARNESS_TARGETS+=("Hermes (XDG)" "$HOME/.config/hermes/skills/ai-companion")
fi
if [ -d "$HOME/.config/pi" ]; then
    HARNESS_TARGETS+=("Pi (XDG)" "$HOME/.config/pi/skills/ai-companion")
fi

# 3. Deploy Skill Files
echo "[3/4] Deploying skill to harnesses..."

INSTALLED_COUNT=0
for ((i=0; i<${#HARNESS_TARGETS[@]}; i+=2)); do
    NAME="${HARNESS_TARGETS[i]}"
    DEST="${HARNESS_TARGETS[i+1]}"

    # Determine if we should install:
    # Always install to standard locations so any installed harness will immediately find it
    mkdir -p "$DEST/scripts" "$DEST/references"
    cp -r "$TMP_DIR"/* "$DEST/"
    chmod +x "$DEST/scripts/companion.sh" "$DEST/scripts/companion.py" 2>/dev/null || true
    echo "  ✓ $NAME -> $DEST"
    INSTALLED_COUNT=$((INSTALLED_COUNT + 1))
done

# 4. Install Global CLI Command (companion)
echo "[4/4] Setting up global 'companion' CLI command..."

CLI_SRC="$TMP_DIR/scripts/companion.sh"
CLI_INSTALLED=false

if [ -n "${PREFIX:-}" ] && [ -d "${PREFIX}/bin" ]; then
    # Termux environment
    cp "$CLI_SRC" "${PREFIX}/bin/companion"
    chmod +x "${PREFIX}/bin/companion"
    echo "  ✓ Installed CLI to ${PREFIX}/bin/companion"
    CLI_INSTALLED=true
elif [ -d "$HOME/.local/bin" ] || mkdir -p "$HOME/.local/bin" 2>/dev/null; then
    cp "$CLI_SRC" "$HOME/.local/bin/companion"
    chmod +x "$HOME/.local/bin/companion"
    echo "  ✓ Installed CLI to $HOME/.local/bin/companion"
    CLI_INSTALLED=true
elif [ -w "/usr/local/bin" ]; then
    cp "$CLI_SRC" "/usr/local/bin/companion"
    chmod +x "/usr/local/bin/companion"
    echo "  ✓ Installed CLI to /usr/local/bin/companion"
    CLI_INSTALLED=true
fi

if [ "$CLI_INSTALLED" = false ]; then
    echo "  ! Note: Could not find writable bin in PATH. You can call the script at:"
    echo "    ~/.agents/skills/ai-companion/scripts/companion.sh"
fi

echo ""
echo "=========================================="
echo "  🎉 Installation Complete! ($INSTALLED_COUNT harnesses configured)"
echo "=========================================="
echo ""
echo "Supported harnesses configured:"
echo "  • Antigravity  (~/.gemini/antigravity-cli/skills & ~/.agents/skills)"
echo "  • OpenClaw     (~/.openclaw/skills)"
echo "  • Hermes Agent (~/.hermes/skills)"
echo "  • Pi Agent     (~/.pi/skills)"
echo ""
echo "Quick Test:"
echo "  companion health"
echo "  companion ui true"
echo ""
