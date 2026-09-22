package jp.halz.levelwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Home screen widget: one row per registered lock. */
class LockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // A widget update is also a good moment to make sure the background scan is still armed.
        Ble.start(context)
        manager.updateAppWidget(ids, buildViews(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            Ble.start(context)
            refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_REFRESH = "jp.halz.levelwidget.REFRESH"

        /** Locks whose Bluetooth device has not been heard from are drawn faded. */
        private const val OUT_OF_RANGE_ALPHA = 0.4f

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LockWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lock)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))
            views.removeAllViews(R.id.widget_rows)

            val prefs = Prefs(context)
            val locks = prefs.locks
            if (locks.isEmpty()) {
                views.addView(R.id.widget_rows, emptyRow(context))
                return views
            }
            locks.forEach { views.addView(R.id.widget_rows, row(context, prefs, it)) }
            return views
        }

        private fun row(context: Context, prefs: Prefs, lock: Lock): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            row.setTextViewText(R.id.row_name, lock.name)

            val status = prefs.status(lock.id)
            val running = status == context.getString(R.string.status_running)
            row.setTextViewText(
                R.id.row_status,
                when {
                    !lock.isReady -> context.getString(R.string.status_not_configured)
                    status.isEmpty() -> context.getString(R.string.status_idle)
                    else -> status
                },
            )
            row.setViewVisibility(R.id.row_progress, if (running) View.VISIBLE else View.GONE)

            // Unknown reachability is drawn as normal: only a lock we know is out of range fades.
            val reachable = Ble.reachable(prefs, lock, context)
            row.setFloat(R.id.row_body, "setAlpha", if (reachable == false) OUT_OF_RANGE_ALPHA else 1f)

            row.setOnClickPendingIntent(R.id.row_body, holdIntent(context, lock))
            return row
        }

        private fun emptyRow(context: Context): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            row.setTextViewText(R.id.row_name, context.getString(R.string.widget_empty))
            row.setTextViewText(R.id.row_status, context.getString(R.string.widget_empty_hint))
            row.setViewVisibility(R.id.row_progress, View.GONE)
            row.setOnClickPendingIntent(
                R.id.row_body,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return row
        }

        private fun holdIntent(context: Context, lock: Lock): PendingIntent =
            PendingIntent.getActivity(
                context,
                lock.id.hashCode(),
                HoldActivity.intent(context, lock),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, LockWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
