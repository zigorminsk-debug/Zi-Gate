package by.zakharevich.zigate.util

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Adaptive GPS polling model — the core "battery-saving" math of ZI Gate.
 *
 * The polling period is a function of two things:
 *   1. the phone's distance **d** to the nearest barrier centre (radius **r**);
 *   2. the **speed** of that distance as it changes over time (approaching
 *      the barrier vs. receding from it).
 *
 * Both effects make the model "look ahead" and adapt, so we neither waste
 * battery far away nor miss the barrier when driving towards it fast.
 *
 *
 * PART 0 — deterministic near-zone bands
 * --------------------------------------
 * By explicit request, the polling period no longer follows a purely smooth
 * curve approaching the gate. Instead, within 200 m of the zone boundary the
 * interval takes EXACT fixed values that do NOT depend on velocity or jitter:
 *
 *   beyond boundary (m)   period
 *   ------------------   -------
 *        0 … 50            1 s
 *        0 … 100           2 s
 *        0 … 150           3 s
 *        0 … 200          10 s
 *       > 200            adaptive curve below
 *
 * ("beyond" = distance to barrier centre − zone radius; inside the zone, i.e.
 * beyond ≤ 0, the first band applies → 1 s.)
 *
 *
 * PART 1 — distance-based period (used only beyond 200 m of the boundary)
 * -----------------------------------------------------------------------
 * The closer we are, the more often we ask for a fix; the further we are, the
 * more rarely we ask, to save battery.
 *
 *   minP  – minimum period (1 s)   ·   maxP – maximum period (60 s)
 *   r     – radius of the nearest barrier zone (m)
 *   L     – "relaxation length": L = max(200, 1.5 * r) m
 *   p     – shape exponent (1.3) – how sharply the period switches
 *
 *   x = max(d - r, 0) / L              (x = 0 right at the boundary)
 *   c = exp(-x^p)                       (1 at boundary → 0 far away)
 *   T_base(d) = minP + (maxP - minP) * (1 - c)
 *
 *
 * PART 2 — velocity factor
 * ------------------------
 * The interval is scaled by how fast the distance is changing, so that we
 * never "sleep through" the target:
 *
 *   v = d(distance)/dt  in metres per second
 *        v < 0  →  distance is shrinking  → we are APPROACHING  (closing in)
 *        v > 0  →  distance is growing    → we are RECEDING     (moving away)
 *
 *   T(d, v) = T_base(d) * F(v)
 *
 *   F(v) = clamp(1 + v * k, F_MIN, F_MAX)
 *
 * so:
 *   – the faster we APPROACH (v more negative)  → F < 1 → SHORTER interval
 *     (we poll more often, so the moment we cross the boundary is not missed);
 *   – the faster we RECEDE (v more positive)   → F > 1 → LONGER interval
 *     (we can afford to wait — the barrier is getting further away anyway);
 *   – standing still (v ≈ 0)                   → F ≈ 1 → base distance curve.
 *
 *   k      – speed response (seconds per metre/second): how strongly speed
 *            changes the interval.           (0.06 s / (m/s))
 *   F_MIN  – floor of the speed factor (never shorter than 0.25x)
 *   F_MAX  – ceiling of the speed factor (never longer than 4x)
 *
 * Finally the result is clamped to [minP, maxP] and a ±5% jitter is added so
 * many phones in the same area do not all wake at the same instant.
 */
object AdaptivePolling {

    /** Fastest polling period – at/inside the trigger zone (or its fast band). */
    const val MIN_PERIOD_MS = 1_000L

    /** Slowest polling period – far away from every barrier. */
    const val MAX_PERIOD_MS = 60_000L

    /**
     * Fixed near-zone polling bands, measured in **metres beyond the zone
     * boundary** (i.e. (distance to barrier centre) − (zone radius)). Within
     * these windows the polling period is EXACTLY the requested value and does
     * not depend on velocity or the smooth distance curve, so the phone polls
     * as fast as the user asked as it closes in on the gate:
     *
     *   0 … 50 m beyond boundary → 1 s
     *   0 … 100 m beyond        → 2 s
     *   0 … 150 m beyond        → 3 s
     *   0 … 200 m beyond        → 10 s
     *   beyond 200 m            → adaptive distance + velocity curve below
     *
     * (Inside the zone, i.e. "beyond" ≤ 0, the first band applies → 1 s.)
     */
    private val NEAR_PERIOD_BANDS = listOf(
        (50f to MIN_PERIOD_MS),      // 1 s
        (100f to 2_000L),            // 2 s
        (150f to 3_000L),            // 3 s
        (200f to 10_000L),           // 10 s
    )

    /** Shape exponent p (used in c = exp(-x^p)). */
    private const val P_SHAPE = 1.3f

    /** Lower bound for the relaxation length (m). */
    private const val MIN_L = 200f

    /** Speed response: seconds of interval change per (m/s) of radial speed. */
    private const val SPEED_RESPONSE = 0.06f

    /** Speed factor bounds. */
    private const val SPEED_FACTOR_MIN = 0.25f
    private const val SPEED_FACTOR_MAX = 4.0f

    /** Cap on radial speed used by the model (90 km/h ≈ 25 m/s). */
    private const val SPEED_CAP = 25.0f

    /** Adds ±5% jitter to reduce call-wake collisions. */
    private const val JITTER = 0.05f

    /**
     * @param distanceMeters current distance to the nearest barrier centre.
     * @param zoneRadiusM    radius of that barrier's trigger zone.
     * @param radialSpeedMs  rate of change of distance (m/s). Negative =
     *                       approaching, positive = receding, 0 = stationary.
     */
    fun intervalMs(distanceMeters: Float, zoneRadiusM: Float, radialSpeedMs: Float = 0f): Long {
        val r = max(zoneRadiusM, 1f)
        val d = max(distanceMeters, 0f)

        // ---- PART 0: deterministic near-zone polling bands ----
        // "beyond" = metres past the zone boundary (≤ 0 when inside the zone).
        // Within the band windows we return the EXACT requested period, with no
        // velocity influence and no jitter, so the phone polls at precisely the
        // rates the user asked for as it approaches the gate.
        val beyond = d - r
        for ((band, bandPeriod) in NEAR_PERIOD_BANDS) {
            if (beyond <= band) {
                return bandPeriod.coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
            }
        }

        // ---- PART 1: distance-based period (used beyond 200 m of the boundary) ----
        val l = max(MIN_L, r * 1.5f)

        var x = (d - r) / l
        if (x < 0f) x = 0f
        if (x > 15f) x = 15f

        val closeness = exp(-x.pow(P_SHAPE))            // 1 → 0
        val basePeriod = MIN_PERIOD_MS +
                ((MAX_PERIOD_MS - MIN_PERIOD_MS).toFloat() * (1f - closeness)).toLong()

        // ---- PART 2: velocity factor ----
        val v = radialSpeedMs.coerceIn(-SPEED_CAP, SPEED_CAP)
        val factor = (1f + v * SPEED_RESPONSE).coerceIn(SPEED_FACTOR_MIN, SPEED_FACTOR_MAX)

        var period = (basePeriod * factor).toLong()
        period = period.coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)

        // Add jitter (only outside the fixed bands).
        val j = period * JITTER
        val jittered = period + (System.nanoTime() % (2L * j.toLong() + 1)) - j.toLong()
        return jittered.coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
    }

    /** Distance in metres between two coordinates (Haversine). */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (r * c).toFloat()
    }
}
