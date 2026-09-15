package io.github.halz.macremote.rfb.transport

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Blocking TCP transport with the fixed-width big-endian read/write helpers the
 * RFB wire format is made of. Intended to be driven from Dispatchers.IO.
 */
class RfbSocket private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : Closeable {

    companion object {
        fun connect(host: String, port: Int, timeoutMillis: Int = 10_000): RfbSocket {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                return RfbSocket(
                    socket,
                    DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024)),
                    DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 8 * 1024)),
                )
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }
    }

    fun readU8(): Int = input.readUnsignedByte()

    fun readU16(): Int = input.readUnsignedShort()

    fun readU32(): Long = input.readInt().toLong() and 0xFFFFFFFFL

    fun readS32(): Int = input.readInt()

    fun readBytes(count: Int): ByteArray {
        val buffer = ByteArray(count)
        input.readFully(buffer)
        return buffer
    }

    fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        input.readFully(buffer, offset, length)
    }

    fun skip(count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped <= 0) throw EOFException("stream ended while skipping")
            remaining -= skipped
        }
    }

    /** Length-prefixed (u32) string, as used by SecurityResult reasons and ServerInit. */
    fun readString(): String {
        val length = readU32().toInt()
        require(length in 0..1_000_000) { "unreasonable string length $length" }
        return String(readBytes(length), Charsets.UTF_8)
    }

    fun writeU8(value: Int) = output.writeByte(value)

    fun writeU16(value: Int) = output.writeShort(value)

    fun writeU32(value: Long) = output.writeInt(value.toInt())

    fun writeS32(value: Int) = output.writeInt(value)

    fun writeBytes(bytes: ByteArray) = output.write(bytes)

    fun flush() = output.flush()

    override fun close() {
        runCatching { socket.close() }
    }
}
