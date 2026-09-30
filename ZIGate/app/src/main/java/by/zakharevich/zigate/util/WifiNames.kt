package by.zakharevich.zigate.util

/** Compare SSIDs so "CSL.by" matches "CSL.by 5" / "CSL.by_5G". */
object WifiNames {

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().trim('"').replace(Regex("\\s+"), " ")
            .takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: ""
    }

    fun family(raw: String?): String {
        var t = clean(raw).lowercase()
        if (t.isEmpty()) return ""
        t = t.replace(
            Regex("""[\s_\-]*(5ghz|2\.4ghz|5g|2\.4g|2g|5ghz|5)$"""),
            ""
        ).trim()
        return t
    }

    fun same(a: String?, b: String?): Boolean {
        val ca = clean(a)
        val cb = clean(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        if (ca.equals(cb, ignoreCase = true)) return true
        val fa = family(ca)
        val fb = family(cb)
        return fa.isNotEmpty() && fa == fb
    }
}
