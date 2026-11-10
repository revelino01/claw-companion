package ai.openclaw.companion.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import ai.openclaw.companion.ClawApp
import ai.openclaw.companion.MainActivity
import ai.openclaw.companion.R
import ai.openclaw.companion.server.ClawHttpServer

class ClawForegroundService : Service() {

    companion object {
        @Volatile
        var instance: ClawForegroundService? = null
            private set

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        @Volatile
        var actualPort: Int = ClawApp.DEFAULT_PORT
            private set

        const val ACTION_STOP = "ai.openclaw.companion.ACTION_STOP"
        const val CHANNEL_ID = "claw_companion_channel"
        const val NOTIFICATION_ID = 10001
    }

    private var server: ClawHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification(actualPort)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Start with SPECIAL_USE initially. MediaProjection type requires user grant first,
            // otherwise Android throws SecurityException. Promoted dynamically if capture is granted.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }

        // Acquire partial wake lock if not already held to avoid leaking multiple locks
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ClawCompanion::ForegroundService"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // Start HTTP server if not running
        if (server == null) {
            startServer()
        }

        return START_STICKY
    }

    fun updateForegroundServiceTypeForMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notification = buildNotification(actualPort)
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } catch (_: Exception) {}
        }
    }

    private fun startServer() {
        try {
            server = ClawHttpServer(ClawApp.DEFAULT_PORT, this)
            server?.start()
            actualPort = ClawApp.DEFAULT_PORT
        } catch (e: Exception) {
            // Port might be in use, try alternative
            try {
                server = ClawHttpServer(ClawApp.DEFAULT_PORT + 1, this)
                server?.start()
                actualPort = ClawApp.DEFAULT_PORT + 1
            } catch (e2: Exception) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
        running = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text, port))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
