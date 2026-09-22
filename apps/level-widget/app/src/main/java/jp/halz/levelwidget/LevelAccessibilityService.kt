package jp.halz.levelwidget

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Presses the Level app on the user's behalf.
 *
 * The Level app draws its lock button itself and reports nothing to accessibility, so both halves
 * work on screen positions: the user points at the button through an overlay, and playback puts a
 * finger on that spot for as long as the user held it.
 */
class LevelAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }

    private var pendingLockId: String? = null
    private var pendingDeadline = 0L
    private var picker: View? = null
    private var progress: View? = null

    private val retry = object : Runnable {
        override fun run() {
            val lock = prefs.lock(pendingLockId) ?: return
            if (tryPress(lock)) return
            if (SystemClock.uptimeMillis() > pendingDeadline) {
                complete(lock.id, getString(R.string.status_no_app))
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
        hideProgress()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The screen changed, so the Level app may have finished coming to the front.
        if (pendingLockId != null) {
            handler.removeCallbacks(retry)
            handler.post(retry)
        }
    }

    // --- running a lock ------------------------------------------------------------------

    /** Queued once the Level app has been asked to come to the foreground. */
    fun enqueue(lock: Lock) {
        handler.removeCallbacks(retry)
        pendingLockId = lock.id
        pendingDeadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        prefs.setStatus(lock.id, getString(R.string.status_running))
        showProgress(lock)
        LockWidgetProvider.refresh(this)
        handler.postDelayed(retry, RETRY_INTERVAL_MS)
    }

    private fun tryPress(lock: Lock): Boolean {
        val press = lock.press ?: run {
            complete(lock.id, getString(R.string.status_not_configured))
            return true
        }
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != prefs.targetPackage) return false

        val path = Path().apply { moveTo(press.x.toFloat(), press.y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, press.holdMillis)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) =
                complete(lock.id, getString(R.string.status_done, time()))

            override fun onCancelled(description: GestureDescription?) =
                complete(lock.id, getString(R.string.status_cancelled))
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, handler)) {
            return false
        }
        // The press finishes later; the callback reports the outcome.
        handler.removeCallbacks(retry)
        pendingLockId = null
        return true
    }

    private fun complete(lockId: String, status: String) {
        handler.removeCallbacks(retry)
        pendingLockId = null
        hideProgress()
        prefs.setStatus(lockId, status)
        LockWidgetProvider.refresh(this)
    }

    private fun time(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    // --- overlays ------------------------------------------------------------------------

    /** Shows what is happening on top of the Level app, which is in front while the press runs. */
    private fun showProgress(lock: Lock) {
        hideProgress()
        val windows = getSystemService(WindowManager::class.java) ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_progress, null)
        view.findViewById<TextView>(R.id.progress_text).text =
            getString(R.string.overlay_running, lock.name)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Untouchable, so the press we are about to inject is not swallowed by this banner.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        runCatching { windows.addView(view, params) }.onSuccess { progress = view }
    }

    private fun hideProgress() {
        val view = progress ?: return
        progress = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    /**
     * Puts a window over the Level app so the user can point at its lock button. It starts as a
     * banner at the top so the Level app stays usable; "start" then expands it to full screen to
     * capture the next press, which is measured as well as located.
     */
    private fun showPicker(lock: Lock) {
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

        val text = view.findViewById<TextView>(R.id.picker_text)
        val start = view.findViewById<Button>(R.id.picker_start)
        text.text = getString(R.string.picker_waiting, lock.name)
        start.setOnClickListener {
            view.arm()
            start.visibility = View.GONE
            text.text = getString(R.string.picker_armed, lock.name)
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            runCatching { windows.updateViewLayout(view, params) }
        }
        view.findViewById<Button>(R.id.picker_cancel).setOnClickListener { hidePicker() }
        view.onPicked = { x, y, held -> recordPress(lock, x, y, held) }

        runCatching { windows.addView(view, params) }.onSuccess { picker = view }
    }

    private fun hidePicker() {
        val view = picker ?: return
        picker = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    private fun recordPress(lock: Lock, x: Int, y: Int, heldMillis: Long) {
        val press = ButtonPress.of(x, y, heldMillis)
        prefs.save(lock.copy(press = press))
        hidePicker()
        handler.post {
            Toast.makeText(
                this,
                getString(R.string.learn_recorded, lock.name, press.describe(), seconds(press.holdMillis)),
                Toast.LENGTH_LONG,
            ).show()
        }
        LockWidgetProvider.refresh(this)
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 400L
        private const val TIMEOUT_MS = 15_000L

        @Volatile
        private var instance: LevelAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun request(lock: Lock): Boolean {
            val service = instance ?: return false
            service.handler.post { service.enqueue(lock) }
            return true
        }

        /** Starts the "point at the button" flow from the setup screen. */
        fun startPicking(lock: Lock): Boolean {
            val service = instance ?: return false
            service.handler.post { service.showPicker(lock) }
            return true
        }

        fun seconds(millis: Long): String =
            String.format(Locale.getDefault(), "%.1f", millis / 1000.0)
    }
}
