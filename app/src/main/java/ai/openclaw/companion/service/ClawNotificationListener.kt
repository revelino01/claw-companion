package ai.openclaw.companion.service

import android.content.Intent
import android.os.Build
import android.content.ComponentName
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

class ClawNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: ClawNotificationListener? = null
            private set

        val recentNotifications = ConcurrentLinkedQueue<JSONObject>()
        private const val MAX_NOTIFICATIONS = 100

        fun isEnabled(context: android.content.Context): Boolean {
            val cn = ComponentName(context, ClawNotificationListener::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabled.contains(cn.flattenToString())
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification.extras
            val json = JSONObject().apply {
                put("key", sbn.key)
                put("package", sbn.packageName)
                put("postTime", sbn.postTime)
                put("category", sbn.notification.category ?: "")
                put("isOngoing", sbn.isOngoing)
                put("isClearable", sbn.isClearable)

                val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: text

                put("title", title)
                put("text", text)
                put("bigText", bigText)
            }

            recentNotifications.add(json)
            while (recentNotifications.size > MAX_NOTIFICATIONS) {
                recentNotifications.poll()
            }
        } catch (_: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op for now
    }

    fun dismissNotification(key: String): Boolean {
        return try {
            cancelNotification(key)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun dismissAll(): Boolean {
        return try {
            cancelAllNotifications()
            true
        } catch (_: Exception) {
            false
        }
    }
}