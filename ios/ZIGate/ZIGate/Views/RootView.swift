import SwiftUI
import CoreLocation

struct RootView: View {
    @EnvironmentObject var store: BarrierStore
    @EnvironmentObject var tracker: LocationTracker
    @StateObject private var settings = AppSettings.shared
    @State private var editor: Barrier?
    @State private var adding = false
    @State private var showSync = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    barriersCard
                    settingsCard
                    statusCard
                    Text("ZI Gate iOS v1.21 · Zakharevich Igor")
                        .font(.caption)
                        .foregroundStyle(Color(hex: 0x60707F))
                        .frame(maxWidth: .infinity)
                }
                .padding()
            }
            .background(Color(hex: 0xF4F6F9).ignoresSafeArea())
            .navigationBarHidden(true)
            .sheet(isPresented: $adding) {
                BarrierEditor(barrier: Barrier()) { store.upsert($0) }
                    .environmentObject(store)
                    .environmentObject(tracker)
            }
            .sheet(item: $editor) { b in
                BarrierEditor(barrier: b) { store.upsert($0) }
                    .environmentObject(store)
                    .environmentObject(tracker)
            }
            .sheet(isPresented: $showSync) {
                SyncView()
                    .environmentObject(store)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("ZI Gate")
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(.white)
            Text("Автооткрытие шлагбаума")
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.85))
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(hex: 0x0B5FA5))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var barriersCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Шлагбаумы").font(.headline)
            HStack {
                Button("Добавить шлагбаум") { adding = true }
                    .buttonStyle(PrimaryButton())
                Button("Загрузить / Отправить") { showSync = true }
                    .buttonStyle(OutlineButton())
            }
            if store.barriers.isEmpty {
                Text("Список шлагбаумов пуст. Добавьте первый.")
                    .foregroundStyle(Color(hex: 0x60707F))
                    .font(.subheadline)
            } else {
                ForEach(store.barriers) { b in
                    BarrierCard(barrier: b, last: tracker.last) {
                        editor = b
                    } onDelete: {
                        store.remove(b)
                    } onCall: {
                        CallHelper.dial(b.phone)
                    } onRecord: {
                        tracker.requestOneShot { loc in
                            guard let loc else { return }
                            var u = b
                            u.lat = loc.coordinate.latitude
                            u.lng = loc.coordinate.longitude
                            store.upsert(u)
                        }
                    }
                }
            }
        }
        .padding()
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var settingsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Настройки").font(.headline)
            Toggle("Автоматическое открытие", isOn: $settings.autoEnabled)
                .onChange(of: settings.autoEnabled) { _ in
                    tracker.configure()
                }
            if tracker.auth != .authorizedAlways && settings.autoEnabled {
                Text("⚠ Для работы в фоне: Настройки → ZI Gate → Геопозиция → Всегда.")
                    .font(.caption)
                    .foregroundStyle(Color(hex: 0xCF4436))
            }
            Text("iPhone не разрешает тихий автозвонок из фона. При входе в зону придёт уведомление «Позвонить». Если приложение открыто — набор начнётся сразу.")
                .font(.caption)
                .foregroundStyle(Color(hex: 0x60707F))
            Toggle("Пауза опроса при Wi-Fi", isOn: $settings.wifiPauseEnabled)
                .onChange(of: settings.wifiPauseEnabled) { _ in tracker.applyTracking() }
            TextField("SSID для паузы (через запятую)", text: Binding(
                get: { settings.wifiSSIDs.joined(separator: ", ") },
                set: { settings.wifiSSIDs = $0.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) } }
            ))
            .textFieldStyle(.roundedBorder)
            TextField("Код паузы / SSID автосети", text: $settings.pauseCode)
                .textFieldStyle(.roundedBorder)
        }
        .padding()
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Статус").font(.headline)
            row("Служба", settings.autoEnabled ? "вкл" : "выкл")
            row("GPS", tracker.statusLine)
            row("Wi-Fi", tracker.wifiSSID ?? "—")
            row("Последняя точка", lastFixText)
        }
        .padding()
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var lastFixText: String {
        guard let loc = tracker.last else { return "—" }
        let t = DateFormatter.localizedString(from: loc.timestamp, dateStyle: .none, timeStyle: .medium)
        if let n = store.barriers.filter(\.hasPoint).min(by: {
            Haversine.meters(lat1: loc.coordinate.latitude, lng1: loc.coordinate.longitude, lat2: $0.lat, lng2: $0.lng) <
            Haversine.meters(lat1: loc.coordinate.latitude, lng1: loc.coordinate.longitude, lat2: $1.lat, lng2: $1.lng)
        }) {
            let d = Int(Haversine.meters(lat1: loc.coordinate.latitude, lng1: loc.coordinate.longitude, lat2: n.lat, lng2: n.lng))
            return "\(t) · \(n.name): \(d) м"
        }
        return t
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack {
            Text(k).foregroundStyle(Color(hex: 0x60707F))
            Spacer()
            Text(v)
        }
        .font(.subheadline)
    }
}

struct BarrierCard: View {
    let barrier: Barrier
    let last: CLLocation?
    var onEdit: () -> Void
    var onDelete: () -> Void
    var onCall: () -> Void
    var onRecord: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: barrier.iconSymbol)
                    .foregroundStyle(Color(hex: 0x0B5FA5))
                    .onTapGesture(perform: onCall)
                VStack(alignment: .leading) {
                    Text(barrier.name).font(.headline)
                    Text("☎ \(barrier.phone)").font(.caption).foregroundStyle(Color(hex: 0x60707F))
                }
                Spacer()
                Text(distText)
                    .font(.headline)
                    .foregroundStyle(Color(hex: 0x0B5FA5))
            }
            Text("Радиус зоны: \(Int(barrier.radius)) м · Повтор: \(barrier.repeatIntervalSec) с")
                .font(.caption)
                .foregroundStyle(Color(hex: 0x60707F))
            HStack {
                Button("Записать координаты", action: onRecord)
                Spacer()
                Button("Изменить", action: onEdit)
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                }
            }
            .font(.caption)
        }
        .padding(12)
        .background(Color(hex: 0xF4F6F9))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .onTapGesture(perform: onEdit)
    }

    private var distText: String {
        guard let last, barrier.hasPoint else { return "—" }
        let d = Int(Haversine.meters(
            lat1: last.coordinate.latitude, lng1: last.coordinate.longitude,
            lat2: barrier.lat, lng2: barrier.lng
        ))
        return "\(d) м"
    }
}

struct PrimaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(Color(hex: 0x0B5FA5).opacity(configuration.isPressed ? 0.7 : 1))
            .foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct OutlineButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(hex: 0x0B5FA5)))
            .foregroundStyle(Color(hex: 0x0B5FA5))
    }
}

extension Color {
    init(hex: UInt, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}
