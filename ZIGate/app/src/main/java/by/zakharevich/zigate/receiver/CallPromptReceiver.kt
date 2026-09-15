package by.zakharevich.zigate.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import by.zakharevich.zigate.util.DialHelper

/** Notification action: user confirmed the call. */
class CallPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CALL_NOW) return
        val phone = intent.getStringExtra(EXTRA_PHONE) ?: return
        DialHelper.placeCall(context, phone)
    }

    companion object {
        const val ACTION_CALL_NOW = "by.zakharevich.zigate.CALL_NOW"
        const val EXTRA_PHONE = "phone"
    }
}
