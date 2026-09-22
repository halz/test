package jp.halz.levelwidget

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Transparent window shown over the Level app so the user can point at a button that reports no
 * accessibility events. Until [arm] is called the window is only the banner at the top, so the
 * Level app can still be used normally to reach the right screen.
 *
 * The press is measured as well as located: how long the finger stays down is how long playback
 * will hold the button.
 */
class PickerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Called with the screen position of the press and how long it was held. */
    var onPicked: ((x: Int, y: Int, heldMillis: Long) -> Unit)? = null

    private var armed = false
    private var downAt = 0L
    private var downX = 0
    private var downY = 0

    fun arm() {
        armed = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!armed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis()
                downX = event.rawX.toInt()
                downY = event.rawY.toInt()
            }
            MotionEvent.ACTION_UP -> {
                armed = false
                onPicked?.invoke(downX, downY, SystemClock.uptimeMillis() - downAt)
            }
        }
        return true
    }
}
