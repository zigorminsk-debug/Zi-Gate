import Foundation
import Combine
import CoreLocation
import Network
import UIKit

/// iOS equivalent of BarrierService: geofences + optional GPS while in use.
/// Apple does not allow silent ACTION_CALL from the background; we fire a
/// notification with a «Позвонить» action (and dial immediately if the app is active).
final class LocationTracker: NSObject, ObservableObject {
    static let shared = LocationTracker()

    private let manager = CLLocationManager()
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "zigate.wifi")

    @Published var last: CLLocation?
    @Published var statusLine = "выкл"
    @Published var wifiSSID: String?
    @Published var pausedByWifi = false
    @Published var auth: CLAuthorizationStatus = .notDetermined

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .automotiveNavigation
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.onPath(path)
            }
        }
        monitor.start(queue: queue)
    }

    func configure() {
        auth = manager.authorizationStatus
        if auth == .notDetermined {
            manager.requestAlwaysAuthorization()
        }
        applyTracking()
    }

    func applyTracking() {
        let on = AppSettings.shared.autoEnabled
        if on && !pausedByWifi {
            if manager.authorizationStatus == .authorizedAlways ||
                manager.authorizationStatus == .authorizedWhenInUse {
                manager.startUpdatingLocation()
                refreshGeofences()
                statusLine = last == nil ? "ожидание" : "вкл"
            } else {
                statusLine = "нет GPS"
            }
        } else {
            manager.stopUpdatingLocation()
            if !on {
                manager.monitoredRegions.forEach { manager.stopMonitoring(for: $0) }
                statusLine = "выкл"
            } else {
                statusLine = "пауза · Wi-Fi"
            }
        }
    }

    func refreshGeofences() {
        guard AppSettings.shared.autoEnabled, !pausedByWifi else { return }
        manager.monitoredRegions.forEach { manager.stopMonitoring(for: $0) }
        let list = BarrierStore.shared.barriers.filter { $0.enabled && $0.hasPoint }
        // iOS allows at most 20 geofences per app.
        for b in list.prefix(20) {
            let r = min(max(b.radius, 20), 400)
            let region = CLCircularRegion(
                center: CLLocationCoordinate2D(latitude: b.lat, longitude: b.lng),
                radius: r,
                identifier: b.regionId
            )
            region.notifyOnEntry = true
            region.notifyOnExit = true
            manager.startMonitoring(for: region)
        }
    }

    func requestOneShot(_ done: @escaping (CLLocation?) -> Void) {
        if let loc = manager.location, abs(loc.timestamp.timeIntervalSinceNow) < 10 {
            done(loc)
            last = loc
            return
        }
        manager.requestLocation()
        oneshot = done
    }

    private var oneshot: ((CLLocation?) -> Void)?

    private func onPath(_ path: NWPath) {
        let wifi = path.usesInterfaceType(.wifi)
        let ssid = wifi ? (currentSSID() ?? wifiSSID) : nil
        wifiSSID = ssid
        let set = Set(AppSettings.shared.wifiSSIDs.filter { !$0.isEmpty })
        let code = AppSettings.shared.pauseCode.trimmingCharacters(in: .whitespaces)
        var pause = false
        if AppSettings.shared.wifiPauseEnabled && wifi {
            if set.isEmpty && code.isEmpty {
                pause = true
            } else if let ssid, set.contains(ssid) || (!code.isEmpty && ssid == code) {
                pause = true
            }
        }
        if pause != pausedByWifi {
            pausedByWifi = pause
            applyTracking()
        }
    }

    private func currentSSID() -> String? {
        // Requires Access Wi-Fi Information + location. May be nil in Simulator.
        if let ifs = CNCopyCurrentNetworkInfoFallback() {
            return ifs
        }
        return nil
    }

    private func CNCopyCurrentNetworkInfoFallback() -> String? {
        // SystemConfiguration is optional; keep compile-safe without extra frameworks.
        return nil
    }

    fileprivate func handleEnter(_ id: String) {
        let rid = id.hasPrefix("zigate.") ? String(id.dropFirst("zigate.".count)) : id
        guard let b = BarrierStore.shared.barriers.first(where: { $0.id == rid || $0.regionId == id }) else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let interval = Int64(max(5, min(180, b.repeatIntervalSec))) * 1000
        if now - b.lastTriggeredAt < interval { return }
        BarrierStore.shared.markTriggered(b.id, at: now)
        NotificationHelper.announceEnter(barrier: b)
        if UIApplication.shared.applicationState == .active {
            CallHelper.dial(b.phone)
        }
        statusLine = "в зоне"
    }

    fileprivate func handleExit(_ id: String) {
        let rid = id.hasPrefix("zigate.") ? String(id.dropFirst("zigate.".count)) : id
        if let b = BarrierStore.shared.barriers.first(where: { $0.id == rid }) {
            BarrierStore.shared.markTriggered(b.id, at: 0)
        }
        if statusLine == "в зоне" { statusLine = "вкл" }
    }
}

extension LocationTracker: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        auth = manager.authorizationStatus
        applyTracking()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        last = locations.last
        oneshot?(locations.last)
        oneshot = nil
        if statusLine == "ожидание" { statusLine = "вкл" }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        oneshot?(nil)
        oneshot = nil
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        handleEnter(region.identifier)
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        handleExit(region.identifier)
    }
}
