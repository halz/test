package io.github.halz.macremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "profiles")

/** Persists the profile list as one JSON blob — a handful of records at most. */
class ProfileRepository(private val dataStore: DataStore<Preferences>) {

    private val key = stringPreferencesKey("profiles_json")
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<Profile>> = dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<Profile>>(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun find(id: String): Profile? = profiles.first().find { it.id == id }

    suspend fun upsert(profile: Profile) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<List<Profile>>(it) }.getOrNull()
            } ?: emptyList()
            val updated = if (current.any { it.id == profile.id }) {
                current.map { if (it.id == profile.id) profile else it }
            } else {
                current + profile
            }
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<List<Profile>>(it) }.getOrNull()
            } ?: emptyList()
            prefs[key] = json.encodeToString(current.filter { it.id != id })
        }
    }
}
