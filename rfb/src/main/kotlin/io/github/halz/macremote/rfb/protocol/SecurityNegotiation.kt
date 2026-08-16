package io.github.halz.macremote.rfb.protocol

import io.github.halz.macremote.rfb.RfbAuthException
import io.github.halz.macremote.rfb.RfbProtocolException
import io.github.halz.macremote.rfb.auth.ArdAuth
import io.github.halz.macremote.rfb.auth.VncAuth
import io.github.halz.macremote.rfb.transport.RfbSocket

object SecurityType {
    const val NONE = 1
    const val VNC_AUTH = 2
    const val ARD_AUTH = 30
}

/**
 * RFB 3.7/3.8 security negotiation + authentication.
 *
 * Type selection: a non-empty username signals intent to log in with a macOS
 * account (ARD, type 30), which is what stock macOS Screen Sharing offers.
 * Otherwise classic VNC password auth (type 2, enabled on the Mac via
 * "VNC viewers may control screen with password") is preferred.
 */
object SecurityNegotiation {

    fun authenticate(socket: RfbSocket, minorVersion: Int, username: String, password: String) {
        val count = socket.readU8()
        if (count == 0) {
            throw RfbProtocolException("server refused connection: ${socket.readString()}")
        }
        val offered = List(count) { socket.readU8() }

        val chosen = when {
            username.isNotEmpty() && SecurityType.ARD_AUTH in offered -> SecurityType.ARD_AUTH
            SecurityType.VNC_AUTH in offered && password.isNotEmpty() -> SecurityType.VNC_AUTH
            SecurityType.NONE in offered -> SecurityType.NONE
            SecurityType.ARD_AUTH in offered -> SecurityType.ARD_AUTH
            SecurityType.VNC_AUTH in offered -> SecurityType.VNC_AUTH
            else -> throw RfbProtocolException(
                "no supported security type (server offered $offered; supported: None, VNC, Apple ARD)"
            )
        }
        socket.writeU8(chosen)
        socket.flush()

        when (chosen) {
            SecurityType.NONE -> {
                // 3.8 sends SecurityResult even for None; 3.7 does not.
                if (minorVersion >= 8) readSecurityResult(socket, minorVersion)
                return
            }
            SecurityType.VNC_AUTH -> {
                val challenge = socket.readBytes(16)
                socket.writeBytes(VncAuth.encryptChallenge(password, challenge))
                socket.flush()
            }
            SecurityType.ARD_AUTH -> {
                if (username.isEmpty()) {
                    throw RfbAuthException(
                        "this Mac requires login with a username and password (Apple Remote Desktop auth)"
                    )
                }
                val response = ArdAuth.respond(socket, username, password)
                socket.writeBytes(response)
                socket.flush()
            }
        }
        readSecurityResult(socket, minorVersion)
    }

    private fun readSecurityResult(socket: RfbSocket, minorVersion: Int) {
        val result = socket.readU32()
        if (result != 0L) {
            val reason = if (minorVersion >= 8) {
                runCatching { socket.readString() }.getOrDefault("authentication failed")
            } else {
                "authentication failed"
            }
            throw RfbAuthException(reason)
        }
    }
}
