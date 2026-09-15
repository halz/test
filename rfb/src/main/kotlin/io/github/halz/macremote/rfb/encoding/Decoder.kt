package io.github.halz.macremote.rfb.encoding

import io.github.halz.macremote.rfb.fb.Framebuffer
import io.github.halz.macremote.rfb.fb.Rect
import io.github.halz.macremote.rfb.transport.RfbSocket

object Encodings {
    const val RAW = 0
    const val COPY_RECT = 1
    const val ZLIB = 6
    const val ZRLE = 16
    const val PSEUDO_DESKTOP_SIZE = -223
}

/**
 * Decodes one rectangle's payload into the framebuffer. Implementations may
 * keep per-connection state (e.g. the Zlib stream) and are not thread-safe;
 * the single reader loop is the only caller.
 */
interface Decoder {
    fun decode(socket: RfbSocket, rect: Rect, framebuffer: Framebuffer)
}

/**
 * Shared pixel conversion: wire bytes in our requested format (32bpp little
 * endian, shifts 16/8/0 → [b, g, r, pad]) to opaque ARGB_8888 ints.
 */
internal fun bgrxToArgb(source: ByteArray, sourceOffset: Int, target: IntArray, targetOffset: Int, pixelCount: Int) {
    var src = sourceOffset
    var dst = targetOffset
    repeat(pixelCount) {
        val b = source[src].toInt() and 0xFF
        val g = source[src + 1].toInt() and 0xFF
        val r = source[src + 2].toInt() and 0xFF
        target[dst] = -0x1000000 or (r shl 16) or (g shl 8) or b
        src += 4
        dst += 1
    }
}
