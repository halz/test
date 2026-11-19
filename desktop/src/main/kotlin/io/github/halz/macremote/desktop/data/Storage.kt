package io.github.halz.macremote.desktop.data

import io.github.halz.macremote.data.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Per-user data folder: %APPDATA%\MacRemote on Windows. */
object AppDirs {
    val root: File = run {
        val appData = System.getenv("APPDATA")
        val dir = if (appData != null) File(appData, "MacRemote") else File(System.getProperty("user.home"), ".macremote")
        dir.mkdirs()
        dir
    }
}

internal val storeJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

/** Writes via a temporary file so a crash never leaves a half-written file behind. */
internal fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** The saved connections, as one JSON file (a handful of records at most). */
class ProfileStore(dir: File = AppDirs.root) {
    private val file = File(dir, "profiles.json")
    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<Profile>> = _profiles

    private fun load(): List<Profile> =
        runCatching { storeJson.decodeFromString<List<Profile>>(file.readText()) }.getOrNull() ?: emptyList()

    fun find(id: String): Profile? = _profiles.value.find { it.id == id }

    @Synchronized
    fun upsert(profile: Profile) {
        val current = _profiles.value
        val updated = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        file.writeAtomically(storeJson.encodeToString(updated))
        _profiles.value = updated
    }

    @Synchronized
    fun delete(id: String) {
        val updated = _profiles.value.filter { it.id != id }
        file.writeAtomically(storeJson.encodeToString(updated))
        _profiles.value = updated
    }
}

@Serializable
data class DesktopSettings(
    /** Send the PC's Ctrl as the Mac's Command (else Alt is Command, as on a Mac keyboard). */
    val ctrlAsCommand: Boolean = true,
    /** Scale the remote screen to the window (else show it 1:1). */
    val fitToWindow: Boolean = true,
    /** Per profile: the displayplacer command that undoes "Full HD". */
    val displayRestore: Map<String, String> = emptyMap(),
)

class SettingsStore(dir: File = AppDirs.root) {
    private val file = File(dir, "settings.json")
    private val _settings = MutableStateFlow(
        runCatching { storeJson.decodeFromString<DesktopSettings>(file.readText()) }.getOrNull() ?: DesktopSettings()
    )
    val settings: StateFlow<DesktopSettings> = _settings

    @Synchronized
    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        _settings.update(transform)
        file.writeAtomically(storeJson.encodeToString(_settings.value))
    }
}
