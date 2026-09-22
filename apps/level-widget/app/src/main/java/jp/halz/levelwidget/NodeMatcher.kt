package jp.halz.levelwidget

import org.json.JSONObject

/**
 * How to find the Level app's lock / unlock button again after the user pointed at it once.
 *
 * The identifiers are recorded from the accessibility event of the real press, so we do not need to
 * know anything about the Level app up front. [tapX] / [tapY] are the centre of the button at
 * recording time and are only used when none of the identifiers match any node on screen.
 * [longPress] mirrors how the user pressed it while recording, so playback presses it the same way.
 */
data class NodeMatcher(
    val viewId: String?,
    val contentDescription: String?,
    val text: String?,
    val className: String?,
    val tapX: Int,
    val tapY: Int,
    val longPress: Boolean,
) {
    /**
     * How well a node on screen matches this recording: higher is better, -1 means no match.
     * A view id is the most stable identifier, a label the least (it changes with the lock state).
     */
    fun score(viewId: String?, contentDescription: String?, text: String?): Int = when {
        this.viewId != null && this.viewId == viewId -> 3
        this.contentDescription != null && this.contentDescription == contentDescription -> 2
        this.text != null && this.text == text -> 1
        else -> -1
    }

    /** What the setup screen shows so the user can tell whether the right button was recorded. */
    fun describe(): String =
        contentDescription ?: text ?: viewId?.substringAfterLast('/') ?: "($tapX, $tapY)"

    fun toJson(): String = JSONObject()
        .put("viewId", viewId ?: JSONObject.NULL)
        .put("contentDescription", contentDescription ?: JSONObject.NULL)
        .put("text", text ?: JSONObject.NULL)
        .put("className", className ?: JSONObject.NULL)
        .put("tapX", tapX)
        .put("tapY", tapY)
        .put("longPress", longPress)
        .toString()

    companion object {
        fun fromJson(raw: String?): NodeMatcher? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val json = JSONObject(raw)
                NodeMatcher(
                    viewId = json.optStringOrNull("viewId"),
                    contentDescription = json.optStringOrNull("contentDescription"),
                    text = json.optStringOrNull("text"),
                    className = json.optStringOrNull("className"),
                    tapX = json.optInt("tapX", -1),
                    tapY = json.optInt("tapY", -1),
                    longPress = json.optBoolean("longPress", false),
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}
