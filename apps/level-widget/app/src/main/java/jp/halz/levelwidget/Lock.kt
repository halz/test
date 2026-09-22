package jp.halz.levelwidget

import org.json.JSONObject
import java.util.UUID

/**
 * One lock the widget can operate. The Level app toggles between locked and unlocked with a single
 * button, so one recorded press per lock is enough.
 *
 * [bleAddress] is the Bluetooth device the user associated with this lock; it is only used to show
 * whether the lock is close enough to operate, never to talk to it.
 */
data class Lock(
    val id: String,
    val name: String,
    val press: ButtonPress?,
    val bleAddress: String?,
) {
    val isReady: Boolean get() = press != null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("press", press?.toJson() ?: JSONObject.NULL)
        .put("bleAddress", bleAddress ?: JSONObject.NULL)

    companion object {
        fun create(name: String): Lock =
            Lock(UUID.randomUUID().toString(), name, null, null)

        fun fromJson(json: JSONObject): Lock? {
            val id = json.optString("id").ifEmpty { return null }
            return Lock(
                id = id,
                name = json.optString("name").ifEmpty { id.take(4) },
                press = ButtonPress.fromJson(json.optJSONObject("press")),
                bleAddress = if (json.isNull("bleAddress")) null
                else json.optString("bleAddress").ifEmpty { null },
            )
        }
    }
}
