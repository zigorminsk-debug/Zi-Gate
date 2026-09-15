package by.zakharevich.zigate.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Adaptive GPS polling.
 *
 * Near the zone: fixed bands (1 / 2 / 3 / 10 s), then a distance curve.
 * Always also apply a **time-to-zone lookahead**: if we are approaching,
 * the period cannot be longer than ~1/3 of the ETA, so a 60 s sleep cannot
 * skip a 200 m gate at city speed.
 *
 * Extra knobs from the service:
 *  - [charging]  → 1 s, no jitter (power is free)
 *  - [onRoute]   → learned commute corridor, poll ~2.5× more often
 *  - [stationary] far away → up to 90 s to save battery
 */
object AdaptivePolling {

    const val MIN_PERIOD_MS = 1_000L
    const val MAX_PERIOD_MS = 60_000L
    const val MAX_STATIONARY_MS = 90_000L
    const val CHARGE_PERIOD_MS = 1_000L

    private val NEAR_PERIOD_BANDS = listOf(
        50f to MIN_PERIOD_MS,
        100f to 2_000L,
        150f to 3_000L,
        200f to 10_000L,
    )

    private const val P_SHAPE = 1.3f
    private const val MIN_L = 200f
    private const val SPEED_RESPONSE = 0.08f
    private const val SPEED_FACTOR_MIN = 0.2f
    private const val SPEED_FACTOR_MAX = 3.0f
    private const val SPEED_CAP = 40.0f
    private const val JITTER = 0.05f
    /** Want this many samples before the car reaches the boundary. */
    private const val LOOKAHEAD_SAMPLES = 3f

    fun intervalMs(
        distanceMeters: Float,
        zoneRadiusM: Float,
        radialSpeedMs: Float = 0f,
        charging: Boolean = false,
        onRoute: Boolean = false,
        stationary: Boolean = false
    ): Long {
        if (charging) return CHARGE_PERIOD_MS

        val r = max(zoneRadiusM, 1f)
        val d = max(distanceMeters, 0f)
        val beyond = d - r
        val v = radialSpeedMs.coerceIn(-SPEED_CAP, SPEED_CAP)

        var period: Long
        var inNearBand = false
        if (beyond <= 200f) {
            inNearBand = true
            period = MIN_PERIOD_MS
            for ((band, bandPeriod) in NEAR_PERIOD_BANDS) {
                if (beyond <= band) {
                    period = bandPeriod
                    break
                }
            }
        } else {
            val l = max(MIN_L, r * 1.5f)
            var x = beyond / l
            if (x < 0f) x = 0f
            if (x > 15f) x = 15f
            val closeness = exp(-x.pow(P_SHAPE))
            val maxP = if (stationary) MAX_STATIONARY_MS else MAX_PERIOD_MS
            val basePeriod = MIN_PERIOD_MS +
                    ((maxP - MIN_PERIOD_MS).toFloat() * (1f - closeness)).toLong()
            val factor = (1f + v * SPEED_RESPONSE).coerceIn(SPEED_FACTOR_MIN, SPEED_FACTOR_MAX)
            period = (basePeriod * factor).toLong()
        }

        // Time-to-zone: never sleep longer than 1/3 of ETA while approaching.
        if (v < -0.4f && beyond > 0f) {
            val etaMs = (beyond / -v) * 1000f
            val look = (etaMs / LOOKAHEAD_SAMPLES).toLong()
            period = min(period, look.coerceAtLeast(MIN_PERIOD_MS))
        }

        if (onRoute && !inNearBand) {
            period = (period * 0.4f).toLong()
        }

        val maxAllowed = if (stationary && beyond > 400f) MAX_STATIONARY_MS else MAX_PERIOD_MS
        period = period.coerceIn(MIN_PERIOD_MS, maxAllowed)

        if (!inNearBand && !charging) {
            val j = period * JITTER
            val jittered = period + (System.nanoTime() % (2L * j.toLong() + 1)) - j.toLong()
            period = jittered.coerceIn(MIN_PERIOD_MS, maxAllowed)
        }
        return period
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).toFloat()
    }

    /** Initial bearing from A to B, degrees 0..360. */
    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lng2 - lng1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        var brng = Math.toDegrees(atan2(y, x))
        if (brng < 0) brng += 360.0
        return brng.toFloat()
    }

    /**
     * Radial speed toward a target (m/s). Negative = approaching.
     * Uses GPS speed/bearing when present (much less noisy than Δd).
     */
    fun radialSpeedFromGps(
        speedMs: Float,
        bearingDeg: Float,
        bearingToTargetDeg: Float
    ): Float {
        val delta = Math.toRadians((bearingDeg - bearingToTargetDeg).toDouble())
        return (-speedMs * cos(delta)).toFloat()
    }
}
