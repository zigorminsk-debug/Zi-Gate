import SwiftUI
import CoreLocation

struct BarrierEditor: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var tracker: LocationTracker
    @State var barrier: Barrier
    var onSave: (Barrier) -> Void

    private let icons = ["gate1", "gate2", "gate3", "gate4", "gate5"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Название") {
                    TextField("Название", text: $barrier.name)
                }
                Section("Номер для звонка") {
                    TextField("Телефон", text: $barrier.phone)
                        .keyboardType(.phonePad)
                }
                Section("Зона") {
                    HStack {
                        Text("Радиус, м")
                        Spacer()
                        TextField("40", value: $barrier.radius, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 80)
                    }
                    HStack {
                        Text("Повтор, сек")
                        Spacer()
                        TextField("60", value: $barrier.repeatIntervalSec, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 80)
                    }
                    Toggle("Включён", isOn: $barrier.enabled)
                }
                Section("Иконка") {
                    Picker("Иконка", selection: $barrier.icon) {
                        ForEach(icons, id: \.self) { k in
                            Text(k).tag(k)
                        }
                    }
                    .pickerStyle(.segmented)
                }
                Section("Координаты") {
                    if barrier.hasPoint {
                        Text(String(format: "%.6f, %.6f", barrier.lat, barrier.lng))
                            .font(.caption.monospaced())
                    } else {
                        Text("Точка не задана").foregroundStyle(.secondary)
                    }
                    Button("Запросить координаты") {
                        tracker.requestOneShot { loc in
                            guard let loc else { return }
                            barrier.lat = loc.coordinate.latitude
                            barrier.lng = loc.coordinate.longitude
                        }
                    }
                }
            }
            .navigationTitle(barrier.name.isEmpty ? "Новый шлагбаум" : "Редактировать")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Отмена") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Сохранить") {
                        barrier.radius = min(max(barrier.radius, 5), 5000)
                        barrier.repeatIntervalSec = min(max(barrier.repeatIntervalSec, 5), 180)
                        onSave(barrier)
                        dismiss()
                    }
                }
            }
        }
    }
}
