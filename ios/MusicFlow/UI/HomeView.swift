import SwiftUI

struct RadioGenre: Hashable, Identifiable {
    let tag: String
    let titleKey: String
    let icon: String
    var id: String { tag }

    static let all: [RadioGenre] = [
        .init(tag: "pop", titleKey: "genre.pop", icon: "sparkles"),
        .init(tag: "rock", titleKey: "genre.rock", icon: "guitars.fill"),
        .init(tag: "dance", titleKey: "genre.dance", icon: "figure.dance"),
        .init(tag: "jazz", titleKey: "genre.jazz", icon: "music.quarternote.3"),
        .init(tag: "classical", titleKey: "genre.classical", icon: "pianokeys"),
        .init(tag: "hip hop", titleKey: "genre.hiphop", icon: "headphones"),
        .init(tag: "electronic", titleKey: "genre.electronic", icon: "waveform"),
        .init(tag: "news", titleKey: "genre.news", icon: "newspaper.fill"),
    ]
}

@MainActor
final class RadioShelfModel: ObservableObject {
    @Published var state: LoadState<[Track]> = .idle
    private var loadedFor: String?

    func load(country: String?, force: Bool = false) async {
        let key = country ?? "*"
        if !force, loadedFor == key, state.value != nil { return }
        state = .loading
        do {
            let tracks = try await Services.radio.top(country: country, limit: 20)
            state = .loaded(tracks)
            loadedFor = key
        } catch is CancellationError {
            state = .idle
        } catch {
            state = .failed((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }
}

struct HomeView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var player: PlayerModel
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var radio = RadioShelfModel()

    private var country: String? { Locale.current.region?.identifier }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    header
                    if library.history.isEmpty && library.liked.isEmpty { welcome }
                    if !library.history.isEmpty {
                        shelf(L.t("home.recent"), library.history.prefix(15).map { $0 }, all: library.history)
                    }
                    if !library.liked.isEmpty {
                        shelf(L.t("home.liked"), library.liked.prefix(15).map { $0 }, all: library.liked)
                    }
                    if settings.radioEnabled { radioSection }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .background(AppBackground())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: RadioGenre.self) { RadioGenreView(genre: $0) }
            .refreshable { await radio.load(country: country, force: true) }
        }
        .task(id: settings.radioEnabled) {
            if settings.radioEnabled { await radio.load(country: country) }
        }
    }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        switch h {
        case 5..<12: return L.t("home.greeting_morning")
        case 12..<18: return L.t("home.greeting_afternoon")
        default: return L.t("home.greeting_evening")
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(greeting).font(.subheadline.weight(.medium)).foregroundStyle(Theme.secondary)
                Text("MusicFlow").font(.largeTitle.weight(.heavy)).foregroundStyle(.white)
            }
            Spacer()
            GlassIconButton(systemName: "magnifyingglass", label: L.t("tab.search")) { router.tab = .search }
            GlassIconButton(systemName: "gearshape.fill", label: L.t("settings.title")) { router.showSettings = true }
                .accessibilityIdentifier("home.settings")
        }
        .padding(.top, 8)
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: "sparkles").font(.title2.weight(.bold)).foregroundStyle(Theme.accent)
            Text(L.t("home.welcome_title")).font(.title3.weight(.bold)).foregroundStyle(.white)
            Text(L.t("home.welcome_text")).font(.subheadline).foregroundStyle(Theme.secondary)
            Button {
                router.tab = .search
            } label: {
                Label(L.t("home.welcome_action"), systemImage: "magnifyingglass")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 18).padding(.vertical, 11)
                    .background(Capsule().fill(Theme.accent))
            }
            .buttonStyle(.plain)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glass(cornerRadius: 28)
    }

    private func shelf(_ title: String, _ tracks: [Track], all: [Track]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: title)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { i, t in
                        TrackCard(track: t) { player.play(all, startAt: i) }
                    }
                }
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -16)
            .contentMargins(.horizontal, 16, for: .scrollContent)
        }
    }

    private var radioSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: L.t("home.radio"), subtitle: country.flatMap { Locale.current.localizedString(forRegionCode: $0) }.map { L.f("home.radio_region", $0) })
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(RadioGenre.all) { g in
                        NavigationLink(value: g) {
                            Label(L.t(g.titleKey), systemImage: g.icon)
                                .font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                                .padding(.horizontal, 14).padding(.vertical, 9)
                                .glassCapsule()
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -16)
            .contentMargins(.horizontal, 16, for: .scrollContent)

            switch radio.state {
            case .idle, .loading:
                HStack { Spacer(); ProgressView().tint(.white); Spacer() }.padding(.vertical, 24)
            case .failed(let message):
                MessageView(systemImage: "wifi.exclamationmark", title: L.t("home.radio_error"), detail: message,
                            actionTitle: L.t("action.retry")) { Task { await radio.load(country: country, force: true) } }
            case .loaded(let stations):
                if stations.isEmpty {
                    MessageView(systemImage: "dot.radiowaves.left.and.right", title: L.t("home.radio_empty"))
                } else {
                    VStack(spacing: 2) {
                        ForEach(Array(stations.prefix(10).enumerated()), id: \.element.id) { i, t in
                            TrackRow(track: t) { player.play(stations, startAt: i) }
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .glass(cornerRadius: 24)
                }
            }
        }
    }
}

struct RadioGenreView: View {
    let genre: RadioGenre
    @EnvironmentObject private var player: PlayerModel
    @State private var state: LoadState<[Track]> = .idle

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 2) {
                switch state {
                case .idle, .loading:
                    ProgressView().tint(.white).padding(.top, 60)
                case .failed(let m):
                    MessageView(systemImage: "wifi.exclamationmark", title: L.t("home.radio_error"), detail: m,
                                actionTitle: L.t("action.retry")) { Task { await load() } }
                case .loaded(let stations):
                    if stations.isEmpty { MessageView(systemImage: "dot.radiowaves.left.and.right", title: L.t("home.radio_empty")) }
                    ForEach(Array(stations.enumerated()), id: \.element.id) { i, t in
                        TrackRow(track: t) { player.play(stations, startAt: i) }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
        .background(AppBackground())
        .navigationTitle(L.t(genre.titleKey))
        .toolbarBackground(.hidden, for: .navigationBar)
        .task { if state.value == nil { await load() } }
    }

    private func load() async {
        state = .loading
        do { state = .loaded(try await Services.radio.byTag(genre.tag)) } catch is CancellationError { state = .idle } catch {
            state = .failed((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }
}
