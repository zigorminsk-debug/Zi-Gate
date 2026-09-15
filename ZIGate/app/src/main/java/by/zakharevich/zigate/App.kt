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
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    /**
     * Records any uncaught exception to Logcat (tag ZIGATE), writes it to a
     * shareable file, and shows it full-screen in [CrashActivity] so the exact
     * error can be read/copied and reported. The process is exited when the
     * activity is dismissed.
     */
    private fun installCrashReporter() {
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

                // Write to a file so it can be shared without adb.
                runCatching {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    File(dir, "zigate_crash.txt").appendText("\n" + log + "\n")
                    Log.i(TAG, "Crash log written to: $dir/zigate_crash.txt")
                }
                log
            } catch (t: Throwable) {
                "Ошибка при формировании отчёта: $t\noriginal=${throwable}"
            }

            // Show it on screen so the user can report the exact message.
            runCatching {
                val i = Intent(this, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CrashActivity.EXTRA_MESSAGE, message)
                startActivity(i)
            }

            // Let the screen render, then stop following the normal (killing)
            // handler. CrashActivity exits the process when dismissed.
            try { Thread.sleep(300) } catch (_: InterruptedException) {}

            // Deliberately DO NOT call previous.uncaughtException here, so the
            // process stays alive long enough to display the error screen.
        }
    }

    companion object {
        const val CHANNEL_ID = "zi_gate_service"
        private const val TAG = "ZIGATE"
    }
}

