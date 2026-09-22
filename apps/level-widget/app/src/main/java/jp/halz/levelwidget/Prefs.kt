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

    /** Last line shown on the widget. */
    var status: String
        get() = prefs.getString(KEY_STATUS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STATUS, value).apply()

    fun isConfigured(action: LockAction): Boolean = targetPackage != null && matcher(action) != null

    private companion object {
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_LEARNING = "learning"
        const val KEY_STATUS = "status"
    }
}
