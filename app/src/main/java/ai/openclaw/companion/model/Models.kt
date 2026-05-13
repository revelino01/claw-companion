package ai.openclaw.companion.model

import com.google.gson.annotations.SerializedName

/**
 * Represents a node in the accessibility tree, serializable to JSON for HTTP API responses.
 */
data class TreeNode(
    val className: String,
    val text: String,
    val contentDescription: String,
    @SerializedName("viewId")
    val viewIdResourceName: String,
    val bounds: String,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val focusable: Boolean = false,
    val selected: Boolean = false,
    val depth: Int = 0,
    val children: List<TreeNode> = emptyList()
)

/**
 * API request/response models
 */

data class TapRequest(val x: Int, val y: Int)

data class SwipeRequest(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val duration: Long = 300)

data class TypeRequest(val text: String, val viewId: String? = null)

data class PressRequest(val key: String)  // back, home, recents, notifications, quick_settings, power

data class ScrollRequest(val viewId: String? = null, val direction: String = "down")

data class ClickRequest(val viewId: String? = null, val text: String? = null, val x: Int? = null, val y: Int? = null)

data class FindRequest(val viewId: String? = null, val text: String? = null)

data class IntentRequest(
    val action: String? = null,
    val `package`: String? = null,
    val className: String? = null,
    val extras: Map<String, String>? = null
)

data class ApiResponse(
    val success: Boolean,
    val data: Any? = null,
    val error: String? = null
)

/**
 * STT request models
 */
data class SttTranscribeRequest(
    val locale: String? = null,
    val mode: String? = null  // "advanced" or "basic"
)