package io.github.halz.macremote.rfb.protocol

import io.github.halz.macremote.rfb.RfbProtocolException
import io.github.halz.macremote.rfb.transport.RfbSocket

/**
 * RFB version handshake. macOS Screen Sharing and x11vnc both speak 3.8.
 * 3.7 is accepted (its security negotiation differs only in SecurityResult
 * details); 3.3 is rejected to keep negotiation simple.
 */
object ProtocolVersion {
    const val MAJOR = 3
    const val MAX_MINOR = 8

    /** Performs the version exchange and returns the agreed minor version (7 or 8). */
    fun handshake(socket: RfbSocket): Int {
        val banner = String(socket.readBytes(12), Charsets.US_ASCII)
        val match = Regex("""RFB (\d{3})\.(\d{3})\n""").matchEntire(banner)
            ?: throw RfbProtocolException("not an RFB server (got ${banner.trim()})")
        val serverMajor = match.groupValues[1].toInt()
        val serverMinor = match.groupValues[2].toInt()
        if (serverMajor != MAJOR || serverMinor < 7) {
            throw RfbProtocolException("unsupported RFB version $serverMajor.$serverMinor (need 3.7+)")
        }
        val minor = minOf(serverMinor, MAX_MINOR)
        socket.writeBytes("RFB 00$MAJOR.00$minor\n".toByteArray(Charsets.US_ASCII))
        socket.flush()
        return minor
    }
}
