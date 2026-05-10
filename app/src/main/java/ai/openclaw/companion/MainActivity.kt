package ai.openclaw.companion

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import ai.openclaw.companion.service.ClawAccessibilityService
import ai.openclaw.companion.service.ClawForegroundService
import ai.openclaw.companion.service.ClawNotificationListener
import ai.openclaw.companion.service.ScreenCaptureManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var toggleServiceBtn: Button
    private lateinit var accessibilityStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var screenshotStatus: TextView

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        toggleServiceBtn = findViewById(R.id.toggleServiceBtn)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        notificationStatus = findViewById(R.id.notificationStatus)
        overlayStatus = findViewById(R.id.overlayStatus)
        screenshotStatus = findViewById(R.id.screenshotStatus)

        // Permission buttons
        findViewById<Button>(R.id.enableAccessibilityBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.enableNotificationBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.enableOverlayBtn).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        // Screenshot permission button
        findViewById<Button>(R.id.enableScreenshotBtn)?.setOnClickListener {
            requestMediaProjection()
        }

        // Request runtime permissions
        requestRuntimePermissions()

        // Toggle service
        toggleServiceBtn.setOnClickListener {
            if (isServiceRunning) {
                stopClawService()
            } else {
                startClawService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        updateServiceStatus()

        // Check if launched from /screenshot/grant
        if (intent?.getBooleanExtra("request_media_projection", false) == true) {
            requestMediaProjection()
            intent.removeExtra("request_media_projection")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("request_media_projection", false)) {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureManager.setPermissionResult(resultCode, data)
            }
            updatePermissionStatuses()
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    private fun updatePermissionStatuses() {
        val hasAccessibility = ClawAccessibilityService.instance != null
        val hasNotification = ClawNotificationListener.instance != null
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        val hasScreenshot = ScreenCaptureManager.isGranted

        accessibilityStatus.text = "♿ Accessibility Service ${if (hasAccessibility) "✅" else "❌"}"
        accessibilityStatus.setTextColor(getColor(if (hasAccessibility) R.color.accent_green else R.color.accent_red))

        notificationStatus.text = "🔔 Notification Access ${if (hasNotification) "✅" else "❌"}"
        notificationStatus.setTextColor(getColor(if (hasNotification) R.color.accent_green else R.color.accent_red))

        overlayStatus.text = "🖥️ Draw Over Apps ${if (hasOverlay) "✅" else "❌"}"
        overlayStatus.setTextColor(getColor(if (hasOverlay) R.color.accent_green else R.color.accent_red))

        screenshotStatus.text = "📸 Screenshot Permission ${if (hasScreenshot) "✅" else "❌"}"
        screenshotStatus.setTextColor(getColor(if (hasScreenshot) R.color.accent_green else R.color.accent_red))
    }

    private fun updateServiceStatus() {
        isServiceRunning = isServiceRunning(this)
        if (isServiceRunning) {
            statusText.text = "✅ Running"
            statusText.setTextColor(getColor(R.color.accent_green))
            addressText.text = "http://localhost:${ClawApp.DEFAULT_PORT}"
            toggleServiceBtn.text = "Stop Service"
        } else {
            statusText.text = "⛔ Stopped"
            statusText.setTextColor(getColor(R.color.accent_red))
            addressText.text = ""
            toggleServiceBtn.text = "Start Service"
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(Int.MAX_VALUE)
        return services.any { it.service.className == ClawForegroundService::class.java.name }
    }

    private fun startClawService() {
        val intent = Intent(this, ClawForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopClawService() {
        val intent = Intent(this, ClawForegroundService::class.java)
        intent.action = ClawForegroundService.ACTION_STOP
        startService(intent)
    }
}