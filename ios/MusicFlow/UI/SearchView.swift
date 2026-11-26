import SwiftUI

enum SearchSource: String, CaseIterable, Identifiable {
    case youtube, radio, musicbrainz
    var id: String { rawValue }

    var title: String {
        switch self {
        case .youtube: return "YouTube Music"
        case .radio: return L.t("source.radio")
        case .musicbrainz: return "MusicBrainz"
        }
    }

    var icon: String {
        switch self {
        case .youtube: return "play.circle.fill"
        case .radio: return "dot.radiowaves.left.and.right"
        case .musicbrainz: return "books.vertical.fill"
        }
    }
}

@MainActor
final class SearchModel: ObservableObject {
    @Published var query = ""
    @Published var source: SearchSource = .youtube
    @Published private(set) var state: LoadState<[Track]> = .idle
    private var task: Task<Void, Never>?
    private var lastKey = ""

    func queryChanged() {
        task?.cancel()
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty { state = .idle; lastKey = ""; return }
        task = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 550_000_000)
            guard !Task.isCancelled else { return }
            await self?.run()
        }
    }

    func submit() {
        task?.cancel()
        task = Task { [weak self] in await self?.run(force: true) }
    }

    func setSource(_ s: SearchSource) {
        guard s != source else { return }
        source = s
        submit()
    }

    private func run(force: Bool = false) async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { state = .idle; return }
        let key = "\(source.rawValue)|\(q)"
        if !force, key == lastKey, state.value != nil { return }
        lastKey = key
        state = .loading
        let source = self.source
        do {
            let result: [Track]
            switch source {
            case .youtube: result = try await Services.youtube.search(q)
            case .radio: result = try await Services.radio.search(q)
            case .musicbrainz: result = try await Services.musicbrainz.search(q)
            }
            try Task.checkCancellation()
            state = .loaded(result)
            Services.resolver.prefetch(Array(result.prefix(1)))
        } catch is CancellationError {
            // A newer search replaced this one.
        } catch {
            guard !Task.isCancelled else { return }
            state = .failed((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }
}

struct SearchView: View {
    @EnvironmentObject private var player: PlayerModel
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var model = SearchModel()
    @FocusState private var focused: Bool

    private var sources: [SearchSource] {
        SearchSource.allCases.filter { s in
            switch s {
            case .youtube: return true
            case .radio: return settings.radioEnabled
            case .musicbrainz: return settings.musicbrainzEnabled
            }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14, pinnedViews: []) {
                    Text(L.t("tab.search")).font(.largeTitle.weight(.heavy)).foregroundStyle(.white).padding(.top, 8)
                    field
                    chips
                    results
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.immediately)
            .background(AppBackground())
            .toolbar(.hidden, for: .navigationBar)
        }
        .onChange(of: sources) { _, available in
            if !available.contains(model.source) { model.setSource(.youtube) }
        }
    }

    private var field: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.secondary)
            TextField("", text: $model.query, prompt: Text(L.t(placeholderKey)).foregroundStyle(Theme.tertiary))
                .foregroundStyle(.white)
                .tint(Theme.magenta)
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { model.submit() }
                .onChange(of: model.query) { _, _ in model.queryChanged() }
                .accessibilityIdentifier("search.field")
            if !model.query.isEmpty {
                Button {
                    model.query = ""
                    focused = true
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.t("action.clear"))
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
        .glass(cornerRadius: 26)
    }

    private var placeholderKey: String {
        switch model.source {
        case .youtube: return "search.placeholder_music"
        case .radio: return "search.placeholder_radio"
        case .musicbrainz: return "search.placeholder_mb"
        }
    }

    private var chips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(sources) { s in
                    PillChip(title: s.title, systemImage: s.icon, selected: model.source == s) { model.setSource(s) }
                        .accessibilityIdentifier("search.source.\(s.rawValue)")
                }
            }
        }
        .scrollIndicators(.hidden)
        .padding(.horizontal, -16)
        .contentMargins(.horizontal, 16, for: .scrollContent)
    }

    @ViewBuilder
    private var results: some View {
        switch model.state {
        case .idle:
            MessageView(systemImage: model.source.icon, title: L.t("search.idle_title"), detail: L.t(idleDetailKey))
                .padding(.top, 30)
        case .loading:
            HStack { Spacer(); ProgressView().tint(.white).scaleEffect(1.2); Spacer() }.padding(.top, 50)
        case .failed(let message):
            MessageView(systemImage: "exclamationmark.triangle.fill", title: L.t("search.error"), detail: message,
                        actionTitle: L.t("action.retry")) { model.submit() }
                .padding(.top, 30)
        case .loaded(let tracks):
            if tracks.isEmpty {
                MessageView(systemImage: "magnifyingglass", title: L.t("search.empty"), detail: L.t("search.empty_detail"))
                    .padding(.top, 30)
            } else {
                if model.source == .musicbrainz {
                    Text(L.t("search.mb_note")).font(.footnote).foregroundStyle(Theme.secondary)
                }
                VStack(spacing: 2) {
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { i, t in
                        TrackRow(track: t) {
                            focused = false
                            player.play(tracks, startAt: i)
                        }
                    }
                }
            }
        }
    }

    private var idleDetailKey: String {
        switch model.source {
        case .youtube: return "search.idle_music"
        case .radio: return "search.idle_radio"
        case .musicbrainz: return "search.idle_mb"
        }
    }
}
