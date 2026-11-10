---
name: ai-companion
description: Automate Android device interactions via the local AI Companion HTTP server. Use to inspect the on-screen UI hierarchy, locate and click elements, tap coordinates, swipe/scroll, type text, capture screenshots, read notifications, and launch apps.
---

# AI Companion Skill

Automate and control Android devices through the local AI Companion HTTP API running on port `18790`.

## 1. Quick Start & Connectivity

- **Local Endpoint**: `http://127.0.0.1:18790`
- **Remote / ADB Endpoint**: If connecting from a computer, forward the port first:
  ```bash
  adb forward tcp:18790 tcp:18790
  ```
- **CLI Helper**:
  The skill includes two zero-dependency CLI tools under `scripts/`:
  - `companion.sh` (Bash/curl wrapper — works on any Termux or Linux environment)
  - `companion.py` (Python 3 CLI wrapper with advanced tree search)

### Health Check
Always verify the companion is active before executing tasks:
```bash
./skills/ai-companion/scripts/companion.sh health
# or curl -s http://localhost:18790/health
```
Ensure `"accessibility": true` in the returned JSON.

---

## 2. Choosing Between UI Hierarchy & Screenshots

| Strategy | When to Use | Advantages |
| :--- | :--- | :--- |
| **UI Hierarchy (`/ui?prune=true`)** | **Default choice** for apps, settings, forms, chat apps, and system UI. | Fast (<50ms), exact coordinates, semantic text search, no OCR needed. |
| **Screenshot (`/screenshot`)** | Games, custom canvases, image viewers, maps, or visual verification. | Shows true pixel rendering when accessibility nodes are obscured. |

---

## 3. Standard Automation Workflows

### Flow 1: Launch an App & Click a Button
1. **Launch the application**:
   ```bash
   ./skills/ai-companion/scripts/companion.sh launch com.whatsapp
   ```
2. **Wait briefly (300-500ms) and dump the UI tree**:
   ```bash
   ./skills/ai-companion/scripts/companion.sh ui true
   ```
3. **Calculate element center from `bounds`**:
   Node bounds format: `"left,top,right,bottom"`
   - `center_x = (left + right) / 2`
   - `center_y = (top + bottom) / 2`
4. **Tap the target element**:
   ```bash
   ./skills/ai-companion/scripts/companion.sh tap <center_x> <center_y>
   ```

### Flow 2: Form Input & Typing
1. Tap the text field or target its `viewId`:
   ```bash
   ./skills/ai-companion/scripts/companion.sh type "My Message" com.whatsapp:id/entry
   ```
2. If typing into an already focused input without specifying `viewId`:
   ```bash
   ./skills/ai-companion/scripts/companion.sh type "My Message"
   ```
3. Hide keyboard if necessary:
   ```bash
   ./skills/ai-companion/scripts/companion.sh press back
   ```

### Flow 3: Scrolling & Lists
1. Scroll down to discover off-screen elements:
   ```bash
   ./skills/ai-companion/scripts/companion.sh scroll down
   ```
2. Or use swipe gestures for precise drag distances:
   ```bash
   # Swipe up (scrolls content down) from y=2000 to y=600
   ./skills/ai-companion/scripts/companion.sh swipe 720 2000 720 600 300
   ```

### Flow 4: Reading Notifications & OTP Codes
Read incoming messages, push notifications, and 2FA verification codes:
```bash
./skills/ai-companion/scripts/companion.sh notifications
```
Or check SMS directly:
```bash
./skills/ai-companion/scripts/companion.sh sms 5
```

---

## 4. Key Navigation Reference

Use `press <key>` for hardware and system navigation:
- `back`: Back button / dismiss modal or keyboard
- `home`: Return to Android launcher home screen
- `recents`: App switcher / recent apps overview
- `notifications`: Pull down the notification drawer
- `quick_settings`: Pull down system quick settings tiles
- `power`: Screen lock / wake

---

## 5. Robustness Guidelines
- **Always Prune Empty Nodes**: Use `/ui?prune=true` (or `companion.sh ui true`) to avoid parsing thousands of empty layout wrappers.
- **Dynamic Content**: After tapping buttons that trigger network requests or page transitions, pause 300ms before querying `/ui` again.
- **Coordinate Scaling**: Always use the screen dimensions reported by `companion.sh device`.
