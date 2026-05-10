package ai.openclaw.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.openclaw.companion.model.TreeNode

class ClawAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClawAccessibilityService? = null
            private set

        fun isEnabled(context: android.content.Context): Boolean {
            val expected = ComponentName(context, ClawAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(expected.flattenToString())
        }

        private var _lastEventTime = 0L
        val lastEventTime: Long get() = _lastEventTime
    }

    override fun onServiceConnected() {
        instance = this
        _lastEventTime = System.currentTimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        _lastEventTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ─── UI Tree ──────────────────────────────────────────────

    fun dumpUiTree(): TreeNode {
        val root = rootInActiveWindow ?: return TreeNode("empty", "No active window", "", "", "")
        return buildTree(root)
    }

    private fun buildTree(node: AccessibilityNodeInfo, depth: Int = 0): TreeNode {
        val children = mutableListOf<TreeNode>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.add(buildTree(child, depth + 1))
                child.recycle()
            }
        }

        return TreeNode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            viewIdResourceName = node.viewIdResourceName?.toString() ?: "",
            bounds = node.boundsInScreen?.let { rect ->
                "${rect.left},${rect.top},${rect.right},${rect.bottom}"
            } ?: "",
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            checked = if (node.isCheckable) node.isChecked else null,
            enabled = node.isEnabled,
            focusable = node.isFocusable,
            selected = node.isSelected,
            depth = depth,
            children = children
        )
    }

    // ─── Gestures ─────────────────────────────────────────────

    fun tap(x: Int, y: Int, duration: Long = 50): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture)
    }

    fun longPress(x: Int, y: Int, duration: Long = 500): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture)
    }

    fun swipeDirection(direction: String, displayWidth: Int = 1080, displayHeight: Int = 2400): Boolean {
        val cx = displayWidth / 2
        val cy = displayHeight / 2
        val margin = 100
        return when (direction.lowercase()) {
            "up" -> swipe(cx, cy + margin, cx, margin)
            "down" -> swipe(cx, margin, cx, cy + margin)
            "left" -> swipe(cx + margin, cy, margin, cy)
            "right" -> swipe(margin, cy, cx + margin, cy)
            else -> false
        }
    }

    // ─── Node Actions ──────────────────────────────────────────

    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeById(root, viewId)
    }

    private fun findNodeById(node: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.contains(viewId) == true) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeById(child, viewId)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(root, text, results)
        return results
    }

    private fun findNodesByText(node: AccessibilityNodeInfo, text: String, results: MutableList<AccessibilityNodeInfo>) {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodesByText(child, text, results)
            }
        }
    }

    fun clickNode(viewId: String): Boolean {
        val node = findNodeById(viewId) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun setText(viewId: String, text: String): Boolean {
        val node = findNodeById(viewId) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollNode(viewId: String, direction: Int): Boolean {
        val node = findNodeById(viewId) ?: return false
        val action = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun pressRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun pressNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_ACCESS_NOTIFICATIONS)
        } else false
    }

    fun pressQuickSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        } else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun pressPowerDialog(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        } else false
    }

    // ─── Internal ──────────────────────────────────────────────

    private fun dispatchGesture(gesture: GestureDescription): Boolean {
        var dispatched = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                dispatched = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                dispatched = false
                latch.countDown()
            }
        }, null)
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return dispatched
    }
}

