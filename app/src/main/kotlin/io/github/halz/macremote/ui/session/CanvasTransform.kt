package io.github.halz.macremote.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Zoom/pan state mapping the remote framebuffer into the view.
 * view = remote * scale + offset. Shared by drawing and touch mapping so the
 * two can never disagree.
 */
class CanvasTransform {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)

    /** True once the user has pinch-zoomed; suppresses auto re-fit on size changes. */
    var userZoomed by mutableStateOf(false)

    var viewSize: IntSize = IntSize.Zero
    var remoteSize: IntSize = IntSize.Zero

    fun toRemote(view: Offset): Offset =
        Offset((view.x - offsetX) / scale, (view.y - offsetY) / scale)

    fun fit() {
        if (viewSize == IntSize.Zero || remoteSize == IntSize.Zero) return
        scale = minOf(
            viewSize.width.toFloat() / remoteSize.width,
            viewSize.height.toFloat() / remoteSize.height,
        )
        offsetX = (viewSize.width - remoteSize.width * scale) / 2f
        offsetY = (viewSize.height - remoteSize.height * scale) / 2f
        userZoomed = false
    }

    /**
     * Zoom until the view is completely covered (no letterbox), cropping the
     * remote screen's overflowing axis. Marked as a user zoom so fold/keyboard
     * size changes clamp instead of resetting back to fit.
     */
    fun fill() {
        if (viewSize == IntSize.Zero || remoteSize == IntSize.Zero) return
        scale = maxOf(
            viewSize.width.toFloat() / remoteSize.width,
            viewSize.height.toFloat() / remoteSize.height,
        )
        offsetX = (viewSize.width - remoteSize.width * scale) / 2f
        offsetY = (viewSize.height - remoteSize.height * scale) / 2f
        userZoomed = true
        clampOffset()
    }

    fun zoomBy(factor: Float, pivot: Offset) {
        val newScale = (scale * factor).coerceIn(minFitScale() * 0.5f, 8f)
        val applied = newScale / scale
        // Keep the content point under the pivot stationary.
        offsetX = pivot.x - (pivot.x - offsetX) * applied
        offsetY = pivot.y - (pivot.y - offsetY) * applied
        scale = newScale
        userZoomed = true
        clampOffset()
    }

    fun panBy(delta: Offset) {
        offsetX += delta.x
        offsetY += delta.y
        clampOffset()
    }

    private fun minFitScale(): Float {
        if (viewSize == IntSize.Zero || remoteSize == IntSize.Zero) return 0.05f
        return minOf(
            viewSize.width.toFloat() / remoteSize.width,
            viewSize.height.toFloat() / remoteSize.height,
        )
    }

    /** Content larger than the view may not leave gaps; smaller content is centered per axis. */
    fun clampOffset() {
        if (viewSize == IntSize.Zero || remoteSize == IntSize.Zero) return
        val contentW = remoteSize.width * scale
        val contentH = remoteSize.height * scale
        offsetX = if (contentW <= viewSize.width) {
            (viewSize.width - contentW) / 2f
        } else {
            offsetX.coerceIn(viewSize.width - contentW, 0f)
        }
        offsetY = if (contentH <= viewSize.height) {
            (viewSize.height - contentH) / 2f
        } else {
            offsetY.coerceIn(viewSize.height - contentH, 0f)
        }
    }
}
