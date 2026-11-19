package io.github.halz.macremote.data

import kotlinx.serialization.Serializable

/** What the remote computer is, which decides the protocol spoken to it. */
@Serializable
enum class Protocol(val label: String, val defaultPort: Int) {
    /** macOS Screen Sharing (VNC / RFB). */
    VNC("Mac (画面共有)", 5900),

    /** Windows Remote Desktop (RDP, via FreeRDP). */
    RDP("Windows (リモートデスクトップ)", 3389),
}

/**
 * A saved connection target. [host] is typically the computer's Tailscale
 * MagicDNS name (my-mac.tailnet-name.ts.net) or Tailnet IP (100.x.y.z).
 * For [Protocol.VNC] a non-empty [username] selects Apple ARD auth (macOS
 * account login) and empty means classic VNC password auth; for
 * [Protocol.RDP] it is the Windows account (DOMAIN\user also accepted).
 * The password lives in [SecretStore].
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 5900,
    val username: String = "",
    val protocol: Protocol = Protocol.VNC,
)
