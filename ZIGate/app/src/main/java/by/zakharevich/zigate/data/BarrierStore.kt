package by.zakharevich.zigate.data

import android.content.Context
import by.zakharevich.zigate.model.Barrier
import org.json.JSONArray
import org.json.JSONObject

/** Saved barrier list, stored as JSON in SharedPreferences. */
object BarrierStore {

    private const val PREFS = "zi_gate_prefs"
    private const val KEY_BARRIERS = "barriers"

    fun load(context: Context): MutableList<Barrier> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BARRIERS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Barrier>()
            for (i in 0 until arr.length()) {
                out.add(Barrier.fromJson(arr.getJSONObject(i)))
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, list: List<Barrier>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_BARRIERS, arr.toString()).apply()
    }

    /** Export barriers as a JSON array string – used by the "send" function. */
    fun export(context: Context): String {
        val arr = JSONArray()
        load(context).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /** Import a JSON array of barriers (from the "receive" function). */
    fun import(context: Context, json: String): Int {
        val arr = JSONArray(json)
        val list = load(context).toMutableList()
        val existing = list.map { it.id }.toHashSet()
        for (i in 0 until arr.length()) {
            val b = Barrier.fromJson(arr.getJSONObject(i))
            if (b.id !in existing) {
                list.add(b)
                existing.add(b.id)
            }
        }
        save(context, list)
        return list.size
    }
}
