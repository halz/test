package jp.halz.levelwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers: which app to drive, the registered locks, and what was seen. */
class Prefs(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("level-widget", Context.MODE_PRIVATE)

    var targetPackage: String?
        get() = prefs.getString(KEY_TARGET_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGE, value).apply()

    var locks: List<Lock>
        get() {
            val raw = prefs.getString(KEY_LOCKS, null) ?: return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { Lock.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_LOCKS, array.toString()).apply()
        }

    fun lock(id: String?): Lock? = locks.firstOrNull { it.id == id }

    /** Adds the lock, or replaces the one with the same id. */
    fun save(lock: Lock) {
        val current = locks.toMutableList()
        val at = current.indexOfFirst { it.id == lock.id }
        if (at >= 0) current[at] = lock else current.add(lock)
        locks = current
    }

    fun remove(id: String) {
        locks = locks.filterNot { it.id == id }
        prefs.edit().remove(statusKey(id)).apply()
    }

    /** Last line shown under a lock on the widget. */
    fun status(id: String): String = prefs.getString(statusKey(id), "") ?: ""

    fun setStatus(id: String, text: String) {
        prefs.edit().putString(statusKey(id), text).apply()
    }

    /** When each associated Bluetooth device was last advertising, as reported by the scanner. */
    fun lastSeen(address: String): Long = seen().optLong(address, 0L)

    fun markSeen(addresses: Collection<String>, at: Long) {
        val json = seen()
        addresses.forEach { json.put(it, at) }
        prefs.edit().putString(KEY_SEEN, json.toString()).apply()
    }

    private fun seen(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_SEEN, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun statusKey(id: String) = "status_$id"

    private companion object {
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_LOCKS = "locks"
        const val KEY_SEEN = "ble_last_seen"
    }
}
