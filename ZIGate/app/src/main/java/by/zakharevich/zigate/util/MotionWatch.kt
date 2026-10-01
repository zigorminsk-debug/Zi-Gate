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
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rest vs pull-away. GPS speed is the teacher: while GPS says we are
 * stopped, accel/gyro noise is learned as the idle baseline. A start is
 * only accepted if sensors stay above that baseline, or GPS speed rises.
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
    /** Learned idle noise while GPS speed ≈ 0. */
    var restAccel: Float = 0.25f
        private set
    var restGyro: Float = 0.12f
        private set

    private var lastMoveElapsed = SystemClock.elapsedRealtime()
    private var rest = true
    private var started = false
    private var lastUi = 0L
    private var aboveCount = 0
    private var lastGpsElapsed = 0L
    private var lastGpsSpeed = -1f
    private var gpsStoppedSince = 0L

    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // Only a hint: wait for GPS or a sustained sensor burst.
            aboveCount = max(aboveCount, HOLD_SAMPLES / 2)
            armSignificant()
        }
    }

    fun start() {
        if (started) return
        started = true
        lastMoveElapsed = SystemClock.elapsedRealtime()
        rest = true
        accel?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler) }
        gyro?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler) }
        armSignificant()
    }

    fun stop() {
        started = false
        runCatching { sm?.unregisterListener(this) }
        runCatching { significant?.let { sm?.cancelTriggerSensor(trigger, it) } }
    }

    fun isRest(): Boolean = rest

    /**
     * GPS lesson. Speed < 0.4 m/s with a decent fix = standing (learn noise).
     * Speed ≥ 1.2 m/s = moving.
     */
    fun noteGps(speedMs: Float, accuracyM: Float, gps: Boolean): Boolean {
        if (!gps || accuracyM > 25f) return false
        val now = SystemClock.elapsedRealtime()
        lastGpsElapsed = now
        lastGpsSpeed = speedMs
        if (speedMs < 0.4f) {
            if (gpsStoppedSince == 0L) gpsStoppedSince = now
            if (now - gpsStoppedSince > 1_500L) {
                restAccel = restAccel * 0.92f + emaAccel * 0.08f
                restGyro = restGyro * 0.92f + emaGyro * 0.08f
                restAccel = restAccel.coerceIn(0.12f, 0.9f)
                restGyro = restGyro.coerceIn(0.05f, 0.45f)
                if (!rest) {
                    rest = true
                    aboveCount = 0
                }
            }
            return false
        }
        gpsStoppedSince = 0L
        if (speedMs >= 1.2f) {
            val was = rest
            rest = false
            lastMoveElapsed = now
            aboveCount = 0
            if (was) {
                onMoved()
                return true
            }
        }
        return false
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
                emaAccel = emaAccel * 0.9f + linear * 0.1f
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val w = sqrt(x * x + y * y + z * z)
                gyroRad = w
                emaGyro = emaGyro * 0.88f + w * 0.12f
            }
            else -> return
        }

        val now = SystemClock.elapsedRealtime()
        val gpsFresh = lastGpsElapsed != 0L && now - lastGpsElapsed < 8_000L
        val gpsStopped = gpsFresh && lastGpsSpeed >= 0f && lastGpsSpeed < 0.4f &&
                gpsStoppedSince != 0L && now - gpsStoppedSince > 1_500L

        val aNeed = restAccel + 0.55f
        val gNeed = restGyro + 0.30f
        val burst = emaAccel > aNeed && (emaGyro > gNeed || emaAccel > restAccel + 1.1f)
        if (gpsStopped) {
            aboveCount = 0
        } else if (burst) {
            aboveCount++
            if (aboveCount >= HOLD_SAMPLES && rest) {
                rest = false
                lastMoveElapsed = now
                aboveCount = 0
                onMoved()
            }
        } else {
            aboveCount = 0
            if (!rest && now - lastMoveElapsed > REST_AFTER_MS &&
                (!gpsFresh || lastGpsSpeed < 0.5f)
            ) {
                rest = true
            }
        }

        if (now - lastUi > 900L) {
            lastUi = now
            onSample?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun armSignificant() {
        val s = significant ?: return
        runCatching { sm?.requestTriggerSensor(trigger, s) }
    }

    companion object {
        private const val REST_AFTER_MS = 20_000L
        private const val HOLD_SAMPLES = 14
    }
}
