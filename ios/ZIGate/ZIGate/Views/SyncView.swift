import SwiftUI

struct SyncView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var store: BarrierStore
    @State private var json = ""
    @State private var message = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Тот же JSON, что на Android: скопируйте список с телефона на iPhone и обратно.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextEditor(text: $json)
                    .font(.system(.footnote, design: .monospaced))
                    .border(Color.gray.opacity(0.3))
                if !message.isEmpty {
                    Text(message).font(.caption)
                }
                HStack {
                    Button("Копировать JSON") {
                        json = store.exportJSON()
                        UIPasteboard.general.string = json
                        message = "Скопировано в буфер"
                    }
                    Button("Вставить и импорт") {
                        if let t = UIPasteboard.general.string { json = t }
                        let n = store.importJSON(json)
                        message = "Импортировано (\(n))"
                    }
                }
                Spacer()
            }
            .padding()
            .navigationTitle("Синхронизация")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Готово") { dismiss() }
                }
            }
            .onAppear { json = store.exportJSON() }
        }
    }
}
