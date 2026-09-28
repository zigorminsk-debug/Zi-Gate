package by.zakharevich.zigate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        installCrashReporter()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_text)
                setShowBadge(false)
            }
            val prompt = NotificationChannel(
                CHANNEL_PROMPT,
                getString(R.string.notif_call_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_call_body, "")
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
            val nm = getSystemService(NotificationManager::class.java)
            val update = NotificationChannel(
                CHANNEL_UPDATE,
                getString(R.string.notif_update_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.notif_update_body) }
            nm?.createNotificationChannel(channel)
            nm?.createNotificationChannel(prompt)
            nm?.createNotificationChannel(update)
        }
    }

    /**
     * Records any uncaught exception to Logcat (tag ZIGATE), writes it to a
     * shareable file, and shows it full-screen in [CrashActivity] so the exact
     * error can be read/copied and reported. The process is exited when the
     * activity is dismissed.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val message = try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val build = "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                        "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n"
                val log = "=== ZI Gate crash - $ts ===\n" + build +
                        "thread=${thread?.name}\n" + sw.toString()
                Log.e(TAG, log)

                runCatching {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    File(dir, "zigate_crash.txt").appendText("\n" + log + "\n")
                    Log.i(TAG, "Crash log written to: $dir/zigate_crash.txt")
                }
                // Binder extra size limit — keep a readable tail.
                if (log.length > 60_000) log.take(60_000) + "\n…truncated…" else log
            } catch (t: Throwable) {
                "Ошибка при формировании отчёта: $t\noriginal=${throwable}"
            }

            val shown = runCatching {
                val i = Intent(this, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(CrashActivity.EXTRA_MESSAGE, message)
                startActivity(i)
            }.isSuccess

            try { Thread.sleep(if (shown) 400 else 50) } catch (_: InterruptedException) {}

            if (!shown) {
                runCatching { previous?.uncaughtException(thread, throwable) }
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "zi_gate_service"
        const val CHANNEL_PROMPT = "zi_gate_call_prompt"
        const val CHANNEL_UPDATE = "zi_gate_update"
        private const val TAG = "ZIGATE"
    }
}

