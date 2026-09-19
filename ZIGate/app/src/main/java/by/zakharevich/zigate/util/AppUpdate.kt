package by.zakharevich.zigate.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import by.zakharevich.zigate.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases and downloads a newer signed APK.
 * Repo: zigorminsk-debug/Zi-Gate — CI attaches ZI-Gate-vX.Y-release.apk.
 */
object AppUpdate {

    const val REPO = "zigorminsk-debug/Zi-Gate"
    private const val API = "https://api.github.com/repos/$REPO/releases"

    data class Release(
        val version: String,
        val apkUrl: String,
        val notes: String
    )

    fun compare(a: String, b: String): Int {
        val pa = a.trimStart('v', 'V').split('.', '-', '_')
        val pb = b.trimStart('v', 'V').split('.', '-', '_')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val xa = pa.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val xb = pb.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (xa != xb) return xa.compareTo(xb)
        }
        return 0
    }

    fun latestNewerThan(current: String = BuildConfig.VERSION_NAME): Release? {
        val rel = fetchLatest() ?: return null
        return if (compare(rel.version, current) > 0) rel else null
    }

    fun fetchLatest(): Release? {
        val body = httpGet("$API/latest") ?: httpGet("$API?per_page=5") ?: return null
        val obj = if (body.trimStart().startsWith("[")) {
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            pickNewest(arr)
        } else JSONObject(body)
        return parseRelease(obj)
    }

    private fun pickNewest(arr: JSONArray): JSONObject {
        var best = arr.getJSONObject(0)
        var bestV = best.optString("tag_name")
        for (i in 1 until arr.length()) {
            val o = arr.getJSONObject(i)
            val v = o.optString("tag_name")
            if (compare(v, bestV) > 0) {
                best = o
                bestV = v
            }
        }
        return best
    }

    private fun parseRelease(obj: JSONObject): Release? {
        val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
        val version = tag.trimStart('v', 'V').trim()
        if (version.isEmpty()) return null
        val assets = obj.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                if (name.contains("ZI-Gate", ignoreCase = true)) break
            }
        }
        if (apkUrl.isNullOrBlank()) return null
        return Release(version, apkUrl, obj.optString("body"))
    }

    fun downloadApk(context: Context, url: String): File? {
        return try {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val out = File(dir, "ZI-Gate-update.apk")
            val conn = open(url)
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.inputStream.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            conn.disconnect()
            if (out.length() > 10_000) out else null
        } catch (_: Exception) {
            null
        }
    }

    fun installApk(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context, "by.zakharevich.zigate.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 26)
            context.packageManager.canRequestPackageInstalls()
        else true
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val i = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = open(url)
            conn.connectTimeout = 12_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            val text = if (code in 200..299)
                conn.inputStream.bufferedReader().readText() else null
            conn.disconnect()
            text
        } catch (_: Exception) {
            null
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ZI-Gate/${BuildConfig.VERSION_NAME}")
        return conn
    }
}
