package io.github.halz.macremote.rfb.messages

import io.github.halz.macremote.rfb.protocol.PixelFormat
import io.github.halz.macremote.rfb.transport.RfbSocket

/** Pointer button bits for PointerEvent.buttonMask. */
object PointerButtons {
    const val LEFT = 1
    const val MIDDLE = 2
    const val RIGHT = 4
    const val WHEEL_UP = 8
    const val WHEEL_DOWN = 16
}

/** Encoders for all client→server messages (RFC 6143 §7.5). Callers serialize access. */
object ClientMessages {

    fun setPixelFormat(socket: RfbSocket, format: PixelFormat) {
        socket.writeU8(0)
        socket.writeBytes(ByteArray(3))
        format.write(socket)
        socket.flush()
    }

    fun setEncodings(socket: RfbSocket, encodings: List<Int>) {
        socket.writeU8(2)
        socket.writeU8(0)
        socket.writeU16(encodings.size)
        encodings.forEach { socket.writeS32(it) }
        socket.flush()
    }

    fun framebufferUpdateRequest(socket: RfbSocket, incremental: Boolean, x: Int, y: Int, width: Int, height: Int) {
        socket.writeU8(3)
        socket.writeU8(if (incremental) 1 else 0)
        socket.writeU16(x)
        socket.writeU16(y)
        socket.writeU16(width)
        socket.writeU16(height)
        socket.flush()
    }

    fun keyEvent(socket: RfbSocket, down: Boolean, keysym: Int) {
        socket.writeU8(4)
        socket.writeU8(if (down) 1 else 0)
        socket.writeU16(0)
        socket.writeU32(keysym.toLong() and 0xFFFFFFFFL)
        socket.flush()
    }

    fun pointerEvent(socket: RfbSocket, buttonMask: Int, x: Int, y: Int) {
        socket.writeU8(5)
        socket.writeU8(buttonMask)
        socket.writeU16(x.coerceIn(0, 0xFFFF))
        socket.writeU16(y.coerceIn(0, 0xFFFF))
        socket.flush()
    }

    /** Latin-1 only per RFC 6143; non-Latin-1 characters are replaced with '?'. */
    fun clientCutText(socket: RfbSocket, text: String) {
        val bytes = ByteArray(text.length) { i ->
            val c = text[i].code
            (if (c in 0..0xFF) c else '?'.code).toByte()
        }
        socket.writeU8(6)
        socket.writeBytes(ByteArray(3))
        socket.writeU32(bytes.size.toLong())
        socket.writeBytes(bytes)
        socket.flush()
    }
}
