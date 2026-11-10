# AI Companion API Reference

Base URL: `http://127.0.0.1:18790` (or `http://localhost:18790`)

---

## 1. System & Health

### `GET /` or `GET /health`
Returns runtime status and active subsystems.
- **Response**:
  ```json
  {
    "success": true,
    "data": {
      "status": "running",
      "port": 18790,
      "accessibility": true,
      "notificationListener": true,
      "uptime": 0
    }
  }
  ```

### `GET /device`
Returns hardware metrics, resolution, and SDK level.
- **Response**:
  ```json
  {
    "success": true,
    "data": {
      "brand": "iQOO",
      "model": "I2401",
      "device": "I2401",
      "sdk": 36,
      "release": "16",
      "widthPixels": 1440,
      "heightPixels": 3168,
      "densityDpi": 600,
      "packageName": "ai.openclaw.companion"
    }
  }
  ```

### `POST /stop`
Remotely shuts down the HTTP server and releases WakeLock.
- **Response**: `{"success": true}`

---

## 2. UI Inspection & Finding

### `GET /ui`
Dumps the current screen accessibility hierarchy tree.
- **Query Parameters**:
  - `depth` (int, default `50`): Maximum traversal depth.
  - `prune` (bool, default `false`): If `true`, strips empty structural container nodes without text/actions to drastically reduce payload size.
- **Node Structure**:
  ```json
  {
    "className": "TextView",
    "text": "Send",
    "contentDescription": "",
    "viewId": "com.example.app:id/send_button",
    "bounds": "934,443,1290,593",
    "clickable": true,
    "scrollable": false,
    "enabled": true,
    "children": []
  }
  ```
- **Bounds Format**: `"left,top,right,bottom"`.
  - Center X: `(left + right) / 2`
  - Center Y: `(top + bottom) / 2`

### `POST /ui/find`
Search for visible nodes by identifier or text.
- **Request Body**:
  ```json
  { "viewId": "com.example.app:id/send_btn" }
  // or
  { "text": "Submit" }
  ```

### `POST /ui/click`
Directly triggers an accessibility click action on a matched node.
- **Request Body**:
  ```json
  { "viewId": "com.example.app:id/send_btn" }
  ```

---

## 3. Gestures & Input

### `POST /tap`
Simulates a touch tap at screen coordinates.
- **Request Body**:
  ```json
  { "x": 540, "y": 1200 }
  ```

### `POST /swipe`
Simulates a finger drag from `(x1, y1)` to `(x2, y2)`.
- **Request Body**:
  ```json
  {
    "x1": 540,
    "y1": 1800,
    "x2": 540,
    "y2": 600,
    "duration": 300
  }
  ```

### `POST /longpress`
Long press at coordinates.
- **Request Body**:
  ```json
  { "x": 540, "y": 1200, "duration": 800 }
  ```

### `POST /scroll`
Scrolls the active window or container.
- **Request Body**:
  ```json
  {
    "direction": "up|down|left|right",
    "viewId": "optional:container_id"
  }
  ```

### `POST /press`
Presses hardware or standard navigation actions.
- **Request Body**:
  ```json
  { "key": "back" }
  ```
- **Allowed Keys**:
  `"back"`, `"home"`, `"recents"`, `"notifications"`, `"quick_settings"`, `"power"`

### `POST /type`
Enters text into an active input field or specific viewId.
- **Request Body**:
  ```json
  {
    "text": "Hello world",
    "viewId": "com.example:id/input" // optional
  }
  ```

---

## 4. App Intents & Screenshots

### `POST /intent`
Launches an application by package name or starts an activity intent.
- **Request Body**:
  ```json
  { "package": "com.whatsapp" }
  // or
  { "action": "android.intent.action.VIEW", "uri": "https://google.com" }
  ```

### `GET /screenshot`
Captures raw screen pixels.
- **Query Parameters**:
  - `format`: `"jpeg"` (default), `"png"`, or `"webp"`
  - `quality`: `1` to `100` (default `80`)
- **Response**: Binary image bytes with appropriate `Content-Type`.

### `GET /screenshot/grant`
Launches the companion app on-screen to trigger the Android MediaProjection consent prompt if screen capture permission is inactive.

---

## 5. Device Utilities

### `GET /notifications`
Returns recent active system notifications.
- **Response Data**: Array of notification objects (`key`, `package`, `title`, `text`, `postTime`, `isClearable`).

### `POST /notifications/dismiss`
Dismisses a notification.
- **Request Body**: `{"key": "0|package|id|null|uid"}`

### `POST /notifications/dismiss/all`
Clears all non-ongoing notifications.

### `GET /clipboard` & `POST /clipboard`
- **GET**: Returns `{"data": {"text": "..."}}`
- **POST**: Sets device clipboard: `{"text": "copied text"}`

### `GET /sms` & `POST /sms/send`
- **GET**: `?limit=25`
- **POST**: `{"phone": "+1234567890", "message": "Text"}`

### `GET /contacts`
- `?q=name&limit=50`

### `GET /calls`
- `?limit=25`

### `GET /packages`
- `?q=query&limit=100`
