package by.zakharevich.zigate.service

/** Snapshot of the service state, published to the UI on every change. */
data class ServiceStatus(
    val running: Boolean,
    val autoOn: Boolean,
    val locationPermission: Boolean,
    val callPermission: Boolean,
    val pausedByWifi: Boolean,
    val wifiSsid: String?,
    val wifiRssi: Int?,
    val hasFix: Boolean,
    val lat: Double?,
    val lng: Double?,
    val nearestDistance: Float?,
    val nearestName: String?,
    val accuracyM: Float?,
    val gpsWarmup: Boolean,
    val fixTimeMs: Long?,
    val inZone: Boolean,
    val pollPeriodMs: Long,
    /** How we currently poll: gps / network / dual / rest / idle / pause. */
    val locMethod: String,
    /** Provider of the last accepted fix: gps / network / passive. */
    val fixSource: String?,
    val heightM: Float? = null,
    val floor: Int? = null,
    val onRoute: Boolean = false,
    val trainingName: String? = null
) {
    companion object {
        fun empty() = ServiceStatus(
            running = false,
            autoOn = false,
            locationPermission = false,
            callPermission = false,
            pausedByWifi = false,
            wifiSsid = null,
            wifiRssi = null,
            hasFix = false,
            lat = null,
            lng = null,
            nearestDistance = null,
            nearestName = null,
            accuracyM = null,
            gpsWarmup = false,
            fixTimeMs = null,
            inZone = false,
            pollPeriodMs = 0L,
            locMethod = "off",
            fixSource = null,
            heightM = null,
            floor = null
        )
    }
}
