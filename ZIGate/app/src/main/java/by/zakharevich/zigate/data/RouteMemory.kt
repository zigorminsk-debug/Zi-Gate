package by.zakharevich.zigate.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standard route lesson:
 *  1. User taps «Обучить маршрут» on a barrier.
 *  2. Drives the usual way to that gate (GPS on; charging helps).
 *  3. When the zone fires, the trail is saved for that barrier.
 *  4. Repeat 2–3 times the same way.
 *
 * Cells with ≥ [HIT_OK] visits are the corridor. On the corridor GPS
 * speeds up so a 5 m zone is not skipped. Off the corridor autodial
 * is stricter (walk-by / parallel street).
 *
 * Cell ≈ 60 m. Cap 800 cells. Persisted in SharedPreferences.
 */
object RouteMemory {

    private const val PREFS = "zi_gate_prefs"
    private const val KEY = "route_v2"
    private const val KEY_OLD = "route_cells"
    private const val CELL_M = 60.0
    private const val MAX_CELLS = 800
    private const val TRAIL_MAX = 48
    const val HIT_OK = 2
    const val LESSONS_OK = 2

    data class Sample(val lat: Double, val lng: Double)

    private val trail = ArrayDeque<Sample>(TRAIL_MAX + 2)
    private val lessonSeen = HashSet<String>()
    private var cache: Store? = null

    var trainingId: String? = null
        private set
    var trainingName: String? = null
        private set

    data class Store(
        val cells: MutableMap<String, Int> = mutableMapOf(),
        val trips: MutableMap<String, Int> = mutableMapOf()
    )

    fun isTraining(): Boolean = trainingId != null

    fun startLesson(context: Context, barrierId: String, name: String) {
        trainingId = barrierId
        trainingName = name
        lessonSeen.clear()
        trail.clear()
        persistMeta(context)
    }

    fun stopLesson(context: Context) {
        trainingId = null
        trainingName = null
        lessonSeen.clear()
        persistMeta(context)
    }

    fun onFix(context: Context, lat: Double, lng: Double, accuracyM: Float) {
        trail.addLast(Sample(lat, lng))
        while (trail.size > TRAIL_MAX) trail.removeFirst()
        val id = trainingId ?: return
        if (accuracyM > 45f) return
        val k = cellKey(id, lat, lng)
        if (!lessonSeen.add(k)) return
        val s = load(context)
        s.cells[k] = (s.cells[k] ?: 0) + 1
        prune(s.cells)
        save(context, s)
    }

    fun onTriggered(context: Context, lat: Double, lng: Double, barrierId: String) {
        onFix(context, lat, lng, 8f)
        val s = load(context)
        val id = trainingId ?: barrierId
        for (p in trail) {
            val k = cellKey(id, p.lat, p.lng)
            if (lessonSeen.add(k) || trainingId == null) {
                s.cells[k] = (s.cells[k] ?: 0) + 1
            }
        }
        s.trips[id] = (s.trips[id] ?: 0) + 1
        prune(s.cells)
        save(context, s)
        if (trainingId == barrierId) stopLesson(context)
    }

    fun isOnRoute(context: Context, lat: Double, lng: Double, barrierId: String? = null): Boolean {
        val s = load(context)
        if (s.cells.isEmpty()) return false
        val ids = if (barrierId != null) listOf(barrierId) else s.trips.keys.toList()
        if (ids.isEmpty()) {
            return hit(s, "*", lat, lng)
        }
        for (id in ids) {
            if (hit(s, id, lat, lng)) return true
        }
        return hit(s, "*", lat, lng)
    }

    fun tripsFor(context: Context, barrierId: String): Int =
        load(context).trips[barrierId] ?: 0

    fun corridorReady(context: Context, barrierId: String): Boolean =
        tripsFor(context, barrierId) >= LESSONS_OK

    fun cellCount(context: Context, barrierId: String): Int {
        val prefix = "$barrierId|"
        return load(context).cells.keys.count { it.startsWith(prefix) }
    }

    private fun hit(s: Store, id: String, lat: Double, lng: Double): Boolean {
        val need = HIT_OK
        if ((s.cells[cellKey(id, lat, lng)] ?: 0) >= need) return true
        for (di in -1..1) for (dj in -1..1) {
            if (di == 0 && dj == 0) continue
            if ((s.cells[neighborKey(id, lat, lng, di, dj)] ?: 0) >= need) return true
        }
        return false
    }

    private fun cellKey(id: String, lat: Double, lng: Double): String {
        val latM = lat * 111_000.0
        val lngM = lng * 111_000.0 * kotlin.math.cos(Math.toRadians(lat))
        val i = kotlin.math.floor(latM / CELL_M).toInt()
        val j = kotlin.math.floor(lngM / CELL_M).toInt()
        return "$id|$i:$j"
    }

    private fun neighborKey(id: String, lat: Double, lng: Double, di: Int, dj: Int): String {
        val latM = lat * 111_000.0
        val lngM = lng * 111_000.0 * kotlin.math.cos(Math.toRadians(lat))
        val i = kotlin.math.floor(latM / CELL_M).toInt() + di
        val j = kotlin.math.floor(lngM / CELL_M).toInt() + dj
        return "$id|$i:$j"
    }

    private fun load(context: Context): Store {
        cache?.let { return it }
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY, null)
        val s = Store()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("cells") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    s.cells[c.getString("k")] = c.optInt("n", 1)
                }
                val t = o.optJSONObject("trips")
                t?.keys()?.forEach { s.trips[it] = t.optInt(it, 0) }
            }
        } else {
            migrateOld(p.getString(KEY_OLD, null), s)
        }
        cache = s
        return s
    }

    private fun migrateOld(raw: String?, s: Store) {
        if (raw.isNullOrBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                s.cells["*|${o.getString("k")}"] = o.optInt("n", 1)
            }
        }
    }

    private fun save(context: Context, s: Store) {
        cache = s
        val arr = JSONArray()
        s.cells.forEach { (k, n) -> arr.put(JSONObject().put("k", k).put("n", n)) }
        val t = JSONObject()
        s.trips.forEach { (k, n) -> t.put(k, n) }
        val o = JSONObject().put("cells", arr).put("trips", t)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }

    private fun persistMeta(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("route_training_id", trainingId)
            .putString("route_training_name", trainingName)
            .apply()
    }

    fun restoreTraining(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        trainingId = p.getString("route_training_id", null)
        trainingName = p.getString("route_training_name", null)
        if (trainingId.isNullOrBlank()) {
            trainingId = null
            trainingName = null
        }
    }

    private fun prune(map: MutableMap<String, Int>) {
        if (map.size <= MAX_CELLS) return
        val drop = map.size - MAX_CELLS
        map.entries.sortedBy { it.value }.take(drop).forEach { map.remove(it.key) }
    }
}
