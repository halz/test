package io.github.halz.macremote.desktop.data

import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.Protocol
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The Android app's backup format (version 1), so a file exported on the
 * phone imports here and vice versa. Passwords are included IN PLAIN TEXT.
 */
@Serializable
data class ProfileBackup(
    val version: Int = 1,
    val profiles: List<ProfileBackupEntry>,
)

@Serializable
data class ProfileBackupEntry(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: Protocol = Protocol.VNC,
)

object ProfileTransfer {

    fun export(profiles: ProfileStore, secrets: SecretStore): String {
        val entries = profiles.profiles.value.map { profile ->
            ProfileBackupEntry(
                id = profile.id,
                name = profile.name,
                host = profile.host,
                port = profile.port,
                username = profile.username,
                password = secrets.loadPassword(profile.id).orEmpty(),
                protocol = profile.protocol,
            )
        }
        return storeJson.encodeToString(ProfileBackup(profiles = entries))
    }

    /** Returns the number of imported profiles, or throws on malformed input. */
    fun import(content: String, profiles: ProfileStore, secrets: SecretStore): Int {
        val backup = storeJson.decodeFromString<ProfileBackup>(content)
        require(backup.version == 1) { "未対応のバックアップバージョンです (version=${backup.version})" }
        backup.profiles.forEach { entry ->
            val id = entry.id.ifBlank { UUID.randomUUID().toString() }
            profiles.upsert(
                Profile(
                    id = id,
                    name = entry.name,
                    host = entry.host,
                    port = entry.port,
                    username = entry.username,
                    protocol = entry.protocol,
                )
            )
            secrets.savePassword(id, entry.password)
        }
        return backup.profiles.size
    }
}
