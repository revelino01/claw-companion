package ai.openclaw.companion.server

import android.accessibilityservice.AccessibilityService
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityNodeInfo
import ai.openclaw.companion.model.*
import ai.openclaw.companion.MainActivity
import ai.openclaw.companion.service.ClawAccessibilityService
import ai.openclaw.companion.service.ClawForegroundService
import ai.openclaw.companion.service.ClawNotificationListener
import ai.openclaw.companion.service.ScreenCaptureManager
import ai.openclaw.companion.service.SpeechRecognizerManager
import kotlinx.coroutines.runBlocking
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ClawHttpServer(port: Int, private val context: Context) : NanoHTTPD(port) {

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun serve(session: IHTTPSession): Response {
        // Handle CORS preflight
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                addHeader("Access-Control-Allow-Headers", "Content-Type")
                addHeader("Access-Control-Max-Age", "86400")
            }
        }

        val uri = session.uri ?: return errorResponse(400, "No URI")
        val method = session.method
        val params = parseParams(session)

        return try {
            when {
                // ─── Health ─────────────────────────────────
                uri == "/" || uri == "/health" -> json(ApiResponse(true, mapOf(
                    "status" to "running",
                    "port" to listeningPort,
                    "accessibility" to (ClawAccessibilityService.instance != null),
                    "notificationListener" to (ClawNotificationListener.instance != null),
                    "uptime" to (System.currentTimeMillis() - context.getSystemService(Context.POWER_SERVICE).let {
                        // Approximate
                        0L
                    })
                )))

                // ─── UI Tree ────────────────────────────────
                uri == "/ui" && method == Method.GET -> getUiTree(params)
                uri == "/ui/find" && method == Method.POST -> findNode(session)
                uri == "/ui/click" && method == Method.POST -> clickNode(session)

                // ─── Gestures ──────────────────────────────
                uri == "/tap" && method == Method.POST -> tap(session)
                uri == "/swipe" && method == Method.POST -> swipe(session)
                uri == "/longpress" && method == Method.POST -> longPress(session)
                uri == "/scroll" && method == Method.POST -> scroll(session)

                // ─── Global Actions ─────────────────────────
                uri == "/press" && method == Method.POST -> pressKey(session)

                // ─── Input ──────────────────────────────────
                uri == "/type" && method == Method.POST -> typeText(session)

                // ─── Intents ────────────────────────────────
                uri == "/intent" && method == Method.POST -> launchIntent(session)

                // ─── Screenshot ─────────────────────────────
                uri == "/screenshot" && method == Method.GET -> screenshot(params)
                uri == "/screenshot/grant" && method == Method.GET -> requestScreenshotGrant()
                uri == "/ocr" && method == Method.GET -> ocr(params)

                // ─── Notifications ──────────────────────────
                uri == "/notifications" && method == Method.GET -> getNotifications()
                uri == "/notifications/dismiss" && method == Method.POST -> dismissNotification(session)
                uri == "/notifications/dismiss/all" && method == Method.POST -> dismissAllNotifications()

                // ─── SMS ────────────────────────────────────
                uri == "/sms" && method == Method.GET -> getSms(params)
                uri == "/sms/send" && method == Method.POST -> sendSms(session)

                // ─── Contacts ──────────────────────────────
                uri == "/contacts" && method == Method.GET -> getContacts(params)

                // ─── Call Log ──────────────────────────────
                uri == "/calls" && method == Method.GET -> getCallLog(params)

                // ─── Clipboard ─────────────────────────────
                uri == "/clipboard" && method == Method.GET -> getClipboard()
                uri == "/clipboard" && method == Method.POST -> setClipboard(session)

                // ─── Device Info ────────────────────────────
                uri == "/device" && method == Method.GET -> getDeviceInfo()

                // ─── Packages ──────────────────────────────
                uri == "/packages" && method == Method.GET -> getPackages(params)

                // ─── Speech-to-Text ──────────────────────────
                uri == "/stt/status" && method == Method.GET -> getSttStatus()
                uri == "/stt/download" && method == Method.POST -> downloadSttModel()
                uri == "/stt/transcribe" && method == Method.POST -> transcribeAudio(session)

                // ─── Service Control ────────────────────────
                uri == "/stop" && method == Method.POST -> stopService()

                else -> errorResponse(404, "Not found: $uri")
            }
        } catch (e: Exception) {
            errorResponse(500, e.message ?: "Internal error")
        }
    }

    // ─── UI Tree ──────────────────────────────────────────────

    private fun getUiTree(params: Map<String, String>): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val maxDepth = params["depth"]?.toIntOrNull() ?: 50
        val tree = service.dumpUiTree()
        val trimmed = trimTree(tree, maxDepth)
        return json(ApiResponse(true, trimmed))
    }

    private fun findNode(session: IHTTPSession): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val req = parseBody(session, FindRequest::class.java) ?: return errorResponse(400, "Invalid request")

        val results = mutableListOf<Map<String, Any?>>()
        if (req.viewId != null) {
            val node = service.findNodeById(req.viewId)
            if (node != null) {
                results.add(mapOf<String, Any?>(
                    "viewId" to node.viewIdResourceName,
                    "text" to node.text?.toString(),
                    "bounds" to run {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        "${rect.left},${rect.top},${rect.right},${rect.bottom}"
                    },
                    "clickable" to node.isClickable
                ))
                node.recycle()
            }
        }
        if (req.text != null) {
            val nodes = service.findNodesByText(req.text)
            for (node in nodes) {
                results.add(mapOf<String, Any?>(
                    "viewId" to node.viewIdResourceName,
                    "text" to node.text?.toString(),
                    "bounds" to run {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        "${rect.left},${rect.top},${rect.right},${rect.bottom}"
                    },
                    "clickable" to node.isClickable
                ))
            }
        }
        return json(ApiResponse(true, results))
    }

    private fun clickNode(session: IHTTPSession): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val req = parseBody(session, ClickRequest::class.java) ?: return errorResponse(400, "Invalid request")

        val success = when {
            req.viewId != null -> service.clickNode(req.viewId)
            req.x != null && req.y != null -> service.tap(req.x, req.y)
            else -> return errorResponse(400, "Provide viewId or x,y coordinates")
        }
        return json(ApiResponse(success))
    }

    // ─── Gestures ─────────────────────────────────────────────

    private fun tap(session: IHTTPSession): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val req = parseBody(session, TapRequest::class.java) ?: return errorResponse(400, "Invalid request")
        val success = service.tap(req.x, req.y)
        return json(ApiResponse(success, mapOf("x" to req.x, "y" to req.y)))
    }

    private fun swipe(session: IHTTPSession): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val req = parseBody(session, SwipeRequest::class.java) ?: return errorResponse(400, "Invalid request")
        val success = service.swipe(req.x1, req.y1, req.x2, req.y2, req.duration)
        return json(ApiResponse(success))
    }

    private fun longPress(session: IHTTPSession): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val req = parseBody(session, TapRequest::class.java) ?: return errorResponse(400, "Invalid request")
        val success = service.longPress(req.x, req.y)
        return json(ApiResponse(success))
    }

    private fun scroll(session: IHTTPSession): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val req = parseBody(session, ScrollRequest::class.java) ?: return errorResponse(400, "Invalid request")
        val direction = if (req.direction == "up")
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD

        val success = if (req.viewId != null) {
            service.scrollNode(req.viewId, direction)
        } else {
            // Fallback: swipe gesture
            val metrics = context.resources.displayMetrics
            val cx = metrics.widthPixels / 2
            when (req.direction) {
                "up" -> service.swipe(cx, metrics.heightPixels * 2 / 3, cx, metrics.heightPixels / 3)
                "down" -> service.swipe(cx, metrics.heightPixels / 3, cx, metrics.heightPixels * 2 / 3)
                else -> false
            }
        }
        return json(ApiResponse(success))
    }

    // ─── Global Actions ───────────────────────────────────────

    private fun pressKey(session: IHTTPSession): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val req = parseBody(session, PressRequest::class.java) ?: return errorResponse(400, "Invalid request")
        val success = when (req.key.lowercase()) {
            "back" -> service.pressBack()
            "home" -> service.pressHome()
            "recents", "overview" -> service.pressRecents()
            "notifications" -> service.pressNotifications()
            "quick_settings", "settings" -> service.pressQuickSettings()
            "power" -> service.pressPowerDialog()
            else -> return errorResponse(400, "Unknown key: ${req.key}")
        }
        return json(ApiResponse(success))
    }

    // ─── Text Input ────────────────────────────────────────────

    private fun typeText(session: IHTTPSession): Response {
        val service = ClawAccessibilityService.instance
            ?: return errorResponse(503, "Accessibility service not enabled")

        val req = parseBody(session, TypeRequest::class.java) ?: return errorResponse(400, "Invalid request")

        val success = if (req.viewId != null) {
            service.setText(req.viewId, req.text)
        } else {
            // Fallback: use clipboard paste
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("claw", req.text))
            // Simulate paste gesture would need accessibility, return clipboard set status
            true
        }
        return json(ApiResponse(success))
    }

    // ─── Intents ───────────────────────────────────────────────

    private fun launchIntent(session: IHTTPSession): Response {
        val req = parseBody(session, IntentRequest::class.java) ?: return errorResponse(400, "Invalid request")

        try {
            val intent = when {
                req.action != null -> Intent(req.action)
                req.`package` != null -> {
                    // Try launch intent first, fallback to MAIN/LAUNCHER
                    context.packageManager.getLaunchIntentForPackage(req.`package`)
                        ?: Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setPackage(req.`package`)
                        }
                }
                else -> return errorResponse(400, "Provide action or package")
            }

            req.`package`?.let { intent.setPackage(it) }
            req.className?.let { intent.component = ComponentName(req.`package`!!, it) }
            req.extras?.forEach { (key, value) ->
                intent.putExtra(key, value)
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return json(ApiResponse(true, mapOf("action" to (intent.action ?: ""), "package" to (req.`package` ?: ""))))
        } catch (e: Exception) {
            return errorResponse(400, "Failed to launch: ${e.message}")
        }
    }

    // ─── Screenshot ────────────────────────────────────────────

    private fun screenshot(params: Map<String, String>): Response {
        val bitmap = ScreenCaptureManager.captureScreenshot(context)
            ?: return errorResponse(500, ScreenCaptureManager.lastError ?: "Capture failed")

        val format = when (params["format"]?.lowercase()) {
            "jpeg", "jpg" -> Pair(Bitmap.CompressFormat.JPEG, "image/jpeg")
            else -> Pair(Bitmap.CompressFormat.PNG, "image/png")
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(format.first, 90, stream)
        bitmap.recycle()

        return newFixedLengthResponse(Response.Status.OK, format.second, stream.toByteArray().inputStream(), stream.size().toLong()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-cache")
        }
    }

    private fun requestScreenshotGrant(): Response {
        ScreenCaptureManager.releaseProjection()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("request_media_projection", true)
        }
        context.startActivity(intent)
        return json(ApiResponse(true, mapOf("message" to "Permission dialog opened. Approve it, then retry /screenshot.")))
    }

    // ─── OCR ────────────────────────────────────────────────────

    private fun ocr(params: Map<String, String>): Response {
        val bitmap = ScreenCaptureManager.captureScreenshot(context)
            ?: return errorResponse(500, "Screenshot failed: " + (ScreenCaptureManager.lastError ?: "unknown"))

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Run OCR synchronously with a latch
        val latch = java.util.concurrent.CountDownLatch(1)
        val resultRef = AtomicReference<com.google.mlkit.vision.text.Text?>(null)
        val errorRef = AtomicReference<Exception?>(null)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                resultRef.set(visionText)
                latch.countDown()
            }
            .addOnFailureListener { e ->
                errorRef.set(e)
                latch.countDown()
            }

        latch.await(10, TimeUnit.SECONDS)
        bitmap.recycle()

        val error = errorRef.get()
        if (error != null) {
            return errorResponse(500, "OCR failed: ${error.message}")
        }

        val visionText = resultRef.get()
            ?: return errorResponse(500, "OCR timed out")

        val textBlocks = visionText.textBlocks.map { block ->
            mapOf(
                "text" to block.text,
                "bounds" to rectToString(block.boundingBox),
                "lines" to block.lines.map { line ->
                    mapOf(
                        "text" to line.text,
                        "bounds" to rectToString(line.boundingBox),
                        "elements" to line.elements.map { el ->
                            mapOf(
                                "text" to el.text,
                                "bounds" to rectToString(el.boundingBox)
                            )
                        }
                    )
                }
            )
        }

        return json(ApiResponse(true, mapOf(
            "fullText" to visionText.text,
            "blocks" to textBlocks
        )))
    }

    private fun rectToString(rect: Rect?): String {
        if (rect == null) return "0,0,0,0"
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    // ─── Notifications ─────────────────────────────────────────

    private fun getNotifications(): Response {
        val notifications = ClawNotificationListener.recentNotifications.toList()
        return json(ApiResponse(true, notifications))
    }

    private fun dismissNotification(session: IHTTPSession): Response {
        val listener = ClawNotificationListener.instance
            ?: return errorResponse(503, "Notification listener not enabled")

        val body = parseJsonBody(session)
            ?: return errorResponse(400, "Invalid request")
        val key = body["key"] ?: return errorResponse(400, "Missing key")
        val success = listener.dismissNotification(key)
        return json(ApiResponse(success))
    }

    private fun dismissAllNotifications(): Response {
        val listener = ClawNotificationListener.instance
            ?: return errorResponse(503, "Notification listener not enabled")
        val success = listener.dismissAll()
        return json(ApiResponse(success))
    }

    // ─── SMS ──────────────────────────────────────────────────

    private fun getSms(params: Map<String, String>): Response {
        val limit = params["limit"]?.toIntOrNull() ?: 25
        val conversations = mutableListOf<Map<String, Any?>>()

        try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            )
            context.contentResolver.query(uri, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    conversations.add(mapOf(
                        "id" to cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
                        "address" to cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)),
                        "body" to cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)),
                        "date" to cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                        "type" to when (type) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
                            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                            else -> "other"
                        },
                        "read" to (cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1)
                    ))
                    count++
                }
            }
        } catch (_: SecurityException) {
            return errorResponse(403, "SMS permission not granted")
        }

        return json(ApiResponse(true, conversations))
    }

    private fun sendSms(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
            ?: return errorResponse(400, "Invalid request")
        val address = body["address"] ?: return errorResponse(400, "Missing address")
        val text = body["text"] ?: return errorResponse(400, "Missing text")

        return try {
            val sentIntent = Intent("SMS_SENT")
            val deliveredIntent = Intent("SMS_DELIVERED")
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendMultipartTextMessage(address, null, smsManager.divideMessage(text), null, null)
            json(ApiResponse(true))
        } catch (e: Exception) {
            errorResponse(500, "Failed to send SMS: ${e.message}")
        }
    }

    // ─── Contacts ─────────────────────────────────────────────

    private fun getContacts(params: Map<String, String>): Response {
        val limit = params["limit"]?.toIntOrNull() ?: 50
        val query = params["q"]
        val contacts = mutableListOf<Map<String, String?>>()

        try {
            val selection = if (query != null) {
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            } else null
            val selectionArgs = if (query != null) arrayOf("%$query%") else null

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                selection, selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    contacts.add(mapOf(
                        "name" to cursor.getString(0),
                        "number" to cursor.getString(1),
                        "type" to when (cursor.getInt(2)) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                            else -> "other"
                        }
                    ))
                    count++
                }
            }
        } catch (_: SecurityException) {
            return errorResponse(403, "Contacts permission not granted")
        }

        return json(ApiResponse(true, contacts))
    }

    // ─── Call Log ─────────────────────────────────────────────

    private fun getCallLog(params: Map<String, String>): Response {
        val limit = params["limit"]?.toIntOrNull() ?: 25
        val calls = mutableListOf<Map<String, Any?>>()

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    calls.add(mapOf(
                        "number" to cursor.getString(0),
                        "date" to cursor.getLong(1),
                        "duration" to cursor.getString(2),
                        "type" to when (cursor.getInt(3)) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            else -> "other"
                        },
                        "name" to cursor.getString(4)
                    ))
                    count++
                }
            }
        } catch (_: SecurityException) {
            return errorResponse(403, "Call log permission not granted")
        }

        return json(ApiResponse(true, calls))
    }

    // ─── Clipboard ─────────────────────────────────────────────

    private fun getClipboard(): Response {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return json(ApiResponse(true, mapOf("text" to text)))
    }

    private fun setClipboard(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
            ?: return errorResponse(400, "Invalid request")
        val text = body["text"] ?: return errorResponse(400, "Missing text")

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("claw", text))
        return json(ApiResponse(true))
    }

    // ─── Device Info ──────────────────────────────────────────

    private fun getDeviceInfo(): Response {
        val metrics = context.resources.displayMetrics
        return json(ApiResponse(true, mapOf(
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "sdk" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "widthPixels" to metrics.widthPixels,
            "heightPixels" to metrics.heightPixels,
            "densityDpi" to metrics.densityDpi,
            "packageName" to context.packageName
        )))
    }

    // ─── Packages ──────────────────────────────────────────────

    private fun getPackages(params: Map<String, String>): Response {
        val query = params["q"]?.lowercase()
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { query == null || it.packageName.lowercase().contains(query) || (pm.getApplicationLabel(it).toString().lowercase().contains(query)) }
            .take(params["limit"]?.toIntOrNull() ?: 100)
            .map {
                mapOf(
                    "package" to it.packageName,
                    "name" to pm.getApplicationLabel(it).toString()
                )
            }
        return json(ApiResponse(true, apps))
    }

    // ─── Service Control ───────────────────────────────────────

    private fun stopService(): Response {
        val intent = Intent(context, ClawForegroundService::class.java).apply {
            action = ClawForegroundService.ACTION_STOP
        }
        context.startService(intent)
        return json(ApiResponse(true, "Stopping service"))
    }

    // ─── Speech-to-Text ────────────────────────────────────────

    private fun getSttStatus(): Response {
        val manager = SpeechRecognizerManager.instance
            ?: return json(ApiResponse(true, mapOf(
                "ready" to false,
                "mode" to "none",
                "modelStatus" to "not_initialized"
            )))

        val statusInfo = manager.getStatusInfo()
        return json(ApiResponse(true, statusInfo))
    }

    private fun downloadSttModel(): Response {
        val manager = SpeechRecognizerManager.instance
            ?: return errorResponse(503, "Speech recognizer not initialized")

        val result = runBlocking { manager.downloadModel() }
        return json(ApiResponse(
            result is SpeechRecognizerManager.ModelStatus.Available,
            mapOf(
                "status" to when (result) {
                    is SpeechRecognizerManager.ModelStatus.Available -> "available"
                    is SpeechRecognizerManager.ModelStatus.Downloading -> "downloading(${result.progress}%)"
                    is SpeechRecognizerManager.ModelStatus.DownloadFailed -> "failed: ${result.error}"
                    else -> result.toString()
                }
            )
        ))
    }

    private fun transcribeAudio(session: IHTTPSession): Response {
        val manager = SpeechRecognizerManager.instance
            ?: return errorResponse(503, "Speech recognizer not initialized")

        if (manager.modelStatus.value !is SpeechRecognizerManager.ModelStatus.Available) {
            return errorResponse(503, "Speech recognizer model not ready. Call /stt/download first or check /stt/status")
        }

        try {
            // Parse multipart form data to get the audio file
            val files = mutableMapOf<String, String>()
            val params = mutableMapOf<String, String>()
            session.parseBody(files)

            // Find the audio file in the upload
            val audioFile = files["audio"] ?: files["file"] ?: files.values.firstOrNull()
                ?: return errorResponse(400, "No audio file provided. Upload as 'audio' or 'file' field")

            // Read the file bytes
            val file = java.io.File(audioFile)
            if (!file.exists()) {
                return errorResponse(400, "Uploaded file not found")
            }

            val audioBytes = file.readBytes()
            val mimeType = session.headers?.get("content-type") ?: "audio/wav"

            val result = runBlocking {
                manager.transcribeFile(audioBytes, mimeType)
            }

            // Clean up temp file from NanoHTTPD
            file.delete()

            return json(ApiResponse(true, mapOf(
                "text" to result.text,
                "mode" to result.mode
            )))
        } catch (e: Exception) {
            return errorResponse(500, "Transcription failed: ${e.message}")
        }
    }

    // ─── Helpers ───────────────────────────────────────────────

    private fun trimTree(node: TreeNode, maxDepth: Int): Map<String, Any?> {
        if (node.depth > maxDepth) return emptyMap()

        val result = mutableMapOf<String, Any?>(
            "className" to node.className.takeLastWhile { it != '.' },
            "text" to node.text.take(200),
            "contentDescription" to node.contentDescription.take(200),
            "viewId" to node.viewIdResourceName,
            "bounds" to node.bounds,
            "clickable" to node.clickable,
            "scrollable" to node.scrollable,
            "enabled" to node.enabled
        )
        if (node.checked != null) result["checked"] = node.checked
        if (node.selected) result["selected"] = node.selected
        if (node.children.isNotEmpty()) {
            result["children"] = node.children.map { trimTree(it, maxDepth) }
        }
        return result
    }

    private fun parseParams(session: IHTTPSession): Map<String, String> {
        val params = mutableMapOf<String, String>()
        session.parms?.forEach { (k, v) -> params[k] = v }
        session.queryParameterString?.split("&")?.forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
        }
        return params
    }

    private fun <T> parseBody(session: IHTTPSession, clazz: Class<T>): T? {
        return try {
            val contentLength = session.headers?.get("content-length")?.toLong() ?: 0L
            if (contentLength > 0L) {
                val bodyMap = mutableMapOf<String, String>()
                session.parseBody(bodyMap)
                val postData = bodyMap["postData"] ?: return null
                gson.fromJson(postData, clazz)
            } else {
                // Try query params for GET-style requests
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseJsonBody(session: IHTTPSession): Map<String, String>? {
        return try {
            val contentLength = session.headers?.get("content-length")?.toLong() ?: 0L
            if (contentLength > 0L) {
                val bodyMap = mutableMapOf<String, String>()
                session.parseBody(bodyMap)
                val postData = bodyMap["postData"] ?: return null
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson<Map<String, String>>(postData, type)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun json(data: Any): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(data)
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            addHeader("Access-Control-Allow-Headers", "Content-Type")
        }
    }

    private fun errorResponse(code: Int, message: String): Response {
        return newFixedLengthResponse(
            when (code) {
                400 -> Response.Status.BAD_REQUEST
                403 -> Response.Status.FORBIDDEN
                404 -> Response.Status.NOT_FOUND
                500 -> Response.Status.INTERNAL_ERROR
                501 -> Response.Status.NOT_IMPLEMENTED
                503 -> Response.Status.SERVICE_UNAVAILABLE
                else -> Response.Status.OK
            },
            "application/json",
            gson.toJson(ApiResponse(false, error = message))
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }


}
