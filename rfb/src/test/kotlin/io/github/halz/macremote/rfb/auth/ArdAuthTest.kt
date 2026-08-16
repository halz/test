package io.github.halz.macremote.rfb.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies ArdAuth against a server role implemented here: generate a DH
 * keypair, hand the client the public parameters, then decrypt the client's
 * response and check the credentials round-trip. This is the only check
 * possible without a real Mac; field order/padding still needs on-device
 * verification (see plan risks).
 */
class ArdAuthTest {

    @Test
    fun `credentials round-trip through DH plus AES`() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        val keyLength = 64 // 512-bit, as macOS uses

        // Server: a safe-ish prime for the test and generator 2.
        val prime = BigInteger.probablePrime(keyLength * 8, random)
        val generator = 2
        val g = BigInteger.valueOf(generator.toLong())
        val serverSecret = BigInteger(keyLength * 8, random).mod(prime)
        val serverPublic = g.modPow(serverSecret, prime)

        val primeBytes = ArdAuth.toFixedLength(prime, keyLength)
        val serverPublicBytes = ArdAuth.toFixedLength(serverPublic, keyLength)

        val username = "alice"
        val password = "s3cret-password"

        val response = ArdAuth.buildResponse(
            generator, primeBytes, serverPublicBytes, username, password, random,
        )

        // Split response: 128-byte ciphertext + client public key.
        assertEquals(128 + keyLength, response.size)
        val ciphertext = response.copyOfRange(0, 128)
        val clientPublicBytes = response.copyOfRange(128, response.size)
        val clientPublic = BigInteger(1, clientPublicBytes)

        // Server derives the same shared secret and AES key.
        val shared = clientPublic.modPow(serverSecret, prime)
        val aesKey = MessageDigest.getInstance("MD5").digest(ArdAuth.toFixedLength(shared, keyLength))

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val plain = cipher.doFinal(ciphertext)

        assertEquals(username, readCString(plain, 0))
        assertEquals(password, readCString(plain, 64))
    }

    private fun readCString(bytes: ByteArray, offset: Int): String {
        var end = offset
        while (end < offset + 64 && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }
}
