package jp.halz.levelwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home screen widget with a lock and an unlock button. */
class LockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildViews(context)
        manager.updateAppWidget(ids, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RUN) {
            LockAction.fromName(intent.getStringExtra(EXTRA_ACTION))?.let { run(context, it) }
            return
        }
        super.onReceive(context, intent)
    }

    private fun run(context: Context, action: LockAction) {
        val failure = LockRunner.run(context, action)
        if (failure != null) {
            Prefs(context).status = failure
            refresh(context)
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        private const val ACTION_RUN = "jp.halz.levelwidget.RUN"
        private const val EXTRA_ACTION = "action"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LockWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lock)
            views.setOnClickPendingIntent(R.id.widget_lock, pendingIntent(context, LockAction.LOCK))
            views.setOnClickPendingIntent(R.id.widget_unlock, pendingIntent(context, LockAction.UNLOCK))
            val status = Prefs(context).status
            views.setTextViewText(
                R.id.widget_status,
                status.ifEmpty { context.getString(R.string.status_idle) },
            )
            return views
        }

        private fun pendingIntent(context: Context, action: LockAction): PendingIntent {
            val intent = Intent(context, LockWidgetProvider::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_ACTION, action.name)
            return PendingIntent.getBroadcast(
                context,
                action.ordinal,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
