package io.github.halz.macremote.rfb.auth

import io.github.halz.macremote.rfb.transport.RfbSocket
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Apple Remote Desktop authentication (security type 30) — what macOS Screen
 * Sharing uses for login with a macOS username/password.
 *
 * Wire format (after selecting type 30):
 *   server -> generator(u16) | keyLength(u16) | primeModulus(keyLength bytes) | serverPublicKey(keyLength bytes)
 *   client -> AES-128-ECB(credentials, key=MD5(sharedSecret))(128 bytes) | clientPublicKey(keyLength bytes)
 * where credentials = username[64] + password[64], each NUL-terminated and
 * padded with random bytes, and sharedSecret is the DH secret left-padded
 * with zeros to keyLength bytes.
 *
 * DH-512 + MD5 is weak by modern standards; this protocol is Apple's and is
 * expected to run inside a Tailscale (WireGuard) tunnel.
 */
object ArdAuth {

    fun respond(socket: RfbSocket, username: String, password: String, random: SecureRandom = SecureRandom()): ByteArray {
        val generator = socket.readU16()
        val keyLength = socket.readU16()
        require(keyLength in 8..1024) { "unreasonable ARD key length $keyLength" }
        val prime = socket.readBytes(keyLength)
        val serverPublic = socket.readBytes(keyLength)
        return buildResponse(generator, prime, serverPublic, username, password, random)
    }

    internal fun buildResponse(
        generator: Int,
        prime: ByteArray,
        serverPublic: ByteArray,
        username: String,
        password: String,
        random: SecureRandom,
    ): ByteArray {
        val keyLength = prime.size
        val p = BigInteger(1, prime)
        val g = BigInteger.valueOf(generator.toLong())
        val serverPub = BigInteger(1, serverPublic)

        var secret: BigInteger
        do {
            secret = BigInteger(keyLength * 8, random).mod(p)
        } while (secret.signum() == 0)

        val clientPub = g.modPow(secret, p)
        val shared = serverPub.modPow(secret, p)

        val aesKey = MessageDigest.getInstance("MD5").digest(toFixedLength(shared, keyLength))

        val credentials = ByteArray(128)
        random.nextBytes(credentials)
        writeCString(credentials, 0, username)
        writeCString(credentials, 64, password)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val encrypted = cipher.doFinal(credentials)

        return encrypted + toFixedLength(clientPub, keyLength)
    }

    /** Big-endian magnitude, left-padded with zeros (BigInteger may emit a sign byte or drop leading zeros). */
    internal fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        val start = raw.size - length
        if (start >= 0) {
            // drop leading sign/zero bytes; anything beyond that would be a protocol error
            for (i in 0 until start) require(raw[i] == 0.toByte()) { "value does not fit in $length bytes" }
            System.arraycopy(raw, start, out, 0, length)
        } else {
            System.arraycopy(raw, 0, out, length - raw.size, raw.size)
        }
        return out
    }

    private fun writeCString(target: ByteArray, offset: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val length = minOf(bytes.size, 63)
        System.arraycopy(bytes, 0, target, offset, length)
        target[offset + length] = 0
    }
}
