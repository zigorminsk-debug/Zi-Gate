package by.zakharevich.zigate.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import by.zakharevich.zigate.App
import by.zakharevich.zigate.R
import by.zakharevich.zigate.data.BarrierStore
import by.zakharevich.zigate.data.Settings
import by.zakharevich.zigate.model.Barrier
import by.zakharevich.zigate.util.AdaptivePolling
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that runs permanently and:
 *  1. Tracks the phone position, choosing the cheapest sufficient provider:
 *     NETWORK location far from every barrier (almost no battery), GPS within
 *     ~300 m of a barrier where metre-level accuracy actually matters;
 *  2. Resamples the position at an adaptive rate (see [AdaptivePolling]);
 *  3. When the phone enters a barrier zone – places a call to the barrier
 *     number automatically (ACTION_CALL, no extra prompts;
 *     the user granted CALL_PHONE at setup);
 *  4. Suspends all polling while connected to any of the user-selected
 *     Wi-Fi networks and resumes it with a short warm-up burst.
 *
 * Distance filtering (v1.14+): bad fixes are dropped, a short warm-up burst
 * re-measures the position after a Wi-Fi pause, stationary drift is frozen,
 * and a single coarse fix can never place a call.
 */
class BarrierService : Service() {

    private var lm: LocationManager? = null
    private var wm: WifiManager? = null
    private var cm: ConnectivityManager? = null

    private var registered = false
    private var currentIntervalMs = -1L
    /** Provider currently registered with LocationManager (null = none). */
    private var currentProvider: String? = null

    // Stable (filtered) position: raw GPS jitter while standing still never
    // reaches these fields, so the displayed distance does not jump 29->70 m.
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastAccuracy: Float? = null
    private var lastFixTimeMs: Long = 0L
    private var hasFix = false

    // Velocity-aware polling: previous distance to the nearest barrier, used
    // to estimate the radial approach/recede speed (m/s).
    private var prevDistToNearest: Float? = null
    private var prevFixNanos: Long = 0L
    private var prevNearestId: String? = null
    private var lastRadialVelocity = 0f

    /** Number of fast fixes still to collect during the short warm-up burst
     *  after resuming from a WiFi pause (or on a cold start with no fix yet).
     *  While > 0 we poll at WARMUP_PERIOD_MS. Fixes collected during the
     *  burst do NOT move the stable position one-by-one: when the burst ends
     *  the single BEST fix (smallest accuracy) becomes the new position, so
     *  one coarse first fix (e.g. the false "7 m") can never flash on screen
     *  or trigger a call. */
    private var warmupRemaining = 0
    private val warmupFixes = mutableListOf<Location>()
    private var warmupDeadlineMs: Long = 0L

    /** Consecutive inside-zone fixes per barrier (false-call guard). */
    private val consecutiveInside = mutableMapOf<String, Int>()

    private var pausedByWifi = false
    private var currentSsid: String? = null

    private var barriers: MutableList<Barrier> = mutableListOf()
    private var wifiPause: Set<String> = emptySet()

    companion object {
        const val ACTION_REFRESH = "by.zakharevich.zigate.REFRESH"
        const val ACTION_START = "by.zakharevich.zigate.START"
        const val ACTION_STOP = "by.zakharevich.zigate.STOP"
        private const val NOTIF_ID = 1001
        private const val TAG = "ZIGATE"

        /** Number of fast fixes to collect during the warm-up burst. */
        private const val WARMUP_FIXES = 6
        /** Poll period used while the warm-up burst is in progress (1 s). */
        private const val WARMUP_PERIOD_MS = 1_000L
        /** Warm-up never lasts longer than this (safety net for bad GPS). */
        private const val WARMUP_TIMEOUT_MS = 25_000L
        /** GPS fixes coarser than this are dropped (cold-start junk). */
        private const val GPS_FIX_ACCURACY_MAX_M = 80f
        /** Network fixes are allowed to be coarser: far away we only need to
         *  know the approximate distance, not the exact metre. */
        private const val NET_FIX_ACCURACY_MAX_M = 200f
        /** Fixes older than this are dropped as stale. */
        private const val MAX_FIX_AGE_MS = 120_000L
        /** Speed (m/s) at/above which we treat the phone as really moving. */
        private const val MOVING_SPEED_MS = 1.2f
        /** Radial speed is only estimated from fixes at least this accurate,
         *  so noisy coarse fixes cannot whip the polling model around. */
        private const val VELOCITY_ACC_MAX_M = 50f

        // ---- provider regime (battery optimisation, v1.16) ----
        /** Within this distance to the nearest barrier we run full GPS:
         *  metre accuracy is needed for the trigger and the fast bands. */
        private const val GPS_REGIME_M = 300f
        /** Above this distance we switch to the (cheap) network provider.
         *  The 300..400 m gap is hysteresis so the provider does not flap. */
        private const val NET_REGIME_M = 400f
        /** Far away the network provider is nearly free, so a responsive cap
         *  (instead of the 60 s GPS maximum) keeps the distance picture fresh
         *  at almost no battery cost. Tiered: beyond 1 km 60 s is plenty
         *  (nothing happens there), within 1 km 30 s detects the approach
         *  sooner. While actually driving towards the gate the velocity
         *  factor shortens the period below the cap automatically. */
        private const val NET_MAX_PERIOD_NEAR_MS = 30_000L
        private const val NET_MAX_PERIOD_FAR_MS = 60_000L
        private const val NET_FAR_BOUNDARY_M = 1000f

        private val statusListeners = CopyOnWriteArrayList<(ServiceStatus) -> Unit>()

        fun addListener(fn: (ServiceStatus) -> Unit) { statusListeners.add(fn) }
        fun removeListener(fn: (ServiceStatus) -> Unit) { statusListeners.remove(fn) }

        fun emit(status: ServiceStatus) {
            statusListeners.forEach { runCatching { it(status) } }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    recomputeWifiState()
                    updateLocationRegistration()
                    publishStatus()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val locationListener = LocationListener { loc ->
        onLocation(loc)
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager

        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        ContextCompat.registerReceiver(
            this, wifiReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START or refresh - loads fresh settings/barriers
                barriers = BarrierStore.load(this)
                wifiPause = if (Settings.isWifiGateEnabled(this))
                    Settings.wifiPauseSet(this) else emptySet()
                // Barrier set changed (edit/import) -> reset per-barrier counters.
                consecutiveInside.clear()
                recomputeWifiState()
                updateLocationRegistration()
                publishStatus()
            }
        }
        return START_STICKY
    }

    // ---------------- foreground ----------------
    private fun startAsForeground() {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, by.zakharevich.zigate.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForegroundCompat(notif)
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Preferred: location-type foreground service (needs the
                // FOREGROUND_SERVICE_LOCATION permission, already requested).
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
                return
            } catch (_: Exception) {
                // Fall back to the type-agnostic call so the service still
                // starts (avoids ForegroundServiceDidNotStartInTimeException).
            }
        }
        try {
            startForeground(NOTIF_ID, notif)
        } catch (_: Exception) {
            // Nothing else we can do — stop rather than crash.
            stopSelf()
        }
    }

    // ---------------- provider choice ----------------
    /** Distance from the stable position to the nearest barrier (m) or null. */
    private fun distanceToNearest(): Float? {
        if (lastLat == null || lastLng == null) return null
        val nearest = barriers.filter { it.enabled }.minByOrNull {
            AdaptivePolling.distanceMeters(lastLat!!, lastLng!!, it.lat, it.lng)
        } ?: return null
        return AdaptivePolling.distanceMeters(lastLat!!, lastLng!!, nearest.lat, nearest.lng)
    }

    /** Picks the cheapest sufficient provider (battery optimisation):
     *  - GPS near barriers (<= 300 m) and whenever network is unavailable —
     *    the trigger and the 1/2/3/10 s bands need metre-level accuracy;
     *  - NETWORK provider far away (> 400 m) or while we have no fix at all —
     *    it costs almost nothing compared to a GPS radio that would otherwise
     *    search continuously all day (e.g.indoors at the office).
     *  300..400 m = hysteresis: keep the current provider. */
    @SuppressLint("MissingPermission")
    private fun chooseProvider(distToNearest: Float?): String {
        val lm = lm ?: return LocationManager.GPS_PROVIDER
        val gpsOk = runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val netOk = runCatching {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)

        if (!netOk) return if (gpsOk) LocationManager.GPS_PROVIDER
        else LocationManager.PASSIVE_PROVIDER
        if (!gpsOk) return LocationManager.NETWORK_PROVIDER

        return when {
            distToNearest == null -> LocationManager.NETWORK_PROVIDER   // cold start: cheap first
            distToNearest < GPS_REGIME_M -> LocationManager.GPS_PROVIDER
            distToNearest > NET_REGIME_M -> LocationManager.NETWORK_PROVIDER
            else -> currentProvider ?: LocationManager.NETWORK_PROVIDER // hysteresis band
        }
    }

    /** Accuracy gate for a fix, depending on the active regime. */
    private fun maxAcceptableAccuracy(): Float =
        if (currentProvider == LocationManager.NETWORK_PROVIDER) NET_FIX_ACCURACY_MAX_M
        else GPS_FIX_ACCURACY_MAX_M

    // ---------------- location ----------------
    /** (Re-)registers location updates. Silent: publishing the status is the
     *  caller's job, so every code path emits the status exactly once. */
    @SuppressLint("MissingPermission")
    private fun updateLocationRegistration() {
        val lm = lm ?: return
        val autoOn = Settings.isAutoEnabled(this)
        val locPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

        if (!autoOn || pausedByWifi || !locPermission) {
            stopLocation()
            return
        }

        val dist = distanceToNearest()
        val provider = chooseProvider(dist)

        // Cold start: as soon as we begin polling but still have no fix, run a
        // short warm-up burst so the first fixes arrive promptly (otherwise the
        // adaptive model might request a 60 s period and the first fix would
        // wait a long time). The distance estimate then starts from real data.
        if (!hasFix && warmupRemaining == 0) {
            warmupRemaining = WARMUP_FIXES
            warmupFixes.clear()
            warmupDeadlineMs = SystemClock.elapsedRealtime() + WARMUP_TIMEOUT_MS
            prevDistToNearest = null
            prevFixNanos = 0L
            prevNearestId = null
            lastRadialVelocity = 0f
            consecutiveInside.clear()
        }

        // While the warm-up burst is running we poll at the fast, fixed rate so
        // the current position (and the distance to the nearest barrier) is
        // measured fresh before the adaptive model picks its real period.
        val period = if (warmupRemaining > 0) WARMUP_PERIOD_MS else periodFor(provider)
        if (registered && provider == currentProvider &&
            abs(period - currentIntervalMs) < 2_000
        ) return

        stopLocation()
        try {
            lm.requestLocationUpdates(provider, period, 0f, locationListener, Looper.getMainLooper())
            registered = true
            currentProvider = provider
            currentIntervalMs = period
        } catch (e: SecurityException) {
            registered = false
        }
    }

    /** Adaptive period for the given provider. On the network regime the
     *  period is capped at 30 s: network fixes are cheap, and the shorter
     *  period makes the far-distance picture refresh sooner. */
    private fun periodFor(provider: String): Long {
        val p = currentPeriod()
        if (provider != LocationManager.NETWORK_PROVIDER) return p
        val d = distanceToNearest()
        val cap = if (d == null || d > NET_FAR_BOUNDARY_M)
            NET_MAX_PERIOD_FAR_MS else NET_MAX_PERIOD_NEAR_MS
        return p.coerceAtMost(cap)
    }

    private fun stopLocation() {
        lm?.removeUpdates(locationListener)
        registered = false
        currentProvider = null
        currentIntervalMs = -1
    }

    /** Begin a short burst of fast fixes (e.g. after a WiFi disconnect) so
     *  the distance to the nearest barrier and the radial speed are measured
     *  afresh from the actual current position, not from a stale pre-pause
     *  estimate. This makes the adaptive polling model start correctly. */
    private fun startWarmup() {
        warmupRemaining = WARMUP_FIXES
        warmupFixes.clear()
        warmupDeadlineMs = SystemClock.elapsedRealtime() + WARMUP_TIMEOUT_MS
        // Discard the stale velocity/last-fix baseline so the first warm-up fix
        // establishes it and the following fixes compute a truthful radial speed.
        prevDistToNearest = null
        prevFixNanos = 0L
        prevNearestId = null
        lastRadialVelocity = 0f
        consecutiveInside.clear()
        updateLocationRegistration()
    }

    private fun onLocation(loc: Location) {
        // ---- 1. Drop obviously bad fixes (stale / coarse / mock / zero). ----
        if (!isUsableLocation(loc)) {
            Log.d(TAG, "drop bad fix prov=${loc.provider} " +
                    "acc=${if (loc.hasAccuracy()) loc.accuracy else -1f}")
            updateLocationRegistration()
            publishStatus()
            return
        }

        // ---- 2. Warm-up burst: collect, never flash intermediate jumps. ----
        // While warming up the stable position is NOT moved fix-by-fix. When
        // the burst ends, the single BEST fix (smallest accuracy) becomes the
        // new position. No triggers fire from intermediate warm-up fixes, so a
        // coarse first fix after a Wi-Fi disconnect can neither show a false
        // "7 m" nor place a false call.
        if (warmupRemaining > 0) {
            warmupFixes.add(loc)
            warmupRemaining--
            val timedOut = SystemClock.elapsedRealtime() > warmupDeadlineMs
            if (warmupRemaining == 0 || timedOut) {
                val best = warmupFixes.minByOrNull {
                    if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE
                }
                warmupFixes.clear()
                warmupRemaining = 0
                if (best != null) {
                    acceptFix(best, fromWarmup = true)
                    checkTriggers(best)
                }
            }
            updateLocationRegistration()
            publishStatus()
            return
        }

        // ---- 3. Stationary drift filter (the 29->70 m jumps fix). ----
        // A motionless phone still gets scattered points within ±accuracy.
        // If the shift is smaller than the combined accuracy and the speed
        // says we are not moving, the stable position stays untouched.
        // IMPORTANT: triggers are still checked against the frozen position,
        // so the repeat-call timer keeps working while the user stands inside
        // the zone (repeat every 5..180 s).
        if (hasFix && lastLat != null && lastLng != null) {
            val dMove = AdaptivePolling.distanceMeters(
                lastLat!!, lastLng!!, loc.latitude, loc.longitude
            )
            val newAcc = if (loc.hasAccuracy()) loc.accuracy else 20f
            val oldAcc = lastAccuracy ?: 20f
            val speed = if (loc.hasSpeed()) loc.speed else Float.NaN
            val moving = !speed.isNaN() && speed >= MOVING_SPEED_MS
            val driftRadius = (max(newAcc, oldAcc) * 1.2f + 8f).coerceIn(15f, 60f)
            if (!moving && dMove < driftRadius) {
                lastRadialVelocity *= 0.5f
                if (loc.hasAccuracy() &&
                    (lastAccuracy == null || loc.accuracy < lastAccuracy!!)
                ) {
                    lastAccuracy = loc.accuracy
                }
                // Keep the repeat-call clock running while standing in a zone.
                val frozen = Location("zigate-stable").apply {
                    latitude = lastLat!!
                    longitude = lastLng!!
                    if (loc.hasAccuracy()) accuracy = max(newAcc, oldAcc)
                }
                checkTriggers(frozen)
                updateLocationRegistration()
                publishStatus()
                return
            }
        }

        acceptFix(loc, fromWarmup = false)
        checkTriggers(loc)

        // Adapt the sampling rate to the current distance AND radial speed.
        updateLocationRegistration()
        publishStatus()
    }

    /** Accept a fix as the new stable position + update the radial speed. */
    private fun acceptFix(loc: Location, fromWarmup: Boolean) {
        val nowNanos = System.nanoTime()
        val firstFix = !hasFix
        lastLat = loc.latitude
        lastLng = loc.longitude
        lastAccuracy = if (loc.hasAccuracy()) loc.accuracy else null
        lastFixTimeMs = System.currentTimeMillis()
        hasFix = true

        val nearest = nearestBarrier()
        val distance = nearest?.let {
            AdaptivePolling.distanceMeters(
                loc.latitude, loc.longitude, it.lat, it.lng
            )
        }

        // Estimate radial speed: Δ(distance to nearest barrier) / Δt.
        // Only meaningful while looking at the SAME nearest barrier and only
        // from sufficiently accurate fixes (coarse network fixes would inject
        // ±tens of metres of fake motion). Smoothed: one bad pair cannot whip
        // the polling model around.
        val acc = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
        if (fromWarmup || firstFix || distance == null ||
            prevDistToNearest == null || prevNearestId != nearest?.id
        ) {
            lastRadialVelocity = 0f
        } else if (acc > VELOCITY_ACC_MAX_M) {
            lastRadialVelocity *= 0.5f
        } else {
            val dt = (nowNanos - prevFixNanos) / 1_000_000_000f
            if (dt > 0.5f) {
                // v < 0 -> approaching (distance shrinking), v > 0 -> receding.
                val vRaw = ((distance - prevDistToNearest!!) / dt).coerceIn(-25f, 25f)
                lastRadialVelocity = lastRadialVelocity * 0.5f + vRaw * 0.5f
            }
        }
        prevDistToNearest = distance
        prevFixNanos = nowNanos
        prevNearestId = nearest?.id
    }

    /** Sanity gate for raw fixes: rejects stale / coarse / mock / zero ones. */
    private fun isUsableLocation(loc: Location): Boolean {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return false
        if (loc.latitude !in -90.0..90.0 || loc.longitude !in -180.0..180.0) return false
        try {
            if (loc.isFromMockProvider) return false
        } catch (_: Exception) {
            // isFromMockProvider not available - ignore.
        }
        // Age by wall clock.
        if (loc.time == 0L) return false
        if (abs(System.currentTimeMillis() - loc.time) > MAX_FIX_AGE_MS) return false
        // Age by elapsed realtime (immune to clock changes).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val ageNs = SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos
                if (ageNs < 0 || ageNs > MAX_FIX_AGE_MS * 1_000_000L) return false
            }
        } catch (_: Exception) {
            // Not available - the wall-clock check above is enough.
        }
        // Coarse fixes only add jumps; the limit depends on the regime
        // (network far away is allowed to be much coarser than GPS).
        if (loc.hasAccuracy()) {
            val acc = loc.accuracy
            if (!acc.isFinite() || acc <= 0f || acc > maxAcceptableAccuracy()) return false
        }
        return true
    }

    private fun nearestBarrier(): Barrier? =
        if (lastLat == null || lastLng == null) null
        else barriers.filter { it.enabled }
            .minByOrNull {
                AdaptivePolling.distanceMeters(
                    lastLat!!, lastLng!!, it.lat, it.lng
                )
            }

    private fun currentPeriod(): Long {
        val nearest = nearestBarrier()
        if (nearest == null) return AdaptivePolling.MAX_PERIOD_MS
        val d = if (lastLat != null && lastLng != null)
            AdaptivePolling.distanceMeters(lastLat!!, lastLng!!, nearest.lat, nearest.lng)
        else AdaptivePolling.MAX_PERIOD_MS.toFloat()
        return AdaptivePolling.intervalMs(d, nearest.radius, lastRadialVelocity)
    }

    private fun checkTriggers(loc: Location) {
        val now = System.currentTimeMillis()
        val acc = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
        var changed = false
        for (b in barriers) {
            if (!b.enabled) continue
            val d = AdaptivePolling.distanceMeters(loc.latitude, loc.longitude, b.lat, b.lng)
            // Per-barrier repeat interval: while we stay inside the zone, re-dial
            // after b.repeatIntervalSec (5s..3min). Leave the zone -> reset, so
            // re-entering fires immediately.
            val intervalMs = (b.repeatIntervalSec.coerceIn(5, 180)) * 1000L
            if (d <= b.radius) {
                // Confidence gate: a single coarse fix must never fire a call
                // (this was the false "7 m" right after a Wi-Fi disconnect).
                val needAcc = max(25f, min(b.radius, 60f))
                if (acc > needAcc) {
                    consecutiveInside[b.id] = 0
                    Log.d(TAG, "in zone '${b.name}' but accuracy too poor " +
                            "(±${acc.toInt()}m, need ±${needAcc.toInt()}m) - wait for better fix")
                    continue
                }
                val n = (consecutiveInside[b.id] ?: 0) + 1
                consecutiveInside[b.id] = n
                // Need 2 consecutive inside-fixes, unless we are deep inside
                // (distance + accuracy still inside the zone -> confident at once).
                val confident = d + acc <= b.radius + 5f
                if (n < 2 && !confident) {
                    Log.d(TAG, "in zone '${b.name}' first fix d=${d.toInt()}m - wait for confirmation fix")
                    continue
                }
                if (now - b.lastTriggeredAt > intervalMs) {
                    Log.i(TAG, "In zone: '${b.name}' d=${d.toInt()}m radius=${b.radius.toInt()}m -> dial ${b.phone} (repeat ${b.repeatIntervalSec}s)")
                    if (placeCall(b.phone)) {
                        b.lastTriggeredAt = now
                        changed = true
                    } else {
                        Log.w(TAG, "Call to '${b.phone}' was NOT placed. Check CALL_PHONE permission and the overlay (display over other apps) permission.")
                    }
                }
            } else {
                consecutiveInside[b.id] = 0
                if (b.lastTriggeredAt != 0L) {
                    // We left the zone - clear the cooldown so the next entry
                    // calls right away instead of waiting out the old timer.
                    b.lastTriggeredAt = 0L
                    changed = true
                }
            }
        }
        if (changed) BarrierStore.save(this, barriers)
    }

    @SuppressLint("MissingPermission")
    private fun placeCall(phone: String): Boolean {
        val number = phone.trim().replace(" ", "").replace("-", "")
        if (number.isEmpty()) {
            Log.w(TAG, "placeCall: empty phone number")
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "placeCall: CALL_PHONE permission not granted")
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            startActivity(intent)
            Log.i(TAG, "placeCall: ACTION_CALL launched for $number")
            true
        } catch (e: SecurityException) {
            // Likely the background activity-start restriction: the phone app
            // could not be launched from the background. Grant "display over
            // other apps" (SYSTEM_ALERT_WINDOW) to fix this.
            Log.e(TAG, "placeCall: SecurityException launching dialer (enable 'Display over other apps' permission): ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "placeCall: error launching dialer: ${e.message}")
            false
        }
    }

    // ---------------- wifi ----------------
    private fun recomputeWifiState() {
        val wasPaused = pausedByWifi
        currentSsid = currentWifiSsid()
        pausedByWifi = Settings.isWifiGateEnabled(this) &&
                currentSsid != null &&
                wifiPause.contains(currentSsid)

        // A WiFi network we were paused on just dropped -> resume with a short
        // burst of fast fixes so the distance to the nearest barrier is
        // re-measured (the adaptive model should not start from a stale guess).
        if (wasPaused && !pausedByWifi) {
            startWarmup()
        }
    }

    private fun currentWifiSsid(): String? {
        // Android 10+ : read the SSID via ConnectivityManager + NetworkCapabilities.
        // This is far more reliable than WifiManager.connectionInfo, which often
        // returns "<unknown ssid>" or null without location enabled.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = cm ?: return null
                val network = cm.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(network) ?: return null
                val info = caps.transportInfo
                if (info is android.net.wifi.WifiInfo) {
                    val ssid = info.ssid?.trim('"')
                    if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") return ssid
                }
            } catch (_: Exception) {
                // fall through to the legacy path
            }
        }
        // Legacy fallback.
        val wm = wm ?: return null
        return try {
            wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- status ----------------
    private fun publishStatus() {
        val nearest = nearestBarrier()
        val dist = nearest?.let {
            if (lastLat != null && lastLng != null)
                AdaptivePolling.distanceMeters(lastLat!!, lastLng!!, it.lat, it.lng)
            else null
        }
        val inZone = dist != null && nearest != null && dist <= nearest.radius

        emit(
            ServiceStatus(
                running = true,
                autoOn = Settings.isAutoEnabled(this),
                locationPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED,
                callPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED,
                pausedByWifi = pausedByWifi,
                wifiSsid = currentSsid,
                hasFix = hasFix,
                lat = lastLat,
                lng = lastLng,
                nearestDistance = dist,
                nearestName = nearest?.name?.trim(),
                accuracyM = lastAccuracy,
                gpsWarmup = warmupRemaining > 0,
                fixTimeMs = if (hasFix) lastFixTimeMs else null,
                inZone = inZone,
                pollPeriodMs = if (!pausedByWifi && warmupRemaining > 0) WARMUP_PERIOD_MS
                else currentProvider?.let { periodFor(it) } ?: currentPeriod()
            )
        )
    }

    override fun onDestroy() {
        stopLocation()
        runCatching { unregisterReceiver(wifiReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
