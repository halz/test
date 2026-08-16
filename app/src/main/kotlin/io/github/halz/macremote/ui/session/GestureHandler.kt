package io.github.halz.macremote.ui.session

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import io.github.halz.macremote.rfb.messages.PointerButtons
import kotlin.math.abs

/**
 * Touch model ("direct touch + pan", Screens-like):
 *  - one-finger tap          -> left click at the touched point
 *  - one-finger drag         -> pan the viewport (nothing sent to the Mac)
 *  - double-tap then drag    -> left-button drag (text selection, window move)
 *  - long-press (no move)    -> right click
 *  - two-finger vertical drag-> scroll wheel
 *  - two-finger pinch        -> zoom (centroid-anchored) + pan
 */
fun Modifier.vncGestures(
    transform: CanvasTransform,
    onPointer: (buttonMask: Int, remote: Offset) -> Unit,
): Modifier = pointerInput(Unit) {
    var lastTapUptime = 0L
    var lastTapPosition = Offset.Zero

    awaitEachGesture {
        val down = awaitFirstDown()
        val slop = viewConfiguration.touchSlop
        val doubleTapWindow = viewConfiguration.doubleTapTimeoutMillis
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis

        val isDoubleTapDrag = down.uptimeMillis - lastTapUptime <= doubleTapWindow &&
            (down.position - lastTapPosition).getDistance() <= slop * 4
        var mode: GestureMode = if (isDoubleTapDrag) GestureMode.LeftDrag else GestureMode.Pending
        if (mode == GestureMode.LeftDrag) {
            val remote = transform.toRemote(down.position)
            onPointer(0, remote)
            onPointer(PointerButtons.LEFT, remote)
        }

        var lastSinglePosition = down.position
        var lastEventUptime = down.uptimeMillis
        var twoFingerDecided = false
        var twoFingerZoom = false
        var lastCentroid = Offset.Zero
        var lastSpan = 0f
        var scrollAccumulator = 0f
        val scrollStepPx = 24 * density

        while (true) {
            val event: PointerEvent? = if (mode == GestureMode.Pending) {
                val remaining = longPressTimeout - (lastEventUptime - down.uptimeMillis)
                withTimeoutOrNull(remaining.coerceAtLeast(1)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                // Long press with no movement: right click.
                val remote = transform.toRemote(down.position)
                onPointer(0, remote)
                onPointer(PointerButtons.RIGHT, remote)
                onPointer(0, remote)
                mode = GestureMode.Consumed
                continue
            }
            lastEventUptime = event.changes.maxOf { it.uptimeMillis }
            val pressed = event.changes.filter { it.pressed }

            if (pressed.isEmpty()) {
                when (mode) {
                    GestureMode.Pending -> {
                        // Tap: click where the finger went down.
                        val remote = transform.toRemote(down.position)
                        onPointer(0, remote)
                        onPointer(PointerButtons.LEFT, remote)
                        onPointer(0, remote)
                        lastTapUptime = lastEventUptime
                        lastTapPosition = down.position
                    }
                    GestureMode.LeftDrag -> {
                        onPointer(0, transform.toRemote(lastSinglePosition))
                        // Counts as a tap for double-tap chains (triple-click selects lines).
                        lastTapUptime = lastEventUptime
                        lastTapPosition = lastSinglePosition
                    }
                    else -> Unit
                }
                event.changes.forEach { it.consume() }
                break
            }

            if (pressed.size >= 2 && mode != GestureMode.LeftDrag) {
                val centroid = Offset(
                    pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size,
                    pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size,
                )
                val span = (pressed[0].position - pressed[1].position).getDistance()
                if (mode != GestureMode.TwoFinger) {
                    mode = GestureMode.TwoFinger
                    twoFingerDecided = false
                    lastCentroid = centroid
                    lastSpan = span
                    scrollAccumulator = 0f
                } else {
                    if (!twoFingerDecided) {
                        when {
                            abs(span - lastSpan) > slop -> { twoFingerDecided = true; twoFingerZoom = true }
                            (centroid - lastCentroid).getDistance() > slop -> { twoFingerDecided = true; twoFingerZoom = false }
                        }
                    }
                    if (twoFingerDecided) {
                        if (twoFingerZoom) {
                            if (lastSpan > 0f && span > 0f) transform.zoomBy(span / lastSpan, centroid)
                            transform.panBy(centroid - lastCentroid)
                        } else {
                            scrollAccumulator += centroid.y - lastCentroid.y
                            val remote = transform.toRemote(centroid)
                            while (abs(scrollAccumulator) >= scrollStepPx) {
                                // Fingers moving down reveal earlier content = wheel up.
                                val button = if (scrollAccumulator > 0) PointerButtons.WHEEL_UP else PointerButtons.WHEEL_DOWN
                                onPointer(button, remote)
                                onPointer(0, remote)
                                scrollAccumulator -= if (scrollAccumulator > 0) scrollStepPx else -scrollStepPx
                            }
                        }
                    }
                    lastCentroid = centroid
                    lastSpan = span
                }
                event.changes.forEach { it.consume() }
                continue
            }

            // Back to (or still) a single pointer.
            val change = pressed[0]
            when (mode) {
                GestureMode.Pending -> {
                    if ((change.position - down.position).getDistance() > slop) {
                        mode = GestureMode.Pan
                        lastSinglePosition = change.position
                    }
                }
                GestureMode.Pan -> {
                    transform.panBy(change.position - lastSinglePosition)
                    lastSinglePosition = change.position
                }
                GestureMode.LeftDrag -> {
                    lastSinglePosition = change.position
                    onPointer(PointerButtons.LEFT, transform.toRemote(change.position))
                }
                GestureMode.TwoFinger -> {
                    // A finger lifted mid-two-finger gesture; stop interpreting.
                    mode = GestureMode.Consumed
                }
                GestureMode.Consumed -> Unit
            }
            event.changes.forEach { it.consume() }
        }
    }
}

private enum class GestureMode { Pending, Pan, LeftDrag, TwoFinger, Consumed }
