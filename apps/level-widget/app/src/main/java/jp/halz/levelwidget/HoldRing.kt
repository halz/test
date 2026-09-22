package jp.halz.levelwidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * A ring that fills while the finger stays down and unwinds when it is lifted early. A widget
 * button cannot be long pressed — the launcher takes that gesture — so this is where the hold that
 * guards the door lock happens.
 */
class HoldRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var holdMillis = 1_000L
    var onHoldComplete: (() -> Unit)? = null
    var onHoldStart: (() -> Unit)? = null
    var onHoldCancel: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        color = 0x33FFFFFF
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF2F6FED.toInt()
    }
    private val arc = RectF()

    private var progress = 0f
    private var holding = false
    private var holdStartedAt = 0L
    private var releasedAt = 0L
    private var releasedFrom = 0f
    private var fired = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = true
                fired = false
                holdStartedAt = SystemClock.uptimeMillis()
                onHoldStart?.invoke()
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (holding) {
                    holding = false
                    releasedFrom = progress
                    releasedAt = SystemClock.uptimeMillis()
                    if (!fired) onHoldCancel?.invoke()
                    postInvalidateOnAnimation()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        when {
            holding -> {
                progress = ((now - holdStartedAt).toFloat() / holdMillis).coerceAtMost(1f)
                if (progress >= 1f && !fired) {
                    fired = true
                    holding = false
                    post { onHoldComplete?.invoke() }
                } else {
                    postInvalidateOnAnimation()
                }
            }
            progress > 0f && !fired -> {
                val unwound = (now - releasedAt).toFloat() / UNWIND_MS
                progress = (releasedFrom * (1f - unwound)).coerceAtLeast(0f)
                if (progress > 0f) postInvalidateOnAnimation()
            }
        }

        val inset = fill.strokeWidth / 2f + 2f * density
        val radius = min(width, height) / 2f - inset
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, track)
        if (progress > 0f) {
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(arc, -90f, 360f * progress, false, fill)
        }
    }

    private companion object {
        const val UNWIND_MS = 220f
    }
}
