package by.zakharevich.zigate.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learns cells the user travels through just before entering a barrier zone.
 * Next time the phone is in those cells, polling is sped up so a familiar
 * commute cannot "sleep through" the gate.
 *
 * Cell size ≈ 80 m. Cap 500 cells. Persisted in SharedPreferences.
 */
object RouteMemory {

    private const val PREFS = "zi_gate_prefs"
    private const val KEY = "route_cells"
    private const val CELL_M = 80.0
    private const val MAX_CELLS = 500
    private const val APPROACH_TRAIL = 12
    private const val HIT_THRESHOLD = 2

    data class Sample(val lat: Double, val lng: Double)

    private val trail = ArrayDeque<Sample>(APPROACH_TRAIL + 2)
    private var cache: MutableMap<String, Int>? = null

    fun onFix(lat: Double, lng: Double) {
        trail.addLast(Sample(lat, lng))
        while (trail.size > APPROACH_TRAIL) trail.removeFirst()
    }

    /** Call when a barrier actually triggered — mark the recent trail. */
    fun onTriggered(context: Context, lat: Double, lng: Double) {
        onFix(lat, lng)
        val map = load(context)
        for (s in trail) {
            val k = cellKey(s.lat, s.lng)
            map[k] = (map[k] ?: 0) + 1
        }
        prune(map)
        save(context, map)
    }

    fun isOnRoute(context: Context, lat: Double, lng: Double): Boolean {
        val map = load(context)
        if (map.isEmpty()) return false
        val k = cellKey(lat, lng)
        if ((map[k] ?: 0) >= HIT_THRESHOLD) return true
        // Neighbours: GPS jitter of one cell.
        for (dLat in -1..1) for (dLng in -1..1) {
            if (dLat == 0 && dLng == 0) continue
            val n = neighborKey(lat, lng, dLat, dLng)
            if ((map[n] ?: 0) >= HIT_THRESHOLD) return true
        }
        return false
    }

    private fun cellKey(lat: Double, lng: Double): String {
        val latM = lat * 111_000.0
        val lngM = lng * 111_000.0 * kotlin.math.cos(Math.toRadians(lat))
        val i = kotlin.math.floor(latM / CELL_M).toInt()
        val j = kotlin.math.floor(lngM / CELL_M).toInt()
        return "$i:$j"
    }

    private fun neighborKey(lat: Double, lng: Double, di: Int, dj: Int): String {
        val latM = lat * 111_000.0
        val lngM = lng * 111_000.0 * kotlin.math.cos(Math.toRadians(lat))
        val i = kotlin.math.floor(latM / CELL_M).toInt() + di
        val j = kotlin.math.floor(lngM / CELL_M).toInt() + dj
        return "$i:$j"
    }

    private fun load(context: Context): MutableMap<String, Int> {
        cache?.let { return it }
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val map = mutableMapOf<String, Int>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    map[o.getString("k")] = o.optInt("n", 1)
                }
            }
        }
        cache = map
        return map
    }

    private fun save(context: Context, map: MutableMap<String, Int>) {
        cache = map
        val arr = JSONArray()
        map.forEach { (k, n) ->
            arr.put(JSONObject().put("k", k).put("n", n))
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun prune(map: MutableMap<String, Int>) {
        if (map.size <= MAX_CELLS) return
        val drop = map.size - MAX_CELLS
        map.entries.sortedBy { it.value }.take(drop).forEach { map.remove(it.key) }
    }
}
