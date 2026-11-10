package ai.openclaw.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.openclaw.companion.model.TreeNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event processing handled on demand via UI queries
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ─── Native Screenshot (Android 11+ / API 30+) ─────────────

    fun takeScreenshot(timeoutMs: Long = 3000): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference<Bitmap?>(null)

            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val swBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBuffer.close()
                                resultRef.set(swBitmap)
                            } catch (_: Exception) {
                            } finally {
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            latch.countDown()
                        }
                    }
                )
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                return resultRef.get()
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    // ─── UI Tree ──────────────────────────────────────────────

    /**
     * Single-pass direct map serialization: bypasses intermediate TreeNode object allocation
     * and redundant trimTree passes, cutting GC pauses and memory allocations dramatically.
     */
    fun dumpUiMap(maxDepth: Int = 50, pruneEmpty: Boolean = false): Map<String, Any?> {
        val root = rootInActiveWindow ?: return mapOf("className" to "empty", "text" to "No active window")
        val rect = Rect()
        return buildNodeMap(root, 0, maxDepth, pruneEmpty, rect)
    }

    private fun buildNodeMap(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        pruneEmpty: Boolean,
        rect: Rect
    ): Map<String, Any?> {
        val className = node.className?.toString()?.takeLastWhile { it != '.' } ?: ""
        val text = node.text?.toString()?.take(200) ?: ""
        val contentDesc = node.contentDescription?.toString()?.take(200) ?: ""
        val viewId = node.viewIdResourceName?.toString() ?: ""

        node.getBoundsInScreen(rect)
        val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"

        val children = mutableListOf<Map<String, Any?>>()
        if (depth < maxDepth) {
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                val childMap = buildNodeMap(child, depth + 1, maxDepth, pruneEmpty, rect)
                if (pruneEmpty && childMap.isEmpty()) {
                    continue
                }
                children.add(childMap)
            }
        }

        val isClickable = node.isClickable
        val isScrollable = node.isScrollable

        // If pruning empty nodes: prune pure non-interactive structural containers without content
        if (pruneEmpty && text.isEmpty() && contentDesc.isEmpty() && viewId.isEmpty() && !isClickable && !isScrollable && children.isEmpty()) {
            return emptyMap()
        }

        val result = mutableMapOf<String, Any?>(
            "className" to className,
            "text" to text,
            "contentDescription" to contentDesc,
            "viewId" to viewId,
            "bounds" to bounds,
            "clickable" to isClickable,
            "scrollable" to isScrollable,
            "enabled" to node.isEnabled
        )

        if (node.isCheckable) result["checked"] = node.isChecked
        if (node.isSelected) result["selected"] = true
        if (children.isNotEmpty()) {
            result["children"] = children
        }

        return result
    }

    fun dumpUiTree(): TreeNode {
        val root = rootInActiveWindow ?: return TreeNode("empty", "No active window", "", "", "")
        return buildTree(root)
    }

    private fun buildTree(node: AccessibilityNodeInfo, depth: Int = 0): TreeNode {
        val children = mutableListOf<TreeNode>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.add(buildTree(child, depth + 1))
            }
        }

        return TreeNode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            viewIdResourceName = node.viewIdResourceName?.toString() ?: "",
            bounds = run {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                "${rect.left},${rect.top},${rect.right},${rect.bottom}"
            },
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
        if (x < 0 || y < 0) return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            dispatchGestureInternal(gesture, duration)
        } catch (_: Exception) {
            false
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 300): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            dispatchGestureInternal(gesture, duration)
        } catch (_: Exception) {
            false
        }
    }

    fun longPress(x: Int, y: Int, duration: Long = 500): Boolean {
        if (x < 0 || y < 0) return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            dispatchGestureInternal(gesture, duration)
        } catch (_: Exception) {
            false
        }
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
        // Try native lookup first (fastest Binder call)
        try {
            val nativeMatches = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nativeMatches.isNullOrEmpty()) {
                return nativeMatches[0]
            }
        } catch (_: Exception) {}
        // Fallback to substring recursive search
        return findNodeByIdRecursive(root, viewId)
    }

    private fun findNodeByIdRecursive(node: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.contains(viewId) == true) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeByIdRecursive(child, viewId)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        try {
            val nativeMatches = root.findAccessibilityNodeInfosByText(text)
            if (!nativeMatches.isNullOrEmpty()) {
                return nativeMatches
            }
        } catch (_: Exception) {}
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text, results)
        return results
    }

    private fun findNodesByTextRecursive(node: AccessibilityNodeInfo, text: String, results: MutableList<AccessibilityNodeInfo>) {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodesByTextRecursive(child, text, results)
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

    fun typeIntoFocusedNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
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

    private fun dispatchGestureInternal(gesture: GestureDescription, duration: Long): Boolean {
        var dispatched = false
        val latch = CountDownLatch(1)
        val waitTimeout = (duration + 1000).coerceAtLeast(2000)
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
        latch.await(waitTimeout, TimeUnit.MILLISECONDS)
        return dispatched
    }
}
