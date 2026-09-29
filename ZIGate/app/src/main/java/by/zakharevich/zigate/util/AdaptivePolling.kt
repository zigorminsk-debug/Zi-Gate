package by.zakharevich.zigate.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polling while moving (service overrides rest / idle / Wi-Fi / charge).
 *
 * Absolute distance to the gate:
 *   ≤ 80 m  → 1 s GPS
 *   ≤ 150 m → 2 s
 *   ≤ 250 m → 3 s
 *   ≤ 400 m → 5 s
 *   ≤ 800 m → 15 s
 *   else    → 30–60 s
 *
 * Approaching: period ≤ ETA/3 so a 5 m zone cannot be skipped at city speed.
 * Stationary far (>400 m): up to 60 s. Charge: 1 s.
 */
object AdaptivePolling {

    const val MIN_PERIOD_MS = 1_000L
    const val MAX_PERIOD_MS = 60_000L
    const val MAX_STATIONARY_MS = 60_000L
    const val CHARGE_PERIOD_MS = 1_000L

    private const val SPEED_CAP = 40.0f
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
        val beyond = max(d - r, 0f)
        val v = radialSpeedMs.coerceIn(-SPEED_CAP, SPEED_CAP)

        var period = when {
            d <= 120f -> 1_000L
            d <= 200f -> 2_000L
            d <= 300f -> 3_000L
            d <= 500f -> 5_000L
            d <= 800f -> 10_000L
            d <= 1500f -> 15_000L
            else -> 20_000L
        }

        // Never slow down while closing on the gate (city speed).
        if (stationary && d > 1500f && v >= -0.3f) period = MAX_STATIONARY_MS

        if (v < -0.4f && beyond > 0f) {
            val etaMs = (beyond / -v) * 1000f
            val look = (etaMs / LOOKAHEAD_SAMPLES).toLong()
            period = min(period, look.coerceAtLeast(MIN_PERIOD_MS))
        }

        if (onRoute && d > 400f && v < 0f) {
            period = (period * 0.5f).toLong().coerceAtLeast(MIN_PERIOD_MS)
        }

        return period.coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val earth = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earth * c).toFloat()
    }

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

    fun radialSpeedFromGps(
        speedMs: Float,
        bearingDeg: Float,
        bearingToTargetDeg: Float
    ): Float {
        val delta = Math.toRadians((bearingDeg - bearingToTargetDeg).toDouble())
        return (-speedMs * cos(delta)).toFloat()
    }
}
