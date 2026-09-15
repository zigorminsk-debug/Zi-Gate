package by.zakharevich.zigate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Shows an uncaught exception full-screen. Built programmatically (no layout
 * inflate) so it cannot itself fail to render. Launched by the crash reporter
 * in [App]; the user can read the message and copy it to send back. Exits the
 * process when dismissed.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = intent.getStringExtra(EXTRA_MESSAGE)
            ?: "Неизвестная ошибка (нет данных о стектрейсе)."

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val header = TextView(this).apply {
            text = "ZI Gate: ошибка"
            textSize = 22f
            setTextColor(0xFFCF4436.toInt())
            setPadding(pad, pad, pad, (8 * density).toInt())
        }

        val crashText = TextView(this).apply {
            text = message
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }

        val scroll = ScrollView(this).apply {
            addView(crashText)
        }

        val copyBtn = Button(this).apply {
            text = "📋 Скопировать"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("zigate_crash", message))
            }
        }
        val exitBtn = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { finishAffinity(); android.os.Process.killProcess(android.os.Process.myPid()) }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(copyBtn)
            addView(exitBtn)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(btnRow)
        }
        setContentView(root)
    }

    override fun onBackPressed() {
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_MESSAGE = "extra_message"
    }
}
