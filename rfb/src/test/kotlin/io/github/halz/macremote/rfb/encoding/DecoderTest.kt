package io.github.halz.macremote.rfb.encoding

import io.github.halz.macremote.rfb.fb.Framebuffer
import io.github.halz.macremote.rfb.fb.Rect
import io.github.halz.macremote.rfb.transport.RfbSocket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Constructor
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.Deflater

/**
 * Feeds decoders a byte stream through a real loopback socket (RfbSocket has no
 * public constructor from streams, so we bridge with an in-process TCP pair).
 */
class DecoderTest {

    private fun withSocket(payload: ByteArray, block: (RfbSocket) -> Unit) {
        ServerSocket(0).use { server ->
            val client = Socket("127.0.0.1", server.localPort)
            val accepted = server.accept()
            accepted.getOutputStream().write(payload)
            accepted.getOutputStream().flush()
            val rfb = rfbSocketFrom(client)
            try {
                block(rfb)
            } finally {
                rfb.close()
                accepted.close()
            }
        }
    }

    // RfbSocket.connect opens its own socket; for tests we reflectively use the
    // private constructor with streams from an already-connected socket.
    private fun rfbSocketFrom(socket: Socket): RfbSocket {
        val ctor: Constructor<RfbSocket> = RfbSocket::class.java.getDeclaredConstructor(
            Socket::class.java, java.io.DataInputStream::class.java, java.io.DataOutputStream::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(
            socket,
            java.io.DataInputStream(socket.getInputStream()),
            java.io.DataOutputStream(socket.getOutputStream()),
        )
    }

    private fun bgrxPixel(r: Int, g: Int, b: Int): ByteArray = byteArrayOf(b.toByte(), g.toByte(), r.toByte(), 0)

    @Test
    fun `raw decoder converts BGRx to opaque ARGB`() {
        // 2x1 rect: red then green.
        val payload = bgrxPixel(255, 0, 0) + bgrxPixel(0, 255, 0)
        val fb = Framebuffer(2, 1)
        withSocket(payload) { sock ->
            RawDecoder().decode(sock, Rect(0, 0, 2, 1), fb)
        }
        assertEquals(0xFFFF0000.toInt(), fb.pixels[0])
        assertEquals(0xFF00FF00.toInt(), fb.pixels[1])
    }

    @Test
    fun `zlib decoder inflates a raw payload`() {
        val fb = Framebuffer(2, 1)
        val raw = bgrxPixel(0, 0, 255) + bgrxPixel(255, 255, 255)
        val compressed = deflate(raw)
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, ((compressed.size shr 8) and 0xFF).toByte(), (compressed.size and 0xFF).toByte()))
            write(compressed)
        }.toByteArray()
        withSocket(payload) { sock ->
            ZlibDecoder().decode(sock, Rect(0, 0, 2, 1), fb)
        }
        assertEquals(0xFF0000FF.toInt(), fb.pixels[0])
        assertEquals(0xFFFFFFFF.toInt(), fb.pixels[1])
    }

    @Test
    fun `copyRect moves pixels within the framebuffer`() {
        val fb = Framebuffer(2, 2)
        fb.pixels[0] = 0xFF112233.toInt() // top-left
        // Copy the top-left pixel to the bottom-right (src 0,0 -> dst 1,1 1x1).
        val payload = byteArrayOf(0, 0, 0, 0) // srcX=0, srcY=0
        withSocket(payload) { sock ->
            CopyRectDecoder().decode(sock, Rect(1, 1, 1, 1), fb)
        }
        assertEquals(0xFF112233.toInt(), fb.pixels[3])
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }
}
