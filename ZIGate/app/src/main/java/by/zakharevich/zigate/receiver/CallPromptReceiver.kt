package by.zakharevich.zigate.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Notification action: user confirmed the call. */
class CallPromptReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CALL_NOW) return
        val phone = intent.getStringExtra(EXTRA_PHONE)?.trim()?.replace(" ", "")?.replace("-", "")
            ?: return
        if (phone.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
            return
        }
        val uri = Uri.parse("tel:$phone")
        val ok = runCatching {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (tm != null) {
                tm.placeCall(uri, android.os.Bundle())
                true
            } else false
        }.getOrDefault(false)
        if (ok) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.w("ZIGATE", "CallPromptReceiver: ${it.message}")
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    companion object {
        const val ACTION_CALL_NOW = "by.zakharevich.zigate.CALL_NOW"
        const val EXTRA_PHONE = "phone"
    }
}
