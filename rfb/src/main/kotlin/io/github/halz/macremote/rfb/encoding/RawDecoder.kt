package io.github.halz.macremote.rfb.encoding

import io.github.halz.macremote.rfb.fb.Framebuffer
import io.github.halz.macremote.rfb.fb.Rect
import io.github.halz.macremote.rfb.transport.RfbSocket

class RawDecoder : Decoder {
    private var rowBuffer = ByteArray(0)

    override fun decode(socket: RfbSocket, rect: Rect, framebuffer: Framebuffer) {
        val rowBytes = rect.width * 4
        if (rowBuffer.size < rowBytes) rowBuffer = ByteArray(rowBytes)
        for (row in 0 until rect.height) {
            socket.readFully(rowBuffer, 0, rowBytes)
            bgrxToArgb(rowBuffer, 0, framebuffer.pixels, (rect.y + row) * framebuffer.width + rect.x, rect.width)
        }
    }
}
