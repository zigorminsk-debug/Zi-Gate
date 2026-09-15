package by.zakharevich.zigate.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import by.zakharevich.zigate.data.Settings
import by.zakharevich.zigate.receiver.KeepAliveReceiver
import by.zakharevich.zigate.service.BarrierService

/**
 * Restarts the foreground service if the OEM killed it overnight.
 * Chains an exact-while-idle alarm every [INTERVAL_MS].
 */
object KeepAlive {

    const val ACTION = "by.zakharevich.zigate.KEEP_ALIVE"
    const val INTERVAL_MS = 8 * 60 * 1000L
    private const val REQ = 7101

    fun schedule(context: Context) {
        val app = context.applicationContext
        if (!Settings.isAutoEnabled(app)) {
            cancel(app)
            return
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val t = SystemClock.elapsedRealtime() + INTERVAL_MS
        val pi = pending(app)
        runCatching {
            when {
                Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms() ->
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t, pi)
                Build.VERSION.SDK_INT >= 23 ->
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t, pi)
                else -> am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, t, pi)
            }
        }.onFailure {
            runCatching {
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t, pi)
                }
            }
        }
    }

    fun cancel(context: Context) {
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pending(context.applicationContext)) }
    }

    fun startServiceIfNeeded(context: Context) {
        if (!Settings.isAutoEnabled(context)) return
        val intent = Intent(context, BarrierService::class.java)
            .setAction(BarrierService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
        schedule(context)
    }

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, KeepAliveReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQ, i, flags)
    }
}
