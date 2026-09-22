package jp.halz.levelwidget

/** The two things the widget can ask the Level app to do. */
enum class LockAction(val prefKey: String, val labelRes: Int) {
    LOCK("lock", R.string.action_lock),
    UNLOCK("unlock", R.string.action_unlock);

    companion object {
        fun fromName(name: String?): LockAction? = values().firstOrNull { it.name == name }
    }
}
