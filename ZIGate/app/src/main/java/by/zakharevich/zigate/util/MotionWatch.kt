package by.zakharevich.zigate.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Accel + gyro: rest at a light vs the car pulling away.
 * Tiny engine wobble is ignored.
 */
class MotionWatch(
    context: Context,
    private val onMoved: () -> Unit,
    private val onSample: (() -> Unit)? = null
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val significant = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val handler = Handler(Looper.getMainLooper())

    private var emaAccel = 0f
    private var emaGyro = 0f
    var accelMs2: Float = 0f
        private set
    var gyroRad: Float = 0f
        private set
    private var lastMoveElapsed = SystemClock.elapsedRealtime()
    private var rest = false
    private var started = false
    private var lastUi = 0L

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
        accel?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler) }
        gyro?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler) }
        armSignificant()
    }

    fun stop() {
        started = false
        runCatching { sm?.unregisterListener(this) }
        runCatching { significant?.let { sm?.cancelTriggerSensor(trigger, it) } }
    }

    fun isRest(): Boolean {
        tickRest()
        return rest
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt(x * x + y * y + z * z)
                val linear = abs(mag - SensorManager.GRAVITY_EARTH)
                accelMs2 = linear
                emaAccel = emaAccel * 0.88f + linear * 0.12f
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val w = sqrt(x * x + y * y + z * z)
                gyroRad = w
                emaGyro = emaGyro * 0.85f + w * 0.15f
            }
            else -> return
        }
        val pull = emaAccel > MOVE_EMA || accelMs2 > MOVE_PEAK ||
                emaGyro > GYRO_EMA || gyroRad > GYRO_PEAK
        if (pull) markMoved() else tickRest()
        val now = SystemClock.elapsedRealtime()
        if (now - lastUi > 800L) {
            lastUi = now
            onSample?.invoke()
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
        private const val REST_AFTER_MS = 18_000L
        private const val MOVE_EMA = 0.38f
        private const val MOVE_PEAK = 0.95f
        private const val GYRO_EMA = 0.22f
        private const val GYRO_PEAK = 0.55f
    }
}
