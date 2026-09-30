package by.zakharevich.zigate.data

import android.content.Context

/** App settings persisted in SharedPreferences. */
object Settings {

    private const val PREFS = "zi_gate_prefs"
    private const val KEY_AUTO = "auto_enabled"
    private const val KEY_WIFI_GATE = "wifi_gate_enabled"
    private const val KEY_WIFI_SET = "wifi_pause_ssids"
    private const val KEY_WIFI_JSON = "wifi_pause_ssids_json"
    private const val KEY_PAUSE_CODE = "pause_code"
    private const val KEY_UPDATE_CHECK = "last_update_check_ms"

    // ---------- auto open ----------
    fun isAutoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO, value).apply()

    // ---------- wifi pause ----------
    fun isWifiGateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_GATE, false)

    fun setWifiGateEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_WIFI_GATE, value).apply()

    fun wifiPauseSet(context: Context): MutableSet<String> {
        val p = prefs(context)
        val json = p.getString(KEY_WIFI_JSON, null)
        if (!json.isNullOrBlank()) {
            val out = HashSet<String>()
            runCatching {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim().trim('"')
                    if (s.isNotEmpty()) out.add(s)
                }
            }
            return out
        }
        return HashSet(p.getStringSet(KEY_WIFI_SET, emptySet()) ?: emptySet())
            .map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toHashSet()
    }

    fun setWifiPauseSet(context: Context, set: Set<String>) {
        val arr = org.json.JSONArray()
        set.map { it.trim().trim('"') }.filter { it.isNotEmpty() }.sorted().forEach { arr.put(it) }
        prefs(context).edit()
            .putString(KEY_WIFI_JSON, arr.toString())
            .remove(KEY_WIFI_SET)
            .apply()
    }

    // ---------- pause code (for the gate auto-network) ----------
    fun pauseCode(context: Context): String =
        prefs(context).getString(KEY_PAUSE_CODE, "") ?: ""

    fun setPauseCode(context: Context, value: String) =
        prefs(context).edit().putString(KEY_PAUSE_CODE, value).apply()

    fun lastUpdateCheckMs(context: Context): Long =
        prefs(context).getLong(KEY_UPDATE_CHECK, 0L)

    fun setLastUpdateCheckMs(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_UPDATE_CHECK, value).apply()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
