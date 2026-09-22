package jp.halz.levelwidget

import org.json.JSONObject

/**
 * Where to press in the Level app and for how long, as measured by the picker overlay.
 *
 * The Level app draws its lock button itself and reports nothing to accessibility, so there is
 * nothing to match against the window content: the position is all we have.
 */
data class ButtonPress(val x: Int, val y: Int, val holdMillis: Long) {

    fun describe(): String = "($x, $y)"

    fun toJson(): JSONObject = JSONObject()
        .put("x", x)
        .put("y", y)
        .put("holdMillis", holdMillis)

    companion object {
        const val MIN_HOLD_MS = 60L
        const val MAX_HOLD_MS = 10_000L

        fun of(x: Int, y: Int, heldMillis: Long): ButtonPress =
            ButtonPress(x, y, heldMillis.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS))

        fun fromJson(json: JSONObject?): ButtonPress? {
            if (json == null) return null
            val x = json.optInt("x", -1)
            val y = json.optInt("y", -1)
            if (x < 0 || y < 0) return null
            return of(x, y, json.optLong("holdMillis", MIN_HOLD_MS))
        }
    }
}
