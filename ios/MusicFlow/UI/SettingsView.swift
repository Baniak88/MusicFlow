import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    row("play.circle.fill", "YouTube Music", detail: L.t("settings.youtube_detail"))
                    Toggle(isOn: $settings.radioEnabled) {
                        rowLabel("dot.radiowaves.left.and.right", L.t("settings.radio"), detail: L.t("settings.radio_detail"))
                    }
                    if settings.radioEnabled {
                        Toggle(isOn: $settings.radioAllowHTTP) {
                            rowLabel("lock.open", L.t("settings.radio_http"), detail: L.t("settings.radio_http_detail"))
                        }
                    }
                    Toggle(isOn: $settings.musicbrainzEnabled) {
                        rowLabel("books.vertical.fill", "MusicBrainz", detail: L.t("settings.mb_detail"))
                    }
                } header: { Text(L.t("settings.sources")) }

                Section {
                    Picker(selection: $settings.quality) {
                        Text(L.t("settings.quality_high")).tag(AudioQuality.high)
                        Text(L.t("settings.quality_saver")).tag(AudioQuality.saver)
                    } label: {
                        rowLabel("waveform", L.t("settings.quality"), detail: L.t("settings.quality_detail"))
                    }
                } header: { Text(L.t("settings.audio")) }

                Section {
                    Button(role: .destructive) { confirmClear = true } label: {
                        Label(L.t("library.clear_history"), systemImage: "clock.badge.xmark")
                    }
                    .disabled(library.history.isEmpty)
                } header: { Text(L.t("tab.library")) } footer: { Text(L.t("settings.local_only")) }

                Section {
                    LabeledContent(L.t("settings.version"), value: "\(AppInfo.version) (\(AppInfo.build))")
                    Link(destination: AppInfo.repository) {
                        Label(L.t("settings.source_code"), systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                    Text(L.t("settings.licence")).font(.footnote).foregroundStyle(Theme.secondary)
                    Text(L.t("settings.privacy")).font(.footnote).foregroundStyle(Theme.secondary)
                    Text(L.t("settings.data_sources")).font(.footnote).foregroundStyle(Theme.secondary)
                } header: { Text(L.t("settings.about")) }
            }
            .scrollContentBackground(.hidden)
            .background(AppBackground())
            .tint(Theme.magenta)
            .navigationTitle(L.t("settings.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L.t("action.done")) { dismiss() } }
            }
            .confirmationDialog(L.t("library.clear_history_confirm"), isPresented: $confirmClear, titleVisibility: .visible) {
                Button(L.t("library.clear_history"), role: .destructive) { library.clearHistory() }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func row(_ icon: String, _ title: String, detail: String) -> some View {
        rowLabel(icon, title, detail: detail)
    }

    private func rowLabel(_ icon: String, _ title: String, detail: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Theme.accent))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(.white)
                Text(detail).font(.caption).foregroundStyle(Theme.secondary)
            }
        }
    }
}
