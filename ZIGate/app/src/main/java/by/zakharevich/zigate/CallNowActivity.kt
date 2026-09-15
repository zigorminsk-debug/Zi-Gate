package by.zakharevich.zigate

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import by.zakharevich.zigate.util.DialHelper

/** Unlocks the screen if needed and immediately dials the barrier number. */
class CallNowActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turnScreenOn()
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        DialHelper.placeCall(this, phone)
        finish()
    }

    private fun turnScreenOn() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        runCatching {
            val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            if (Build.VERSION.SDK_INT >= 26) {
                km?.requestDismissKeyguard(this, null)
            }
        }
    }

    companion object {
        const val EXTRA_PHONE = "phone"
    }
}
