package io.github.halz.macremote.rfb.protocol

import io.github.halz.macremote.rfb.transport.RfbSocket

data class ServerInit(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val name: String,
)

object InitMessages {
    /** ClientInit with shared=1 so an existing local session is not kicked. */
    fun clientInit(socket: RfbSocket) {
        socket.writeU8(1)
        socket.flush()
    }

    fun readServerInit(socket: RfbSocket): ServerInit {
        val width = socket.readU16()
        val height = socket.readU16()
        val pixelFormat = PixelFormat.read(socket)
        val name = socket.readString()
        return ServerInit(width, height, pixelFormat, name)
    }
}
