package ai.openclaw.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.view.View
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
    private lateinit var screenshotStatusText: TextView
    private lateinit var toggleServiceBtn: com.google.android.material.button.MaterialButton
    private lateinit var statusDot: View
    private lateinit var statusAccentBar: View
    private lateinit var accessibilityBadge: TextView
    private lateinit var notificationBadge: TextView
    private lateinit var overlayBadge: TextView

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        screenshotStatusText = findViewById(R.id.screenshotStatusText)
        toggleServiceBtn = findViewById(R.id.toggleServiceBtn)
        statusDot = findViewById(R.id.statusDot)
        statusAccentBar = findViewById(R.id.statusAccentBar)
        accessibilityBadge = findViewById(R.id.accessibilityBadge)
        notificationBadge = findViewById(R.id.notificationBadge)
        overlayBadge = findViewById(R.id.overlayBadge)

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

        // Request runtime permissions
        requestRuntimePermissions()

        // Toggle service: starting triggers both server and screen recording prompt
        toggleServiceBtn.setOnClickListener {
            if (isServiceRunning) {
                stopClawService()
            } else {
                startClawService()
                if (!ScreenCaptureManager.isGranted) {
                    requestMediaProjection()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ClawForegroundService.onStateChanged = {
            updateServiceStatus()
        }
        updatePermissionStatuses()
        updateServiceStatus()

        // Check if launched from /screenshot/grant
        if (intent?.getBooleanExtra("request_media_projection", false) == true) {
            requestMediaProjection()
            intent.removeExtra("request_media_projection")
        }
    }

    override fun onPause() {
        super.onPause()
        ClawForegroundService.onStateChanged = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("request_media_projection", false)) {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureManager.setPermissionResult(resultCode, data)
            } else {
                // User declined: do not stop the server, keep screenshot disabled
                ScreenCaptureManager.releaseProjection()
            }
            updatePermissionStatuses()
            updateServiceStatus()
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

        updateBadge(accessibilityBadge, hasAccessibility)
        updateBadge(notificationBadge, hasNotification)
        updateBadge(overlayBadge, hasOverlay)
    }

    private fun updateBadge(badge: TextView, granted: Boolean) {
        badge.text = if (granted) "✓" else "✗"
        badge.setTextColor(getColor(if (granted) R.color.status_running else R.color.status_stopped))
    }

    private fun updateServiceStatus() {
        isServiceRunning = isServiceRunning(this)
        if (isServiceRunning) {
            statusText.text = "Running"
            statusText.setTextColor(getColor(R.color.status_running))
            statusDot.setBackgroundResource(R.drawable.status_dot_running)
            statusAccentBar.setBackgroundColor(getColor(R.color.status_running))
            addressText.text = "http://localhost:${ClawForegroundService.actualPort}"
            toggleServiceBtn.text = "Stop"
            toggleServiceBtn.setIconResource(android.R.drawable.ic_media_pause)
            toggleServiceBtn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_stopped))

            if (ScreenCaptureManager.isGranted) {
                screenshotStatusText.text = "Screen capture: Active ✓"
                screenshotStatusText.setTextColor(getColor(R.color.status_running))
            } else {
                screenshotStatusText.text = "Screen capture: Disabled (restart to enable)"
                screenshotStatusText.setTextColor(getColor(R.color.text_secondary))
            }
            screenshotStatusText.visibility = View.VISIBLE
        } else {
            statusText.text = "Stopped"
            statusText.setTextColor(getColor(R.color.status_stopped))
            statusDot.setBackgroundResource(R.drawable.status_dot_stopped)
            statusAccentBar.setBackgroundColor(getColor(R.color.status_stopped))
            addressText.text = ""
            screenshotStatusText.visibility = View.GONE
            toggleServiceBtn.text = "Start"
            toggleServiceBtn.setIconResource(android.R.drawable.ic_media_play)
            toggleServiceBtn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.primary))
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        return ClawForegroundService.isRunning()
    }

    private fun startClawService() {
        ClawForegroundService.setServiceEnabled(this, true)
        val intent = Intent(this, ClawForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        window.decorView.postDelayed({ updateServiceStatus() }, 150)
        window.decorView.postDelayed({ updateServiceStatus() }, 500)
    }

    private fun stopClawService() {
        ClawForegroundService.setServiceEnabled(this, false)
        val intent = Intent(this, ClawForegroundService::class.java).apply {
            action = ClawForegroundService.ACTION_STOP
        }
        startService(intent)
        stopService(Intent(this, ClawForegroundService::class.java))
        ScreenCaptureManager.releaseProjection()
        window.decorView.postDelayed({ updateServiceStatus() }, 150)
        updatePermissionStatuses()
        updateServiceStatus()
    }
}
