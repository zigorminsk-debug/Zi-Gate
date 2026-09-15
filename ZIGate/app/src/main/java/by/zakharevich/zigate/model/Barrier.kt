package by.zakharevich.zigate.model

import org.json.JSONObject
import java.util.UUID

/**
 * One barrier ("шлагбаум"): a trigger zone described by its center, radius,
 * the phone number to dial and an optional name.
 */
data class Barrier(
    val id: String,
    var name: String,
    var phone: String,
    var lat: Double,
    var lng: Double,
    var radius: Float,
    var enabled: Boolean = true,
    /** Direct auto-call when entering the zone. If false, a confirm notification is shown. */
    var autoCall: Boolean = true,
    // Time (ms) of the last automatically triggered call – used as a cooldown.
    var lastTriggeredAt: Long = 0L,
    // How often to re-dial while the phone stays inside the zone (in seconds).
    // Range 5..180 (5s .. 3min). Default 60s (1 minute).
    var repeatIntervalSec: Int = 60,
    // Which barrier icon to show. One of "gate1".."gate5".
    var icon: String = "gate1"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("phone", phone)
        put("lat", lat)
        put("lng", lng)
        put("radius", radius.toDouble())
        put("enabled", enabled)
        put("autoCall", autoCall)
        put("lastTriggeredAt", lastTriggeredAt)
        put("repeatIntervalSec", repeatIntervalSec)
        put("icon", icon)
    }

    /** Compact JSON for sharing (no id / lastTriggered). */
    fun toShareJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("phone", phone)
        put("lat", lat)
        put("lng", lng)
        put("radius", radius.toDouble())
        put("autoCall", autoCall)
        put("repeatIntervalSec", repeatIntervalSec)
        put("icon", icon)
    }

    companion object {
        fun fromShareJson(o: JSONObject): Barrier = Barrier(
            id = UUID.randomUUID().toString(),
            name = o.optString("name", "Шлагбаум"),
            phone = o.optString("phone", ""),
            lat = o.optDouble("lat", 0.0),
            lng = o.optDouble("lng", 0.0),
            radius = o.optDouble("radius", 30.0).toFloat(),
            enabled = true,
            autoCall = o.optBoolean("autoCall", true),
            lastTriggeredAt = 0L,
            repeatIntervalSec = o.optInt("repeatIntervalSec", 60),
            icon = o.optString("icon", "gate1")
        )

        fun fromJson(o: JSONObject): Barrier = Barrier(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Шлагбаум"),
            phone = o.optString("phone", ""),
            lat = o.optDouble("lat", 0.0),
            lng = o.optDouble("lng", 0.0),
            radius = o.optDouble("radius", 30.0).toFloat(),
            enabled = o.optBoolean("enabled", true),
            autoCall = o.optBoolean("autoCall", true),
            lastTriggeredAt = o.optLong("lastTriggeredAt", 0L),
            repeatIntervalSec = o.optInt("repeatIntervalSec", 60),
            icon = o.optString("icon", "gate1")
        )
    }
}
