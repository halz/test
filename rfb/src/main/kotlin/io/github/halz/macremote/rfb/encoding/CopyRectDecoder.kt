package io.github.halz.macremote.rfb.encoding

import io.github.halz.macremote.rfb.fb.Framebuffer
import io.github.halz.macremote.rfb.fb.Rect
import io.github.halz.macremote.rfb.transport.RfbSocket

class CopyRectDecoder : Decoder {
    override fun decode(socket: RfbSocket, rect: Rect, framebuffer: Framebuffer) {
        val srcX = socket.readU16()
        val srcY = socket.readU16()
        framebuffer.copyRect(srcX, srcY, rect)
    }
}
