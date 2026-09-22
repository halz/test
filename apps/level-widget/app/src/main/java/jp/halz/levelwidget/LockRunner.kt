package jp.halz.levelwidget

import android.content.Context
import android.content.Intent

/** Brings the Level app to the front and asks the accessibility service to press the button. */
object LockRunner {

    /** Returns null on success, or a message explaining what is still missing. */
    fun run(context: Context, action: LockAction): String? {
        val prefs = Prefs(context)
        val target = prefs.targetPackage
        if (target == null || prefs.matcher(action) == null) {
            return context.getString(R.string.status_not_configured)
        }
        if (!LevelAccessibilityService.isRunning()) {
            return context.getString(R.string.status_service_off)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(target)
            ?: return context.getString(R.string.status_app_missing)
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        LevelAccessibilityService.request(action)
        return null
    }
}
