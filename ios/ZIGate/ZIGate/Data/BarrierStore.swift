import Foundation
import Combine

final class BarrierStore: ObservableObject {
    static let shared = BarrierStore()

    @Published var barriers: [Barrier] = []

    private let key = "barriers"
    private let defaults = UserDefaults.standard

    private init() { load() }

    func load() {
        guard let data = defaults.data(forKey: key) else {
            barriers = []
            return
        }
        barriers = (try? JSONDecoder().decode([Barrier].self, from: data)) ?? []
    }

    func save() {
        if let data = try? JSONEncoder().encode(barriers) {
            defaults.set(data, forKey: key)
        }
        LocationTracker.shared.refreshGeofences()
    }

    func upsert(_ b: Barrier) {
        if let i = barriers.firstIndex(where: { $0.id == b.id }) {
            barriers[i] = b
        } else {
            barriers.append(b)
        }
        save()
    }

    func remove(_ b: Barrier) {
        barriers.removeAll { $0.id == b.id }
        save()
    }

    func markTriggered(_ id: String, at ms: Int64) {
        guard let i = barriers.firstIndex(where: { $0.id == id }) else { return }
        barriers[i].lastTriggeredAt = ms
        save()
    }

    func exportJSON() -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? enc.encode(barriers), let s = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return s
    }

    @discardableResult
    func importJSON(_ text: String) -> Int {
        guard let data = text.data(using: .utf8),
              let incoming = try? JSONDecoder().decode([Barrier].self, from: data)
        else { return barriers.count }
        var byId = Dictionary(uniqueKeysWithValues: barriers.map { ($0.id, $0) })
        for var b in incoming {
            if let old = byId[b.id] {
                b.lastTriggeredAt = old.lastTriggeredAt
            }
            byId[b.id] = b
        }
        barriers = Array(byId.values)
        save()
        return barriers.count
    }
}
