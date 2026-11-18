package io.github.halz.macremote.desktop.data

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-profile passwords, encrypted at rest. On Windows they are sealed with
 * DPAPI (CryptProtectData), so only the same Windows account can read them.
 * Elsewhere (development builds) AES-GCM with a key file readable by the
 * owner only stands in.
 */
class SecretStore(private val dir: File = AppDirs.root) {
    private val file = File(dir, "secrets.json")
    private val windows = System.getProperty("os.name").startsWith("Windows")

    private fun loadAll(): Map<String, String> =
        runCatching { storeJson.decodeFromString<Map<String, String>>(file.readText()) }.getOrNull() ?: emptyMap()

    @Synchronized
    fun savePassword(profileId: String, password: String) {
        val blob = Base64.getEncoder().encodeToString(seal(password.toByteArray(Charsets.UTF_8)))
        file.writeAtomically(storeJson.encodeToString(loadAll() + (profileId to blob)))
    }

    @Synchronized
    fun loadPassword(profileId: String): String? {
        val blob = loadAll()[profileId] ?: return null
        return runCatching { String(unseal(Base64.getDecoder().decode(blob)), Charsets.UTF_8) }.getOrNull()
    }

    @Synchronized
    fun deletePassword(profileId: String) {
        file.writeAtomically(storeJson.encodeToString(loadAll() - profileId))
    }

    private fun seal(data: ByteArray): ByteArray {
        if (windows) return Crypt32Util.cryptProtectData(data)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, fallbackKey())
        return cipher.iv + cipher.doFinal(data)
    }

    private fun unseal(data: ByteArray): ByteArray {
        if (windows) return Crypt32Util.cryptUnprotectData(data)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, fallbackKey(), GCMParameterSpec(128, data, 0, 12))
        return cipher.doFinal(data, 12, data.size - 12)
    }

    private fun fallbackKey(): SecretKeySpec {
        val keyFile = File(dir, "secret.key")
        if (!keyFile.exists()) {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            keyFile.writeBytes(key)
            runCatching { Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }
}
