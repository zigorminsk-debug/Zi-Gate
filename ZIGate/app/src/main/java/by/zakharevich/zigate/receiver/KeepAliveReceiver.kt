package by.zakharevich.zigate.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import by.zakharevich.zigate.util.KeepAlive

/** Periodic alarm + package-replaced: bring the tracking service back. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == KeepAlive.ACTION ||
            a == Intent.ACTION_MY_PACKAGE_REPLACED ||
            a == Intent.ACTION_USER_PRESENT
        ) {
            KeepAlive.startServiceIfNeeded(context)
        }
    }
}
