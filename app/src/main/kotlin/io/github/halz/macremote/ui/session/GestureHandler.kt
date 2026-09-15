package io.github.halz.macremote.ui.session

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import io.github.halz.macremote.data.GestureTrigger
import io.github.halz.macremote.rfb.messages.PointerButtons
import kotlin.math.abs

/**
 * Pointer targets differ per mode, so gestures delegate position decisions:
 *
 * Direct mode ("Screens"-like):
 *  - one-finger tap          -> left click at the touched point
 *  - one-finger drag         -> pan the viewport (nothing sent to the Mac)
 *  - double-tap then drag    -> left-button drag at the touched point
 *  - long-press (no move)    -> right click at the touched point
 *
 * Trackpad mode (laptop-like, relative):
 *  - one-finger drag         -> move the virtual cursor (hover)
 *  - tap                     -> left click at the cursor
 *  - double-tap then drag    -> left-button drag from the cursor
 *  - long-press (no move)    -> right click at the cursor
 *
 * Both modes:
 *  - two-finger vertical drag-> scroll wheel
 *  - two-finger pinch        -> zoom (centroid-anchored) + pan
 *  - two-finger tap and every three/four-finger swipe or tap -> the command
 *    bound to that [GestureTrigger] in settings, reported through [onGesture]
 *    along with the view-space centroid of the fingers that performed it
 */
interface PointerTarget {
    /** True when relative (trackpad) mode is active. */
    fun isTrackpad(): Boolean

    /** Server coordinates for an absolute touch position (direct mode). */
    fun resolveDirect(viewPosition: Offset): Offset

    /** Current virtual cursor in server coordinates (trackpad mode). */
    fun cursor(): Offset

    /** Applies a view-space delta to the cursor; returns the new server position. */
    fun moveCursorBy(viewDelta: Offset): Offset

    /** Sends a PointerEvent with [buttonMask] at [server] coordinates. */
    fun send(buttonMask: Int, server: Offset)
}

fun Modifier.vncGestures(
    transform: CanvasTransform,
    target: PointerTarget,
    onGesture: (GestureTrigger, Offset) -> Unit,
): Modifier = pointerInput(transform, target, onGesture) {
    var lastTapUptime = 0L
    var lastTapPosition = Offset.Zero

    awaitEachGesture {
        val down = awaitFirstDown()
        val slop = viewConfiguration.touchSlop
        val doubleTapWindow = viewConfiguration.doubleTapTimeoutMillis
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val trackpad = target.isTrackpad()
        // A multi-finger swipe has to clear more than the tap slop, or a
        // shaky three-finger tap would register as a swipe.
        val swipeThreshold = 32 * density

        val isDoubleTapDrag = down.uptimeMillis - lastTapUptime <= doubleTapWindow &&
            (down.position - lastTapPosition).getDistance() <= slop * 4
        var mode: GestureMode = if (isDoubleTapDrag) GestureMode.LeftDrag else GestureMode.Pending
        if (mode == GestureMode.LeftDrag) {
            val server = if (trackpad) target.cursor() else target.resolveDirect(down.position)
            target.send(0, server)
            target.send(PointerButtons.LEFT, server)
        }

        var lastSinglePosition = down.position
        var lastEventUptime = down.uptimeMillis
        var twoFingerDecided = false
        var twoFingerZoom = false
        var lastCentroid = Offset.Zero
        var lastSpan = 0f
        // Where the two fingers started: scroll-vs-zoom is decided on travel
        // since then, because a single frame of a normal-speed scroll moves
        // only a few pixels — far below the tap slop.
        var twoFingerStartCentroid = Offset.Zero
        var twoFingerStartSpan = 0f
        var scrollAccumulator = 0f
        val scrollStepPx = 8 * density
        // Multi-finger (3+) state. maxPointers is the peak count, since
        // fingers rarely land on the glass at the same instant.
        var maxPointers = 1
        var multiStartCentroid = Offset.Zero
        // Where the fingers were last seen, so a gesture bound to a click
        // acts on the spot the user actually touched.
        var gestureCentroid = down.position

        while (true) {
            val event: PointerEvent? = if (mode == GestureMode.Pending) {
                val remaining = longPressTimeout - (lastEventUptime - down.uptimeMillis)
                withTimeoutOrNull(remaining.coerceAtLeast(1)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                // Long press with no movement: right click.
                val server = if (trackpad) target.cursor() else target.resolveDirect(down.position)
                target.send(0, server)
                target.send(PointerButtons.RIGHT, server)
                target.send(0, server)
                mode = GestureMode.Consumed
                continue
            }
            lastEventUptime = event.changes.maxOf { it.uptimeMillis }
            val pressed = event.changes.filter { it.pressed }
            maxPointers = maxOf(maxPointers, pressed.size)

            if (pressed.isEmpty()) {
                when (mode) {
                    GestureMode.Pending -> {
                        // Tap: click at the cursor (trackpad) or touch point (direct).
                        val server = if (trackpad) target.cursor() else target.resolveDirect(down.position)
                        target.send(0, server)
                        target.send(PointerButtons.LEFT, server)
                        target.send(0, server)
                        lastTapUptime = lastEventUptime
                        lastTapPosition = down.position
                    }
                    GestureMode.LeftDrag -> {
                        val server = if (trackpad) target.cursor() else target.resolveDirect(lastSinglePosition)
                        target.send(0, server)
                        // Counts as a tap for double-tap chains (triple-click selects lines).
                        lastTapUptime = lastEventUptime
                        lastTapPosition = lastSinglePosition
                    }
                    GestureMode.TwoFinger -> {
                        // Lifted without scrolling or zooming: a two-finger tap.
                        if (!twoFingerDecided) onGesture(GestureTrigger.TWO_TAP, gestureCentroid)
                    }
                    GestureMode.Multi -> tapTrigger(maxPointers)?.let { onGesture(it, gestureCentroid) }
                    else -> Unit
                }
                event.changes.forEach { it.consume() }
                break
            }

            val centroid = Offset(
                pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size,
                pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size,
            )
            gestureCentroid = centroid

            if (pressed.size >= 3 && mode != GestureMode.LeftDrag) {
                if (mode != GestureMode.Multi) {
                    // A third finger cancels any two-finger interpretation.
                    mode = GestureMode.Multi
                    multiStartCentroid = centroid
                }
                val travel = centroid - multiStartCentroid
                if (travel.getDistance() > swipeThreshold) {
                    swipeTrigger(maxPointers, travel)?.let { onGesture(it, multiStartCentroid) }
                    mode = GestureMode.Consumed
                }
                event.changes.forEach { it.consume() }
                continue
            }

            if (pressed.size >= 2 && mode != GestureMode.LeftDrag && mode != GestureMode.Multi) {
                val span = (pressed[0].position - pressed[1].position).getDistance()
                if (mode != GestureMode.TwoFinger) {
                    mode = GestureMode.TwoFinger
                    twoFingerDecided = false
                    lastCentroid = centroid
                    lastSpan = span
                    twoFingerStartCentroid = centroid
                    twoFingerStartSpan = span
                    scrollAccumulator = 0f
                } else {
                    if (!twoFingerDecided) {
                        val spread = abs(span - twoFingerStartSpan)
                        val travel = (centroid - twoFingerStartCentroid).getDistance()
                        // Whichever passes the slop first wins; spreading the
                        // fingers also moves the centroid a little, so compare
                        // the two once either is past the threshold.
                        if (spread > slop || travel > slop) {
                            twoFingerDecided = true
                            twoFingerZoom = spread > travel
                        }
                    }
                    if (twoFingerDecided) {
                        if (twoFingerZoom) {
                            if (lastSpan > 0f && span > 0f) transform.zoomBy(span / lastSpan, centroid)
                            transform.panBy(centroid - lastCentroid)
                        } else {
                            scrollAccumulator += centroid.y - lastCentroid.y
                            val server = if (trackpad) target.cursor() else target.resolveDirect(centroid)
                            while (abs(scrollAccumulator) >= scrollStepPx) {
                                // Fingers moving down reveal earlier content = wheel up.
                                val button = if (scrollAccumulator > 0) PointerButtons.WHEEL_UP else PointerButtons.WHEEL_DOWN
                                target.send(button, server)
                                target.send(0, server)
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
                    val delta = change.position - lastSinglePosition
                    if (trackpad) {
                        // Hover-move the virtual cursor.
                        target.send(0, target.moveCursorBy(delta))
                    } else {
                        transform.panBy(delta)
                    }
                    lastSinglePosition = change.position
                }
                GestureMode.LeftDrag -> {
                    val delta = change.position - lastSinglePosition
                    lastSinglePosition = change.position
                    val server = if (trackpad) target.moveCursorBy(delta) else target.resolveDirect(change.position)
                    target.send(PointerButtons.LEFT, server)
                }
                // Fingers rarely leave the glass together, so a tap is decided
                // as soon as the count drops — not only when it reaches zero.
                GestureMode.TwoFinger -> {
                    if (!twoFingerDecided) onGesture(GestureTrigger.TWO_TAP, gestureCentroid)
                    mode = GestureMode.Consumed
                }
                GestureMode.Multi -> {
                    tapTrigger(maxPointers)?.let { onGesture(it, gestureCentroid) }
                    mode = GestureMode.Consumed
                }
                GestureMode.Consumed -> Unit
            }
            event.changes.forEach { it.consume() }
        }
    }
}

private fun tapTrigger(fingers: Int): GestureTrigger? = when {
    fingers >= 4 -> GestureTrigger.FOUR_TAP
    fingers == 3 -> GestureTrigger.THREE_TAP
    else -> null
}

private fun swipeTrigger(fingers: Int, travel: Offset): GestureTrigger? {
    val horizontal = abs(travel.x) > abs(travel.y)
    return if (fingers >= 4) {
        when {
            horizontal && travel.x < 0 -> GestureTrigger.FOUR_LEFT
            horizontal -> GestureTrigger.FOUR_RIGHT
            travel.y < 0 -> GestureTrigger.FOUR_UP
            else -> GestureTrigger.FOUR_DOWN
        }
    } else if (fingers == 3) {
        when {
            horizontal && travel.x < 0 -> GestureTrigger.THREE_LEFT
            horizontal -> GestureTrigger.THREE_RIGHT
            travel.y < 0 -> GestureTrigger.THREE_UP
            else -> GestureTrigger.THREE_DOWN
        }
    } else {
        null
    }
}

private enum class GestureMode { Pending, Pan, LeftDrag, TwoFinger, Multi, Consumed }
