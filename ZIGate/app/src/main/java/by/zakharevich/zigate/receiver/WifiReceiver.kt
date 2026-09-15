package by.zakharevich.zigate.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import by.zakharevich.zigate.data.Settings
import by.zakharevich.zigate.service.BarrierService

/**
 * Redundant safety net: if the running service did not see a Wi-Fi change
 * (for example after process restart), ask it to re-evaluate. When the
 * feature is enabled this also makes sure the service is alive after a
 * Wi-Fi reconnection.
 */
class WifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION &&
            intent.action != android.net.ConnectivityManager.CONNECTIVITY_ACTION
        ) return
        if (Settings.isAutoEnabled(context)) {
            val service = Intent(context, BarrierService::class.java)
                .setAction(BarrierService.ACTION_REFRESH)
            runCatching { ContextCompat.startForegroundService(context, service) }
            by.zakharevich.zigate.util.KeepAlive.schedule(context)
        }
    }
}
