import Foundation
import UIKit
import UserNotifications

enum CallHelper {
    static func dial(_ phone: String) {
        let n = phone.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "")
        guard !n.isEmpty, let url = URL(string: "tel://\(n)") else { return }
        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }
}

enum NotificationHelper {
    static let callAction = "ZIGATE_CALL"
    static let category = "ZIGATE_ENTER"

    static func registerCategories() {
        let call = UNNotificationAction(
            identifier: callAction,
            title: "Позвонить",
            options: [.foreground]
        )
        let cat = UNNotificationCategory(
            identifier: category,
            actions: [call],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([cat])
    }

    static func announceEnter(barrier: Barrier) {
        let content = UNMutableNotificationContent()
        content.title = "ZI Gate: \(barrier.name)"
        content.body = "Вы в зоне шлагбаума. Нажмите, чтобы позвонить \(barrier.phone)."
        content.sound = .default
        content.categoryIdentifier = category
        content.userInfo = ["phone": barrier.phone, "id": barrier.id]
        let req = UNNotificationRequest(
            identifier: "enter-\(barrier.id)-\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
    }
}

enum Haversine {
    static func meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let r = 6_371_000.0
        let dLat = (lat2 - lat1) * .pi / 180
        let dLng = (lng2 - lng1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
