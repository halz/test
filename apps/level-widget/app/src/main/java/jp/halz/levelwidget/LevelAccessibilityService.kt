package jp.halz.levelwidget

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the Level app on the user's behalf: records which node is the lock / unlock button and
 * how it was pressed, then presses it the same way when the widget asks for it.
 */
class LevelAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }

    private var pendingAction: LockAction? = null
    private var pendingDeadline = 0L

    private val retry = object : Runnable {
        override fun run() {
            val action = pendingAction ?: return
            if (tryPerform(action)) return
            if (SystemClock.uptimeMillis() > pendingDeadline) {
                complete(getString(R.string.status_not_found, getString(action.labelRes)))
                return
            }
            handler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(retry)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val target = prefs.targetPackage ?: return
        if (event.packageName?.toString() != target) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ) {
            val learning = prefs.learning
            if (learning != null) {
                record(learning, event)
                return
            }
        }
        // The screen changed, so the button we are waiting for may have appeared.
        pendingAction?.let { tryPerform(it) }
    }

    private fun record(action: LockAction, event: AccessibilityEvent) {
        val source = event.source
        val bounds = Rect(-1, -1, -1, -1)
        source?.getBoundsInScreen(bounds)
        val matcher = NodeMatcher(
            viewId = source?.viewIdResourceName,
            contentDescription = source?.contentDescription?.toString()
                ?: event.contentDescription?.toString(),
            text = source?.text?.toString() ?: event.text.firstOrNull()?.toString(),
            className = source?.className?.toString(),
            tapX = if (bounds.width() > 0) bounds.centerX() else -1,
            tapY = if (bounds.height() > 0) bounds.centerY() else -1,
            longPress = event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        )
        if (matcher.viewId == null && matcher.contentDescription == null &&
            matcher.text == null && matcher.tapX < 0
        ) {
            toast(getString(R.string.learn_failed))
            return
        }
        prefs.setMatcher(action, matcher)
        prefs.learning = null
        val how = getString(if (matcher.longPress) R.string.press_long else R.string.press_tap)
        toast(getString(R.string.learn_recorded, getString(action.labelRes), matcher.describe(), how))
        LockWidgetProvider.refresh(this)
    }

    /** Queued by the widget once the Level app has been brought to the foreground. */
    fun enqueue(action: LockAction) {
        handler.removeCallbacks(retry)
        pendingAction = action
        pendingDeadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        prefs.status = getString(R.string.status_running, getString(action.labelRes))
        LockWidgetProvider.refresh(this)
        handler.postDelayed(retry, RETRY_INTERVAL_MS)
    }

    private fun tryPerform(action: LockAction): Boolean {
        val matcher = prefs.matcher(action) ?: run {
            complete(getString(R.string.status_not_configured))
            return true
        }
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != prefs.targetPackage) return false

        val node = findBest(root, matcher)
        val result = if (node != null) press(node, matcher, action)
        else touch(matcher, matcher.tapX, matcher.tapY, action)
        return when (result) {
            Press.DONE -> {
                succeeded(action)
                true
            }
            // A held gesture finishes later; the dispatch callback reports the outcome.
            Press.DISPATCHED -> {
                handler.removeCallbacks(retry)
                pendingAction = null
                true
            }
            Press.FAILED -> false
        }
    }

    private fun succeeded(action: LockAction) {
        complete(
            getString(
                R.string.status_done,
                getString(action.labelRes),
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            )
        )
    }

    private fun complete(status: String) {
        handler.removeCallbacks(retry)
        pendingAction = null
        prefs.status = status
        LockWidgetProvider.refresh(this)
    }

    private fun findBest(root: AccessibilityNodeInfo, matcher: NodeMatcher): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val score = matcher.score(
                node.viewIdResourceName,
                node.contentDescription?.toString(),
                node.text?.toString(),
            )
            if (score > bestScore) {
                best = node
                bestScore = score
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private enum class Press { DONE, DISPATCHED, FAILED }

    /**
     * Presses the node the way it was recorded. The node that reports the press is not always the
     * one that handles it, so walk up until an ancestor accepts the action; if none does, the
     * button is a custom view that only reacts to a real touch, so put a finger on it instead.
     */
    private fun press(node: AccessibilityNodeInfo, matcher: NodeMatcher, action: LockAction): Press {
        val nodeAction = if (matcher.longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK
        else AccessibilityNodeInfo.ACTION_CLICK
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_PRESS_DEPTH) {
            val accepts = if (matcher.longPress) current.isLongClickable else current.isClickable
            if (accepts && current.performAction(nodeAction)) return Press.DONE
            current = current.parent
            depth++
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val onScreen = bounds.width() > 0 && bounds.height() > 0
        val x = if (onScreen) bounds.centerX() else matcher.tapX
        val y = if (onScreen) bounds.centerY() else matcher.tapY
        return touch(matcher, x, y, action)
    }

    /** A real touch on the screen: a quick tap, or held down for the configured time. */
    private fun touch(matcher: NodeMatcher, x: Int, y: Int, action: LockAction): Press {
        if (x < 0 || y < 0) return Press.FAILED
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = if (matcher.longPress) prefs.holdMillis else TAP_DURATION_MS
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) = succeeded(action)

            override fun onCancelled(description: GestureDescription?) =
                complete(getString(R.string.status_not_found, getString(action.labelRes)))
        }
        val dispatched = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            callback,
            handler,
        )
        return if (dispatched) Press.DISPATCHED else Press.FAILED
    }

    private fun toast(message: String) {
        handler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 500L
        private const val TIMEOUT_MS = 15_000L
        private const val TAP_DURATION_MS = 60L
        private const val MAX_NODES = 2_000
        private const val MAX_PRESS_DEPTH = 6

        @Volatile
        private var instance: LevelAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun request(action: LockAction): Boolean {
            val service = instance ?: return false
            service.enqueue(action)
            return true
        }
    }
}
