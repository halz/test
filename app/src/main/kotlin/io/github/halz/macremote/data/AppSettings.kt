package io.github.halz.macremote.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How the received framebuffer is scaled for display. */
enum class DisplayScale(val label: String, val divisor: Int?) {
    /** Pick a divisor that roughly matches the screen currently in use (fold-aware). */
    AUTO("自動 (画面に最適化)", null),
    FULL("フル解像度", 1),
    HALF("1/2", 2),
    THIRD("1/3", 3),
}

/** Session-behavior settings, shared across profiles. */
class AppSettings(private val dataStore: DataStore<Preferences>) {

    private val displayScaleKey = stringPreferencesKey("display_scale")
    private val trackpadKey = booleanPreferencesKey("trackpad_mode")

    val displayScale: Flow<DisplayScale> = dataStore.data.map { prefs ->
        prefs[displayScaleKey]?.let { name -> DisplayScale.entries.find { it.name == name } } ?: DisplayScale.AUTO
    }

    suspend fun setDisplayScale(value: DisplayScale) {
        dataStore.edit { it[displayScaleKey] = value.name }
    }

    val trackpadMode: Flow<Boolean> = dataStore.data.map { it[trackpadKey] ?: false }

    suspend fun setTrackpadMode(value: Boolean) {
        dataStore.edit { it[trackpadKey] = value }
    }
}
