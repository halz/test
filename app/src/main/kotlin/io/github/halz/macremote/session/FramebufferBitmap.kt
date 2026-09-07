package io.github.halz.macremote.session

import android.graphics.Bitmap
import io.github.halz.macremote.rfb.fb.Framebuffer
import io.github.halz.macremote.rfb.fb.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Mirrors the RFB framebuffer into an Android Bitmap, optionally downsampled
 * by an integer [divisor] (nearest neighbour). Downsampling keeps the Bitmap
 * close to the physical screen in use — a Retina Mac at 2880x1800 shown on the
 * Fold's cover display doesn't need a 20MB full-resolution bitmap.
 *
 * Dirty rects are copied with setPixels from the session's IO coroutine; the
 * UI draws the same Bitmap concurrently. That race can show a torn frame for
 * a moment, which is the usual trade-off VNC viewers make to keep copies off
 * the main thread.
 */
class FramebufferBitmap(private val framebuffer: Framebuffer) {

    var divisor: Int = 1
        private set

    var bitmap: Bitmap = create()
        private set

    private var scaledBuffer = IntArray(0)

    private fun create(): Bitmap = Bitmap.createBitmap(
        max(1, framebuffer.width / divisor),
        max(1, framebuffer.height / divisor),
        Bitmap.Config.ARGB_8888,
    )

    /** Call after the framebuffer resized (DesktopSize). */
    fun onResize() {
        bitmap = create()
    }

    /** Changes the downsampling factor and repaints from the current framebuffer. */
    fun setDivisor(value: Int) {
        val clamped = value.coerceIn(1, 4)
        if (clamped == divisor) return
        divisor = clamped
        bitmap = create()
        repaintAll()
    }

    fun repaintAll() {
        apply(listOf(Rect(0, 0, framebuffer.width, framebuffer.height)))
    }

    fun apply(rects: List<Rect>) {
        val b = bitmap
        if (divisor == 1) {
            if (b.width != framebuffer.width || b.height != framebuffer.height) return
            for (rect in rects) {
                b.setPixels(
                    framebuffer.pixels,
                    rect.y * framebuffer.width + rect.x,
                    framebuffer.width,
                    rect.x, rect.y, rect.width, rect.height,
                )
            }
            return
        }
        if (b.width != framebuffer.width / divisor || b.height != framebuffer.height / divisor) return
        for (rect in rects) {
            val sx0 = rect.x / divisor
            val sy0 = rect.y / divisor
            val sx1 = min(b.width, (rect.x + rect.width + divisor - 1) / divisor)
            val sy1 = min(b.height, (rect.y + rect.height + divisor - 1) / divisor)
            val sw = sx1 - sx0
            val sh = sy1 - sy0
            if (sw <= 0 || sh <= 0) continue
            if (scaledBuffer.size < sw * sh) scaledBuffer = IntArray(sw * sh)
            var out = 0
            for (sy in sy0 until sy1) {
                val srcRow = min(sy * divisor, framebuffer.height - 1) * framebuffer.width
                for (sx in sx0 until sx1) {
                    scaledBuffer[out++] = framebuffer.pixels[srcRow + min(sx * divisor, framebuffer.width - 1)]
                }
            }
            b.setPixels(scaledBuffer, 0, sw, sx0, sy0, sw, sh)
        }
    }
}
