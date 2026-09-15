package by.zakharevich.zigate.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import by.zakharevich.zigate.data.Settings
import by.zakharevich.zigate.service.BarrierService

/** Starts the tracking service every time the device boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            // Only run automatically if the user enabled the feature.
            if (Settings.isAutoEnabled(context)) {
                val service = Intent(context, BarrierService::class.java)
                    .setAction(BarrierService.ACTION_START)
                // Some Android versions restrict background foreground-service
                // starts; never crash the receiver on that.
                runCatching { ContextCompat.startForegroundService(context, service) }
            }
        }
    }
}
