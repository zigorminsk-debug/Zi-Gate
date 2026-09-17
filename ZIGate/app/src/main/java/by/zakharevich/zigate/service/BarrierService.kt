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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import by.zakharevich.zigate.App
import by.zakharevich.zigate.R
import by.zakharevich.zigate.data.BarrierStore
import by.zakharevich.zigate.data.RouteMemory
import by.zakharevich.zigate.data.Settings
import by.zakharevich.zigate.model.Barrier
import by.zakharevich.zigate.util.AdaptivePolling
import by.zakharevich.zigate.util.DialHelper
import by.zakharevich.zigate.util.KeepAlive
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
    private var lastFixGps = false
    private var lastGpsElapsed: Long = 0L

    /** Confirmed inside (hysteresis) so GPS jitter does not re-arm the call. */
    private val wasInside = mutableMapOf<String, Boolean>()

    // Velocity-aware polling: previous distance to the nearest barrier, used
    // to estimate the radial approach/recede speed (m/s).
    private var prevDistToNearest: Float? = null
    private var prevFixNanos: Long = 0L
    private var prevNearestId: String? = null
    private var lastRadialVelocity = 0f
    /** ElapsedRealtime of last real movement. Sitting still must not keep GPS on. */
    private var lastMotionElapsed: Long = 0L

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
    private var currentRssi: Int? = null
    /** Keep GPS hot after leaving home Wi-Fi. */
    private var forceGpsUntilElapsed: Long = 0L
    private var pendingMotionBurst = false
    private var charging = false
    private var onLearnedRoute = false

    private var barriers: MutableList<Barrier> = mutableListOf()
    private var wifiPause: Set<String> = emptySet()
    private var lastBarrierIds: Set<String> = emptySet()

    private var wakeLock: PowerManager.WakeLock? = null
    /** When no usable GPS arrived for this long, accept coarser network fixes. */
    private var lastGoodGpsElapsed: Long = 0L

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
        /** GPS worse than this is junk even far away. Near the gate we tighten further. */
        private const val GPS_FIX_ACCURACY_MAX_M = 80f
        /** Network is only a far-away hint, never a trigger. */
        private const val NET_FIX_ACCURACY_MAX_M = 400f
        private const val GPS_STARVE_MS = 20_000L
        private const val STARVE_NET_ACCURACY_MAX_M = 800f
        /** Last-known older than this is not used as a position. */
        private const val MAX_FIX_AGE_MS = 20_000L
        private const val SEED_MAX_AGE_MS = 90_000L
        private const val MOVING_SPEED_MS = 1.0f
        private const val VELOCITY_ACC_MAX_M = 35f
        /** Ignore a teleport: metres of jump faster than a car. */
        private const val MAX_JUMP_MPS = 50f
        /** After this long without movement, drop GPS and poll network slowly. */
        private const val IDLE_AFTER_MS = 45_000L
        private const val IDLE_PERIOD_MS = 60_000L
        /** Resting near a gate (no Wi-Fi, no charge). */
        private const val ZONE_REST_M = 400f
        /** Pause GPS only when home Wi-Fi is strong (inside), not from the street. */
        private const val WIFI_PAUSE_RSSI_ON = -70
        /** Leave pause as soon as the signal is no longer “inside”. */
        private const val WIFI_PAUSE_RSSI_OFF = -70
        /** Aggressive GPS after Wi-Fi drop / weak home signal. */
        private const val WIFI_LEAVE_GPS_MS = 120_000L

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
        /** GPS earlier when on a learned commute cell. */
        private const val GPS_REGIME_ROUTE_M = 700f

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
                WifiManager.RSSI_CHANGED_ACTION,
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    recomputeWifiState()
                    updateLocationRegistration()
                    publishStatus()
                }
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val was = charging
            charging = isChargingNow()
            if (was != charging) {
                recomputeWifiState()
                updateLocationRegistration()
                publishStatus()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { onLocation(loc) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager

        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        // System wifi/connectivity broadcasts require an exported receiver on API 33+.
        ContextCompat.registerReceiver(
            this, wifiReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, powerReceiver, powerFilter, ContextCompat.RECEIVER_EXPORTED
        )
        charging = isChargingNow()

        startAsForeground()
        acquireWakeLock()
        seedLastKnown()
        KeepAlive.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                KeepAlive.cancel(this)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                barriers = BarrierStore.load(this)
                wifiPause = wifiPauseSet()
                val ids = barriers.map { it.id }.toSet()
                if (ids != lastBarrierIds) {
                    consecutiveInside.keys.retainAll(ids)
                    lastBarrierIds = ids
                }
                charging = isChargingNow()
                recomputeWifiState()
                if (!hasFix) seedLastKnown()
                updateLocationRegistration()
                publishStatus()
                KeepAlive.schedule(this)
            }
        }
        return START_STICKY
    }

    private fun wifiPauseSet(): Set<String> {
        if (!Settings.isWifiGateEnabled(this)) return emptySet()
        val set = Settings.wifiPauseSet(this)
        val code = Settings.pauseCode(this).trim()
        if (code.isNotEmpty()) set.add(code)
        return set
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zigate:gps").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        val lm = lm ?: return
        val candidates = listOfNotNull(
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
            runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        )
        val best = candidates.filter { loc ->
            val age = if (Build.VERSION.SDK_INT >= 17)
                (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
            else abs(System.currentTimeMillis() - loc.time)
            age in 0..SEED_MAX_AGE_MS &&
                isUsableLocation(loc, starving = true, ignoreAge = true)
        }.minByOrNull { if (it.hasAccuracy()) it.accuracy else 9999f }
        if (best != null && !hasFix) {
            acceptFix(best, fromWarmup = true)
            // Display only — never call from a cached last-known point.
        }
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
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
    private fun hasCoords(b: Barrier): Boolean =
        !(b.lat == 0.0 && b.lng == 0.0)

    private fun enabledBarriers(): List<Barrier> =
        barriers.filter { it.enabled && hasCoords(it) }

    private fun distanceToNearest(): Float? {
        if (lastLat == null || lastLng == null) return null
        val nearest = enabledBarriers().minByOrNull {
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

        if (charging || lastRadialVelocity < -1f) return LocationManager.GPS_PROVIDER
        val gpsM = if (onLearnedRoute) GPS_REGIME_ROUTE_M else GPS_REGIME_M
        val netM = gpsM + 120f
        return when {
            distToNearest == null -> LocationManager.GPS_PROVIDER
            distToNearest < gpsM -> LocationManager.GPS_PROVIDER
            distToNearest > netM -> LocationManager.NETWORK_PROVIDER
            else -> currentProvider ?: LocationManager.GPS_PROVIDER
        }
    }

    private fun forcingGps(): Boolean =
        SystemClock.elapsedRealtime() < forceGpsUntilElapsed

    private fun isStationaryLongEnough(): Boolean {
        if (charging || warmupRemaining > 0 || forcingGps()) return false
        if (lastMotionElapsed == 0L) return false
        if (kotlin.math.abs(lastRadialVelocity) > 0.8f) return false
        return SystemClock.elapsedRealtime() - lastMotionElapsed > IDLE_AFTER_MS
    }

    /** Sitting still within ~400 m of a gate, no charge, no home Wi-Fi pause. */
    private fun isZoneRest(): Boolean {
        if (!isStationaryLongEnough()) return false
        val d = distanceToNearest() ?: return false
        return d < ZONE_REST_M
    }

    /** Closer rest → shorter GPS tick: 150 m / 5 s, 200 m / 10 s, 300 m / 15 s. */
    private fun zoneRestPeriodMs(): Long {
        val d = distanceToNearest() ?: return 20_000L
        return when {
            d <= 150f -> 5_000L
            d <= 200f -> 10_000L
            d <= 300f -> 15_000L
            else -> 20_000L
        }
    }

    private fun isIdleStationary(): Boolean {
        if (!isStationaryLongEnough()) return false
        val d = distanceToNearest()
        if (d == null || d < ZONE_REST_M) return false
        val fixAge = System.currentTimeMillis() - lastFixTimeMs
        if (hasFix && fixAge > 15_000L) return false
        return true
    }

    private fun gpsStarving(): Boolean {
        if (lastGoodGpsElapsed == 0L) return !hasFix
        return SystemClock.elapsedRealtime() - lastGoodGpsElapsed > GPS_STARVE_MS
    }

    /** Accuracy gate for a fix, depending on the active regime. */
    private fun isGpsFix(loc: Location): Boolean =
        loc.provider == LocationManager.GPS_PROVIDER

    private fun maxAcceptableAccuracy(loc: Location): Float {
        val near = distanceToNearest()
        val close = near != null && near < 250f
        if (isGpsFix(loc)) return if (close) 40f else GPS_FIX_ACCURACY_MAX_M
        if (close) return 0f // reject network next to the gate
        return if (gpsStarving()) STARVE_NET_ACCURACY_MAX_M else NET_FIX_ACCURACY_MAX_M
    }

    // ---------------- location ----------------
    /** (Re-)registers location updates. Silent: publishing the status is the
     *  caller's job, so every code path emits the status exactly once. */
    @SuppressLint("MissingPermission")
    private fun updateLocationRegistration() {
        val lm = lm ?: return
        val autoOn = Settings.isAutoEnabled(this)
        val locPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

        if (!autoOn || pausedByWifi || !locPermission) {
            stopLocation()
            releaseWakeLock()
            return
        }

        val dist = distanceToNearest()
        val idle = isIdleStationary()
        val zoneRest = isZoneRest()
        val provider = chooseProvider(dist)
        val periodHint = when {
            warmupRemaining > 0 -> WARMUP_PERIOD_MS
            zoneRest -> zoneRestPeriodMs()
            idle && !charging -> IDLE_PERIOD_MS
            else -> periodFor(provider)
        }
        val needWake = charging || warmupRemaining > 0 ||
                (!idle && !zoneRest && periodHint <= 2_000L)
        if (needWake) acquireWakeLock() else releaseWakeLock()

        if (!hasFix && warmupRemaining == 0) {
            warmupRemaining = WARMUP_FIXES
            warmupFixes.clear()
            warmupDeadlineMs = SystemClock.elapsedRealtime() + WARMUP_TIMEOUT_MS
            prevDistToNearest = null
            prevFixNanos = 0L
            prevNearestId = null
            lastRadialVelocity = 0f
        }

        val period = when {
            warmupRemaining > 0 -> WARMUP_PERIOD_MS
            zoneRest -> zoneRestPeriodMs()
            idle && !charging -> IDLE_PERIOD_MS
            else -> periodFor(provider)
        }
        val gpsNear = dist != null && dist < (if (onLearnedRoute) GPS_REGIME_ROUTE_M else GPS_REGIME_M)
        val approaching = lastRadialVelocity < -0.8f
        val wantGps = charging || warmupRemaining > 0 || forcingGps() ||
                gpsStarving() || zoneRest ||
                (!idle && (gpsNear || approaching || dist == null))
        val minDist = when {
            charging || warmupRemaining > 0 -> 0f
            zoneRest -> 5f
            idle -> 30f
            gpsNear && !idle -> 0f
            dist != null && dist < 800f -> 15f
            else -> 40f
        }
        val dualKey = when {
            zoneRest -> "zone-rest"
            wantGps -> "dual"
            idle -> "idle-net"
            else -> provider
        }
        if (registered && dualKey == currentProvider &&
            abs(period - currentIntervalMs) < 2_000
        ) return

        stopLocation()
        try {
            // Dual registration: many phones never deliver GPS if only NETWORK
            // is subscribed (and vice versa). GPS near the gate / when starving;
            // always keep NETWORK as a fallback.
            if (wantGps) {
                runCatching {
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, period, minDist,
                            locationListener, Looper.getMainLooper()
                        )
                    }
                }
            }
            val netPeriod = if (wantGps) max(period, 5_000L) else period.coerceAtLeast(8_000L)
            runCatching {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, netPeriod, minDist,
                        locationListener, Looper.getMainLooper()
                    )
                }
            }
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, period, minDist,
                    locationListener, Looper.getMainLooper()
                )
            }
            registered = true
            currentProvider = dualKey
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
        if (provider != LocationManager.NETWORK_PROVIDER) return p // gps / dual
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
        wasInside.clear()
        updateLocationRegistration()
    }

    private fun onLocation(loc: Location) {
        // ---- 1. Drop obviously bad fixes (stale / coarse / mock / zero). ----
        if (!isUsableLocation(loc, starving = gpsStarving())) {
            Log.d(TAG, "drop bad fix prov=${loc.provider} " +
                    "acc=${if (loc.hasAccuracy()) loc.accuracy else -1f}")
            updateLocationRegistration()
            publishStatus()
            return
        }
        val near = distanceToNearest()
        val gpsFresh = lastGpsElapsed != 0L &&
                SystemClock.elapsedRealtime() - lastGpsElapsed < 12_000L
        if (!isGpsFix(loc) && (gpsFresh || (near != null && near < 350f))) {
            Log.d(TAG, "drop network near gate / while GPS is fresh")
            return
        }
        if (hasFix && lastLat != null && lastLng != null) {
            val jump = AdaptivePolling.distanceMeters(
                lastLat!!, lastLng!!, loc.latitude, loc.longitude
            )
            val dt = (System.currentTimeMillis() - lastFixTimeMs).coerceAtLeast(1L) / 1000f
            if (jump / dt > MAX_JUMP_MPS && jump > 80f) {
                Log.d(TAG, "drop teleport ${jump.toInt()}m in ${dt}s")
                return
            }
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
            val zoneWalk = isZoneRest() && dMove > 8f
            if (zoneWalk) {
                pendingMotionBurst = true
            } else if (!moving && dMove < driftRadius) {
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
        val smoothed = Location(loc).apply {
            lastLat?.let { latitude = it }
            lastLng?.let { longitude = it }
        }
        checkTriggers(smoothed)

        if (pendingMotionBurst) {
            pendingMotionBurst = false
            startWarmup()
            publishStatus()
            return
        }
        updateLocationRegistration()
        publishStatus()
    }

    /** Accept a fix as the new stable position + update the radial speed. */
    private fun acceptFix(loc: Location, fromWarmup: Boolean) {
        val nowNanos = System.nanoTime()
        val firstFix = !hasFix
        if (hasFix && lastLat != null && lastLng != null && loc.hasAccuracy() &&
            loc.accuracy <= 40f && (lastAccuracy ?: 99f) <= 40f && isGpsFix(loc)
        ) {
            val wNew = (lastAccuracy ?: loc.accuracy) /
                    ((lastAccuracy ?: loc.accuracy) + loc.accuracy)
            lastLat = lastLat!! * (1.0 - wNew) + loc.latitude * wNew
            lastLng = lastLng!! * (1.0 - wNew) + loc.longitude * wNew
        } else {
            lastLat = loc.latitude
            lastLng = loc.longitude
        }
        lastAccuracy = if (loc.hasAccuracy()) loc.accuracy else lastAccuracy
        lastFixTimeMs = System.currentTimeMillis()
        hasFix = true
        lastFixGps = isGpsFix(loc) || loc.provider == "zigate-stable" && lastFixGps
        if (isGpsFix(loc)) {
            lastGoodGpsElapsed = SystemClock.elapsedRealtime()
            lastGpsElapsed = lastGoodGpsElapsed
        }

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

        RouteMemory.onFix(loc.latitude, loc.longitude)
        onLearnedRoute = RouteMemory.isOnRoute(this, loc.latitude, loc.longitude)

        if (nearest != null && loc.hasSpeed() && loc.speed >= 1.0f && loc.hasBearing()) {
            val brng = AdaptivePolling.bearingDegrees(
                loc.latitude, loc.longitude, nearest.lat, nearest.lng
            )
            val vGps = AdaptivePolling.radialSpeedFromGps(loc.speed, loc.bearing, brng)
            lastRadialVelocity = lastRadialVelocity * 0.35f + vGps * 0.65f
        }
        val moving = (loc.hasSpeed() && loc.speed >= MOVING_SPEED_MS) ||
                kotlin.math.abs(lastRadialVelocity) > 0.8f
        val nowEl = SystemClock.elapsedRealtime()
        val wasResting = lastMotionElapsed != 0L &&
                nowEl - lastMotionElapsed > IDLE_AFTER_MS
        if (moving) {
            if (wasResting) pendingMotionBurst = true
            lastMotionElapsed = nowEl
        } else if (lastMotionElapsed == 0L) lastMotionElapsed = nowEl
    }

    /** Sanity gate for raw fixes: rejects stale / coarse / mock / zero ones. */
    private fun isUsableLocation(loc: Location, starving: Boolean = false, ignoreAge: Boolean = false): Boolean {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return false
        if (loc.latitude !in -90.0..90.0 || loc.longitude !in -180.0..180.0) return false
        try {
            if (loc.isFromMockProvider) return false
        } catch (_: Exception) {
            // isFromMockProvider not available - ignore.
        }
        if (!ignoreAge) {
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
        } // !ignoreAge
        // Coarse fixes only add jumps; the limit depends on the regime
        // (network far away is allowed to be much coarser than GPS).
        if (loc.hasAccuracy()) {
            val acc = loc.accuracy
            val maxAcc = if (starving && loc.provider != LocationManager.GPS_PROVIDER)
                STARVE_NET_ACCURACY_MAX_M else maxAcceptableAccuracy(loc)
            if (!acc.isFinite() || acc <= 0f || acc > maxAcc) return false
        }
        return true
    }

    private fun nearestBarrier(): Barrier? =
        if (lastLat == null || lastLng == null) null
        else enabledBarriers()
            .minByOrNull {
                AdaptivePolling.distanceMeters(
                    lastLat!!, lastLng!!, it.lat, it.lng
                )
            }

    private fun currentPeriod(): Long {
        if (charging) return AdaptivePolling.CHARGE_PERIOD_MS
        if (isZoneRest()) return zoneRestPeriodMs()
        if (isIdleStationary()) return IDLE_PERIOD_MS
        val nearest = nearestBarrier()
        if (nearest == null) return AdaptivePolling.MAX_PERIOD_MS
        val d = if (lastLat != null && lastLng != null)
            AdaptivePolling.distanceMeters(lastLat!!, lastLng!!, nearest.lat, nearest.lng)
        else AdaptivePolling.MAX_PERIOD_MS.toFloat()
        val stationary = kotlin.math.abs(lastRadialVelocity) < 0.4f
        return AdaptivePolling.intervalMs(
            d, nearest.radius, lastRadialVelocity,
            charging = false,
            onRoute = onLearnedRoute,
            stationary = stationary
        )
    }

    private fun checkTriggers(loc: Location) {
        val now = System.currentTimeMillis()
        val acc = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
        val gpsOk = isGpsFix(loc) || (loc.provider == "zigate-stable" && lastFixGps)
        var changed = false
        for (b in barriers) {
            if (!b.enabled || !hasCoords(b)) continue
            val d = AdaptivePolling.distanceMeters(loc.latitude, loc.longitude, b.lat, b.lng)
            val intervalMs = (b.repeatIntervalSec.coerceIn(5, 180)) * 1000L
            val hitPad = min(if (acc.isFinite()) acc * 0.4f else 8f, 12f)
            val hitR = b.radius + hitPad
            val exitMargin = max(12f, min(acc * 0.6f, 28f))
            val staying = wasInside[b.id] == true && d <= b.radius + exitMargin
            val inside = d <= hitR || staying
            if (!inside) {
                consecutiveInside[b.id] = 0
                wasInside[b.id] = false
                if (b.lastTriggeredAt != 0L && d > b.radius + exitMargin) {
                    b.lastTriggeredAt = 0L
                    changed = true
                }
                continue
            }
            wasInside[b.id] = true
            if (!gpsOk) {
                Log.d(TAG, "in zone '${b.name}' but not GPS — wait")
                continue
            }
            // Phone GPS at a gate is often ±10–20 m; a 5 m radius must still fire.
            val needAcc = 25f
            if (acc > needAcc) {
                consecutiveInside[b.id] = 0
                Log.d(TAG, "in zone '${b.name}' acc ±${acc.toInt()}m > ${needAcc.toInt()}m")
                continue
            }
            val n = (consecutiveInside[b.id] ?: 0) + 1
            consecutiveInside[b.id] = n
            val deep = d <= b.radius
            val needN = if (deep || !b.autoCall) 1 else 2
            if (n < needN) {
                Log.d(TAG, "in zone '${b.name}' confirm $n/$needN d=${d.toInt()}m")
                continue
            }
            if (now - b.lastTriggeredAt > intervalMs) {
                if (b.autoCall) {
                    Log.i(TAG, "In zone: '${b.name}' d=${d.toInt()}m ±${acc.toInt()}m -> dial")
                    if (placeCall(b.phone)) {
                        b.lastTriggeredAt = now
                        changed = true
                        RouteMemory.onTriggered(this, loc.latitude, loc.longitude)
                    } else {
                        Log.w(TAG, "Call to '${b.phone}' was NOT placed.")
                    }
                } else {
                    Log.i(TAG, "In zone: '${b.name}' notify")
                    showCallPrompt(b)
                    b.lastTriggeredAt = now
                    changed = true
                    RouteMemory.onTriggered(this, loc.latitude, loc.longitude)
                }
            }
        }
        if (changed) persistTriggerState()
    }

    /** Write only cooldown fields so a UI toggle of autoCall cannot be overwritten. */
    private fun persistTriggerState() {
        val disk = BarrierStore.load(this)
        val byId = barriers.associateBy { it.id }
        for (i in disk.indices) {
            val fresh = byId[disk[i].id] ?: continue
            disk[i].lastTriggeredAt = fresh.lastTriggeredAt
        }
        BarrierStore.save(this, disk)
        for (d in disk) {
            val mem = barriers.find { it.id == d.id } ?: continue
            mem.autoCall = d.autoCall
            mem.enabled = d.enabled
        }
    }

    private fun showCallPrompt(b: Barrier) {
        val phone = DialHelper.normalize(b.phone)
        if (phone.isEmpty()) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "zigate:callprompt"
            ).acquire(3000L)
        }
        val act = Intent(this, by.zakharevich.zigate.CallNowActivity::class.java)
            .putExtra(by.zakharevich.zigate.CallNowActivity.EXTRA_PHONE, phone)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val callPi = PendingIntent.getActivity(this, b.id.hashCode(), act, flags)
        val notif = NotificationCompat.Builder(this, App.CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_call_title, b.name))
            .setContentText(getString(R.string.notif_call_body, phone))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(callPi)
            .setFullScreenIntent(callPi, true)
            .addAction(0, getString(R.string.notif_call_action), callPi)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(2000 + (b.id.hashCode() and 0x0fff), notif)
        }
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
        val uri = Uri.parse("tel:$number")
        val telecomOk = runCatching {
            val tm = getSystemService(TELECOM_SERVICE) as? TelecomManager
            if (tm != null && Build.VERSION.SDK_INT >= 23) {
                tm.placeCall(uri, android.os.Bundle())
                Log.i(TAG, "placeCall: TelecomManager.placeCall $number")
                true
            } else false
        }.getOrDefault(false)
        if (telecomOk) return true
        return try {
            val intent = Intent(Intent.ACTION_CALL, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            startActivity(intent)
            Log.i(TAG, "placeCall: ACTION_CALL launched for $number")
            true
        } catch (e: SecurityException) {
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
        val prevSsid = currentSsid
        currentSsid = currentWifiSsid()
        currentRssi = currentWifiRssi()
        val listed = Settings.isWifiGateEnabled(this) &&
                currentSsid != null &&
                wifiPause.contains(currentSsid)
        val rssi = currentRssi
        val strongEnough = if (rssi == null || rssi <= -120) {
            false
        } else {
            rssi >= WIFI_PAUSE_RSSI_ON
        }
        pausedByWifi = !charging && listed && strongEnough

        val disconnected = prevSsid != null && currentSsid == null
        val ssidChanged = prevSsid != null && currentSsid != null && prevSsid != currentSsid
        val leftPause = wasPaused && !pausedByWifi
        val leavingHome = listed && rssi != null && rssi < WIFI_PAUSE_RSSI_ON
        val shouldBurst = leftPause || disconnected || ssidChanged ||
                (leavingHome && !forcingGps())
        if (shouldBurst && !pausedByWifi) {
            forceGpsUntilElapsed = SystemClock.elapsedRealtime() + WIFI_LEAVE_GPS_MS
            lastMotionElapsed = SystemClock.elapsedRealtime()
            startWarmup()
        }
    }

    private fun isChargingNow(): Boolean {
        return runCatching {
            val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
            if (bm != null && Build.VERSION.SDK_INT >= 23) {
                if (bm.isCharging) return true
            }
            val st = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            plugged != 0
        }.getOrDefault(false)
    }

    private fun currentWifiRssi(): Int? {
        val wm = wm ?: return null
        return runCatching {
            val r = wm.connectionInfo?.rssi ?: return null
            if (r >= 0 || r <= -120) null else r
        }.getOrNull()
    }

    private fun currentWifiSsid(): String? {
        // Android 10+ : read the SSID via ConnectivityManager + NetworkCapabilities.
        // This is far more reliable than WifiManager.connectionInfo, which often
        // returns "<unknown ssid>" or null without location enabled.
        // getTransportInfo() exists only from API 31; calling it on 10/11 is NoSuchMethodError.
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val cm = cm ?: return null
                val network = cm.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(network) ?: return null
                val info = caps.transportInfo
                if (info is android.net.wifi.WifiInfo) {
                    val ssid = info.ssid?.trim('"')
                    if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") return ssid
                }
            } catch (_: Throwable) {
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
                wifiRssi = currentRssi,
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
        releaseWakeLock()
        runCatching { unregisterReceiver(wifiReceiver) }
        runCatching { unregisterReceiver(powerReceiver) }
        KeepAlive.schedule(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
