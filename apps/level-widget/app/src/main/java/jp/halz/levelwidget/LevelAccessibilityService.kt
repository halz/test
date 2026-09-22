package jp.halz.levelwidget

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
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
    private var picker: PickerOverlay? = null

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
        hidePicker()
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
            holdMillis = 0L,
        )
        if (matcher.viewId == null && matcher.contentDescription == null &&
            matcher.text == null && matcher.tapX < 0
        ) {
            toast(getString(R.string.learn_failed))
            return
        }
        prefs.setMatcher(action, matcher)
        prefs.learning = null
        toast(getString(R.string.learn_recorded, getString(action.labelRes), matcher.describe(), pressLabel(matcher)))
        LockWidgetProvider.refresh(this)
    }

    /**
     * Puts a window over the Level app so the user can point at a button that reports no
     * accessibility events. It starts as a banner at the top so the Level app stays usable; the
     * "start" button then expands it to full screen to capture the next press.
     */
    private fun showPicker(action: LockAction) {
        hidePicker()
        val windows = getSystemService(WindowManager::class.java) ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_picker, null) as PickerOverlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        val label = getString(action.labelRes)
        val text = view.findViewById<TextView>(R.id.picker_text)
        val start = view.findViewById<Button>(R.id.picker_start)
        text.text = getString(R.string.picker_waiting, label)
        start.setOnClickListener {
            view.arm()
            start.visibility = View.GONE
            text.text = getString(R.string.picker_armed, label)
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            windows.updateViewLayout(view, params)
        }
        view.findViewById<Button>(R.id.picker_cancel).setOnClickListener { hidePicker() }
        view.onPicked = { x, y, held -> recordPosition(action, x, y, held) }

        windows.addView(view, params)
        picker = view
    }

    private fun hidePicker() {
        val view = picker ?: return
        picker = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    private fun recordPosition(action: LockAction, x: Int, y: Int, heldMillis: Long) {
        val longPress = heldMillis >= LONG_PRESS_MS
        val matcher = NodeMatcher(
            viewId = null,
            contentDescription = null,
            text = null,
            className = null,
            tapX = x,
            tapY = y,
            longPress = longPress,
            holdMillis = if (longPress) heldMillis.coerceIn(LONG_PRESS_MS, MAX_HOLD_MS) else 0L,
        )
        prefs.setMatcher(action, matcher)
        prefs.learning = null
        hidePicker()
        toast(getString(R.string.learn_recorded, getString(action.labelRes), matcher.describe(), pressLabel(matcher)))
        LockWidgetProvider.refresh(this)
    }

    private fun pressLabel(matcher: NodeMatcher): String = when {
        !matcher.longPress -> getString(R.string.press_tap)
        matcher.holdMillis > 0 -> getString(R.string.press_long_seconds, seconds(matcher.holdMillis))
        else -> getString(R.string.press_long)
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
        val duration = when {
            !matcher.longPress -> TAP_DURATION_MS
            matcher.holdMillis > 0 -> matcher.holdMillis
            else -> DEFAULT_HOLD_MS
        }
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
        private const val LONG_PRESS_MS = 400L
        private const val MAX_HOLD_MS = 10_000L
        private const val DEFAULT_HOLD_MS = 1_500L
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

        /** Starts the "point at the button" flow from the setup screen. */
        fun startPicking(action: LockAction): Boolean {
            val service = instance ?: return false
            service.handler.post { service.showPicker(action) }
            return true
        }

        fun seconds(millis: Long): String =
            String.format(Locale.getDefault(), "%.1f", millis / 1000.0)
    }
}
