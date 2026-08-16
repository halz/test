package io.github.halz.macremote.rfb.encoding

import io.github.halz.macremote.rfb.RfbProtocolException
import io.github.halz.macremote.rfb.fb.Framebuffer
import io.github.halz.macremote.rfb.fb.Rect
import io.github.halz.macremote.rfb.transport.RfbSocket
import java.util.zip.Inflater

/**
 * Zlib encoding (6): a u32 length followed by zlib-compressed Raw pixel data.
 * One zlib stream spans the whole connection, so the Inflater must persist
 * across rectangles and must never be reset.
 */
class ZlibDecoder : Decoder {
    private val inflater = Inflater()
    private var compressed = ByteArray(0)
    private var expanded = ByteArray(0)

    override fun decode(socket: RfbSocket, rect: Rect, framebuffer: Framebuffer) {
        val compressedLength = socket.readU32().toInt()
        if (compressed.size < compressedLength) compressed = ByteArray(compressedLength)
        socket.readFully(compressed, 0, compressedLength)

        val expectedBytes = rect.width * rect.height * 4
        if (expanded.size < expectedBytes) expanded = ByteArray(expectedBytes)

        inflater.setInput(compressed, 0, compressedLength)
        var produced = 0
        while (produced < expectedBytes) {
            val n = inflater.inflate(expanded, produced, expectedBytes - produced)
            if (n == 0) {
                if (inflater.needsInput()) {
                    throw RfbProtocolException("zlib rect truncated: got $produced of $expectedBytes bytes")
                }
                if (inflater.needsDictionary()) {
                    throw RfbProtocolException("zlib stream requested a preset dictionary")
                }
            }
            produced += n
        }

        for (row in 0 until rect.height) {
            bgrxToArgb(
                expanded, row * rect.width * 4,
                framebuffer.pixels, (rect.y + row) * framebuffer.width + rect.x,
                rect.width,
            )
        }
    }
}
