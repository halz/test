package io.github.halz.macremote.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Backup format for connection profiles. Passwords are included IN PLAIN TEXT
 * so a backup restores to a working state on another device — the UI warns
 * about this before exporting.
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
)

object ProfileTransfer {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun export(repository: ProfileRepository, secretStore: SecretStore): String {
        val profiles = repository.profiles.first().map { profile ->
            ProfileBackupEntry(
                id = profile.id,
                name = profile.name,
                host = profile.host,
                port = profile.port,
                username = profile.username,
                password = secretStore.loadPassword(profile.id).orEmpty(),
            )
        }
        return json.encodeToString(ProfileBackup(profiles = profiles))
    }

    /** Returns the number of imported profiles, or throws on malformed input. */
    suspend fun import(content: String, repository: ProfileRepository, secretStore: SecretStore): Int {
        val backup = json.decodeFromString<ProfileBackup>(content)
        backup.profiles.forEach { entry ->
            val id = entry.id.ifBlank { UUID.randomUUID().toString() }
            repository.upsert(
                Profile(
                    id = id,
                    name = entry.name,
                    host = entry.host,
                    port = entry.port,
                    username = entry.username,
                )
            )
            secretStore.savePassword(id, entry.password)
        }
        return backup.profiles.size
    }
}
