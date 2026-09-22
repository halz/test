package jp.halz.levelwidget

import android.content.Context

/** Everything the app remembers: which app to drive, and the two recorded buttons. */
class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("level-widget", Context.MODE_PRIVATE)

    var targetPackage: String?
        get() = prefs.getString(KEY_TARGET_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGE, value).apply()

    fun matcher(action: LockAction): NodeMatcher? =
        NodeMatcher.fromJson(prefs.getString("matcher_${action.prefKey}", null))

    fun setMatcher(action: LockAction, matcher: NodeMatcher?) {
        prefs.edit().putString("matcher_${action.prefKey}", matcher?.toJson()).apply()
    }

    /** Set while the setup screen is waiting for the user to tap the real button in the Level app. */
    var learning: LockAction?
        get() = LockAction.fromName(prefs.getString(KEY_LEARNING, null))
        set(value) = prefs.edit().putString(KEY_LEARNING, value?.name).apply()

    /** How long a long press holds the button down. The Level app decides what is long enough. */
    var holdMillis: Long
        get() = prefs.getLong(KEY_HOLD_MILLIS, DEFAULT_HOLD_MILLIS)
        set(value) = prefs.edit().putLong(KEY_HOLD_MILLIS, value).apply()

    /** Last line shown on the widget. */
    var status: String
        get() = prefs.getString(KEY_STATUS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STATUS, value).apply()

    companion object {
        const val DEFAULT_HOLD_MILLIS = 1_500L

        private const val KEY_HOLD_MILLIS = "hold_millis"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_LEARNING = "learning"
        private const val KEY_STATUS = "status"
    }
}
