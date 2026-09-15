package io.github.halz.macremote.rfb.auth

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Classic VNC authentication (security type 2, RFC 6143 §7.2.2).
 *
 * The 16-byte challenge is DES-ECB encrypted with a key built from the first
 * 8 bytes of the password — with the bits of each key byte REVERSED. That bit
 * reversal is a VNC quirk (DES key bytes are used little-bit-endian) and is
 * the part every from-scratch implementation gets wrong first.
 */
object VncAuth {

    fun encryptChallenge(password: String, challenge: ByteArray): ByteArray {
        require(challenge.size == 16) { "VNC challenge must be 16 bytes" }
        val key = ByteArray(8)
        val passwordBytes = password.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 8) {
            val b = if (i < passwordBytes.size) passwordBytes[i] else 0
            key[i] = reverseBits(b)
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    internal fun reverseBits(b: Byte): Byte {
        var input = b.toInt() and 0xFF
        var output = 0
        repeat(8) {
            output = (output shl 1) or (input and 1)
            input = input shr 1
        }
        return output.toByte()
    }
}
