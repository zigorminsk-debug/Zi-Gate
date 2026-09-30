package by.zakharevich.zigate.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cheap rest / motion from the accelerometer (+ significant-motion trigger).
 * Tiny jitters (table, engine idle) are ignored; a real start of movement
 * is reported so GPS can wake.
 */
class MotionWatch(
    context: Context,
    private val onMoved: () -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val significant = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private var ema = 0f
    private var lastMoveElapsed = SystemClock.elapsedRealtime()
    private var rest = false
    private var started = false

    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            markMoved()
            armSignificant()
        }
    }

    fun start() {
        if (started) return
        started = true
        lastMoveElapsed = SystemClock.elapsedRealtime()
        rest = false
        accel?.let {
            sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        armSignificant()
    }

    fun stop() {
        started = false
        runCatching { sm?.unregisterListener(this) }
        runCatching { significant?.let { sm?.cancelTriggerSensor(trigger, it) } }
    }

    /** True after [REST_AFTER_MS] without filtered motion. */
    fun isRest(): Boolean {
        tickRest()
        return rest
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        val linear = abs(mag - SensorManager.GRAVITY_EARTH)
        ema = ema * 0.88f + linear * 0.12f
        if (ema > MOVE_EMA || linear > MOVE_PEAK) {
            markMoved()
        } else {
            tickRest()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun markMoved() {
        val wasRest = rest
        lastMoveElapsed = SystemClock.elapsedRealtime()
        rest = false
        if (wasRest) onMoved()
    }

    private fun tickRest() {
        if (rest) return
        if (SystemClock.elapsedRealtime() - lastMoveElapsed > REST_AFTER_MS) {
            rest = true
        }
    }

    private fun armSignificant() {
        val s = significant ?: return
        runCatching { sm?.requestTriggerSensor(trigger, s) }
    }

    companion object {
        private const val REST_AFTER_MS = 25_000L
        /** Ignore table / idle-engine wobble. */
        private const val MOVE_EMA = 0.42f
        private const val MOVE_PEAK = 1.1f
    }
}
