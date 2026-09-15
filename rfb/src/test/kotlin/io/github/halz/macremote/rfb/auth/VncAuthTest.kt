package io.github.halz.macremote.rfb.auth

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VncAuthTest {

    @Test
    fun `bit reversal matches known values`() {
        // 0x01 (0000_0001) reversed is 0x80 (1000_0000); 0xF0 reversed is 0x0F.
        assertEquals(0x80.toByte(), VncAuth.reverseBits(0x01))
        assertEquals(0x0F.toByte(), VncAuth.reverseBits(0xF0.toByte()))
        assertEquals(0xAA.toByte(), VncAuth.reverseBits(0x55))
    }

    @Test
    fun `encrypts challenge with an independently verified vector`() {
        // password "abc" -> bit-reversed DES key 8646c60000000000; encrypting a
        // zero challenge yields 0f3ddf69f786bf5e per block (verified with
        // `openssl enc -des-ecb -nopad -K 8646c60000000000`).
        val challenge = ByteArray(16)
        val block = byteArrayOf(0x0F, 0x3D, 0xDF.toByte(), 0x69, 0xF7.toByte(), 0x86.toByte(), 0xBF.toByte(), 0x5E)
        val expected = block + block
        assertArrayEquals(expected, VncAuth.encryptChallenge("abc", challenge))
    }

    @Test
    fun `password longer than 8 bytes is truncated`() {
        val challenge = ByteArray(16) { it.toByte() }
        val eight = VncAuth.encryptChallenge("password", challenge)
        val longer = VncAuth.encryptChallenge("password-extra", challenge)
        assertArrayEquals(eight, longer)
    }
}
