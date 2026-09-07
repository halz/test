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

/** How long the app may sit in the background before sessions are closed. */
enum class IdleTimeout(val label: String, val minutes: Int) {
    M5("5分", 5),
    M15("15分", 15),
    M30("30分", 30),
    M60("60分", 60),
    OFF("なし (切断しない)", 0),
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

    private val idleTimeoutKey = stringPreferencesKey("idle_timeout")

    val idleTimeout: Flow<IdleTimeout> = dataStore.data.map { prefs ->
        prefs[idleTimeoutKey]?.let { name -> IdleTimeout.entries.find { it.name == name } } ?: IdleTimeout.M15
    }

    suspend fun setIdleTimeout(value: IdleTimeout) {
        dataStore.edit { it[idleTimeoutKey] = value.name }
    }

    private fun gestureKey(trigger: GestureTrigger) = stringPreferencesKey("gesture_${trigger.name}")

    /** Every gesture with its bound command; unset gestures fall back to their default. */
    val gestures: Flow<Map<GestureTrigger, GestureAction>> = dataStore.data.map { prefs ->
        GestureTrigger.entries.associateWith { trigger ->
            prefs[gestureKey(trigger)]?.let { name -> GestureAction.entries.find { it.name == name } }
                ?: trigger.default
        }
    }

    suspend fun setGesture(trigger: GestureTrigger, action: GestureAction) {
        dataStore.edit { it[gestureKey(trigger)] = action.name }
    }

    suspend fun resetGestures() {
        dataStore.edit { prefs -> GestureTrigger.entries.forEach { prefs.remove(gestureKey(it)) } }
    }
}
