package io.github.halz.macremote.rfb.protocol

import io.github.halz.macremote.rfb.transport.RfbSocket

/** The 16-byte PIXEL_FORMAT structure (RFC 6143 §7.4). */
data class PixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val bigEndian: Boolean,
    val trueColour: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int,
) {
    companion object {
        /**
         * The only format this client renders: 32bpp true colour, little endian,
         * R/G/B shifts 16/8/0. Wire bytes per pixel are then [b, g, r, pad], which maps
         * to ARGB_8888 ints with a fixed alpha OR (see decoders).
         */
        val CLIENT = PixelFormat(
            bitsPerPixel = 32, depth = 24, bigEndian = false, trueColour = true,
            redMax = 255, greenMax = 255, blueMax = 255,
            redShift = 16, greenShift = 8, blueShift = 0,
        )

        fun read(socket: RfbSocket): PixelFormat {
            val bpp = socket.readU8()
            val depth = socket.readU8()
            val bigEndian = socket.readU8() != 0
            val trueColour = socket.readU8() != 0
            val redMax = socket.readU16()
            val greenMax = socket.readU16()
            val blueMax = socket.readU16()
            val redShift = socket.readU8()
            val greenShift = socket.readU8()
            val blueShift = socket.readU8()
            socket.skip(3)
            return PixelFormat(bpp, depth, bigEndian, trueColour, redMax, greenMax, blueMax, redShift, greenShift, blueShift)
        }
    }

    fun write(socket: RfbSocket) {
        socket.writeU8(bitsPerPixel)
        socket.writeU8(depth)
        socket.writeU8(if (bigEndian) 1 else 0)
        socket.writeU8(if (trueColour) 1 else 0)
        socket.writeU16(redMax)
        socket.writeU16(greenMax)
        socket.writeU16(blueMax)
        socket.writeU8(redShift)
        socket.writeU8(greenShift)
        socket.writeU8(blueShift)
        socket.writeBytes(ByteArray(3))
    }
}
