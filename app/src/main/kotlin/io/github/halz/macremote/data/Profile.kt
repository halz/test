package io.github.halz.macremote.data

import kotlinx.serialization.Serializable

/**
 * A saved connection target. [host] is typically the Mac's Tailscale MagicDNS
 * name (my-mac.tailnet-name.ts.net) or Tailnet IP (100.x.y.z).
 * A non-empty [username] selects Apple ARD auth (macOS account login);
 * empty means classic VNC password auth. The password lives in [SecretStore].
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 5900,
    val username: String = "",
)
