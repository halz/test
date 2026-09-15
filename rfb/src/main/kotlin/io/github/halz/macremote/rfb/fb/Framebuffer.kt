package io.github.halz.macremote.rfb.fb

/** A rectangle in framebuffer coordinates. */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The remote screen as ARGB_8888 ints, row-major. Pure JVM — the Android layer
 * copies dirty rects into a Bitmap via setPixels.
 */
class Framebuffer(width: Int, height: Int) {
    var width: Int = width
        private set
    var height: Int = height
        private set
    var pixels: IntArray = IntArray(width * height)
        private set

    fun resize(newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight
        pixels = IntArray(newWidth * newHeight)
    }

    fun copyRect(srcX: Int, srcY: Int, dst: Rect) {
        // Regions may overlap: copy via a temporary buffer, row by row.
        val temp = IntArray(dst.width * dst.height)
        for (row in 0 until dst.height) {
            System.arraycopy(pixels, (srcY + row) * width + srcX, temp, row * dst.width, dst.width)
        }
        for (row in 0 until dst.height) {
            System.arraycopy(temp, row * dst.width, pixels, (dst.y + row) * width + dst.x, dst.width)
        }
    }
}
