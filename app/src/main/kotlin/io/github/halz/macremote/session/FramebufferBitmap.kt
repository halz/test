package io.github.halz.macremote.session

import android.graphics.Bitmap
import io.github.halz.macremote.rfb.fb.Framebuffer
import io.github.halz.macremote.rfb.fb.Rect

/**
 * Mirrors the RFB framebuffer into an Android Bitmap. Dirty rects are copied
 * with setPixels from the session's IO coroutine; the UI draws the same Bitmap
 * concurrently. That race can show a torn frame for a moment, which is the
 * usual trade-off VNC viewers make to keep copies off the main thread.
 */
class FramebufferBitmap(private val framebuffer: Framebuffer) {

    var bitmap: Bitmap = create()
        private set

    private fun create(): Bitmap =
        Bitmap.createBitmap(framebuffer.width, framebuffer.height, Bitmap.Config.ARGB_8888)

    /** Call after the framebuffer resized (DesktopSize). */
    fun onResize() {
        bitmap = create()
    }

    fun apply(rects: List<Rect>) {
        val b = bitmap
        if (b.width != framebuffer.width || b.height != framebuffer.height) return
        for (rect in rects) {
            b.setPixels(
                framebuffer.pixels,
                rect.y * framebuffer.width + rect.x,
                framebuffer.width,
                rect.x, rect.y, rect.width, rect.height,
            )
        }
    }
}
