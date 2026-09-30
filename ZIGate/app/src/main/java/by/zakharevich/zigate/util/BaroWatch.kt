package by.zakharevich.zigate.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.pow

/**
 * Relative height from the barometer. Ground pressure is learned while
 * moving outdoors (GPS + speed); upstairs is then ~3 m per floor.
 */
class BaroWatch(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private var started = false

    /** Smoothed pressure, hPa. */
    var pressureHpa: Float? = null
        private set
    var groundHpa: Float? = null
        private set
    private var groundElapsed: Long = 0L

    val available: Boolean get() = sensor != null

    fun start() {
        if (started || sensor == null) return
        started = true
        sm?.registerListener(
            this, sensor, SensorManager.SENSOR_DELAY_NORMAL,
            Handler(Looper.getMainLooper())
        )
    }

    fun stop() {
        started = false
        runCatching { sm?.unregisterListener(this) }
    }

    /** Metres above the last learned ground. Null if unknown. */
    fun heightM(): Float? {
        val p = pressureHpa ?: return null
        val g = groundHpa ?: return null
        if (p < 300f || p > 1100f || g < 300f || g > 1100f) return null
        val hP = altitudeM(p)
        val hG = altitudeM(g)
        return (hP - hG).coerceIn(-20f, 80f)
    }

    /** 1 = ground floor. */
    fun floor(): Int? {
        val h = heightM() ?: return null
        if (h < 2.8f) return 1
        return 1 + ((h + 0.6f) / FLOOR_M).toInt().coerceAtLeast(1)
    }

    /** True if clearly upstairs (cannot drive out of a gate). */
    fun upstairs(): Boolean {
        val h = heightM() ?: return false
        val f = floor() ?: return false
        return h >= UPSTAIRS_M && f >= 2
    }

    /**
     * Learn ground while moving with GPS (car / walk outside).
     * Weather drift is absorbed because we refresh often outdoors.
     */
    fun noteGround(gpsSpeedMs: Float, accuracyM: Float, outdoors: Boolean) {
        val p = pressureHpa ?: return
        if (!outdoors) return
        if (accuracyM > 35f) return
        if (gpsSpeedMs < 1.2f) return
        val now = SystemClock.elapsedRealtime()
        val g = groundHpa
        if (g == null || now - groundElapsed > 30_000L) {
            groundHpa = p
            groundElapsed = now
        } else {
            groundHpa = g * 0.7f + p * 0.3f
            groundElapsed = now
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return
        val p = event.values[0]
        if (p < 300f || p > 1100f) return
        val prev = pressureHpa
        pressureHpa = if (prev == null) p else prev * 0.85f + p * 0.15f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val FLOOR_M = 3.2f
        const val UPSTAIRS_M = 3.2f

        fun altitudeM(hPa: Float): Float {
            val x = (hPa / 1013.25f).toDouble().coerceIn(0.5, 1.2)
            return (44330.0 * (1.0 - x.pow(0.190266))).toFloat()
        }
    }
}
