package by.zakharevich.zigate.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

object DialHelper {
    private const val TAG = "ZIGATE"

    fun normalize(phone: String): String =
        phone.trim().replace(" ", "").replace("-", "")

    @SuppressLint("MissingPermission")
    fun placeCall(context: Context, rawPhone: String): Boolean {
        val phone = normalize(rawPhone)
        if (phone.isEmpty()) return false
        val uri = Uri.parse("tel:$phone")
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            val telecomOk = runCatching {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (tm != null && Build.VERSION.SDK_INT >= 23) {
                    tm.placeCall(uri, android.os.Bundle())
                    Log.i(TAG, "DialHelper: TelecomManager $phone")
                    true
                } else false
            }.getOrDefault(false)
            if (telecomOk) return true
            val callOk = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                )
                Log.i(TAG, "DialHelper: ACTION_CALL $phone")
                true
            }.getOrDefault(false)
            if (callOk) return true
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }
}
