import Foundation

/// Same JSON shape as the Android app — lists can be copied between phones.
struct Barrier: Identifiable, Codable, Equatable {
    var id: String
    var name: String
    var phone: String
    var lat: Double
    var lng: Double
    var radius: Double
    var enabled: Bool
    var lastTriggeredAt: Int64
    var repeatIntervalSec: Int
    var icon: String

    init(
        id: String = UUID().uuidString,
        name: String = "Шлагбаум",
        phone: String = "",
        lat: Double = 0,
        lng: Double = 0,
        radius: Double = 40,
        enabled: Bool = true,
        lastTriggeredAt: Int64 = 0,
        repeatIntervalSec: Int = 60,
        icon: String = "gate1"
    ) {
        self.id = id
        self.name = name
        self.phone = phone
        self.lat = lat
        self.lng = lng
        self.radius = radius
        self.enabled = enabled
        self.lastTriggeredAt = lastTriggeredAt
        self.repeatIntervalSec = repeatIntervalSec
        self.icon = icon
    }

    var hasPoint: Bool { !(lat == 0 && lng == 0) }

    var regionId: String { "zigate.\(id)" }

    var telURL: URL? {
        let n = phone.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "")
        guard !n.isEmpty else { return nil }
        return URL(string: "tel://\(n)")
    }

    var iconSymbol: String {
        switch icon {
        case "gate2": return "car.fill"
        case "gate3": return "building.2.fill"
        case "gate4": return "house.fill"
        case "gate5": return "lock.fill"
        default: return "flag.fill"
        }
    }
}
