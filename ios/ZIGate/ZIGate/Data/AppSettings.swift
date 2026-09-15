import Foundation
import Combine

final class AppSettings: ObservableObject {
    static let shared = AppSettings()
    private let d = UserDefaults.standard

    @Published var autoEnabled: Bool {
        didSet { d.set(autoEnabled, forKey: "auto_enabled") }
    }
    @Published var wifiPauseEnabled: Bool {
        didSet { d.set(wifiPauseEnabled, forKey: "wifi_gate_enabled") }
    }
    @Published var wifiSSIDs: [String] {
        didSet { d.set(wifiSSIDs, forKey: "wifi_pause_ssids") }
    }
    @Published var pauseCode: String {
        didSet { d.set(pauseCode, forKey: "pause_code") }
    }

    private init() {
        autoEnabled = d.bool(forKey: "auto_enabled")
        wifiPauseEnabled = d.bool(forKey: "wifi_gate_enabled")
        wifiSSIDs = d.stringArray(forKey: "wifi_pause_ssids") ?? []
        pauseCode = d.string(forKey: "pause_code") ?? ""
    }
}
