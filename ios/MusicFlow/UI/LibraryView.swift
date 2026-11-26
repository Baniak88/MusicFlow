import SwiftUI

enum LibraryRoute: Hashable {
    case liked
    case history
    case playlist(UUID)
}

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var router: Router
    @State private var askName = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    header
                    shortcuts
                    playlists
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .background(AppBackground())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: LibraryRoute.self) { route in
                switch route {
                case .liked: LikedView()
                case .history: HistoryView()
                case .playlist(let id): PlaylistView(id: id)
                }
            }
        }
        .alert(L.t("library.new_playlist"), isPresented: $askName) {
            TextField(L.t("library.playlist_name"), text: $newName)
            Button(L.t("action.cancel"), role: .cancel) { newName = "" }
            Button(L.t("action.create")) {
                let p = library.createPlaylist(newName)
                newName = ""
                router.show(L.f("toast.playlist_created", p.name))
            }
        }
    }

    private var header: some View {
        HStack {
            Text(L.t("tab.library")).font(.largeTitle.weight(.heavy)).foregroundStyle(.white)
            Spacer()
            GlassIconButton(systemName: "plus", label: L.t("library.new_playlist")) { askName = true }
                .accessibilityIdentifier("library.new")
            GlassIconButton(systemName: "gearshape.fill", label: L.t("settings.title")) { router.showSettings = true }
        }
        .padding(.top, 8)
    }

    private var shortcuts: some View {
        VStack(spacing: 12) {
            NavigationLink(value: LibraryRoute.liked) {
                tile(icon: "heart.fill", title: L.t("library.liked"), detail: L.f("library.count", library.liked.count), wide: true)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("library.liked")
            NavigationLink(value: LibraryRoute.history) {
                tile(icon: "clock.arrow.circlepath", title: L.t("library.history"), detail: L.f("library.count", library.history.count), wide: true)
            }
            .buttonStyle(.plain)
        }
    }

    private func tile(icon: String, title: String, detail: String, wide: Bool) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 52, height: 52)
                .background(Circle().fill(Theme.accent))
                .shadow(color: Theme.magenta.opacity(0.35), radius: 10, y: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).foregroundStyle(.white)
                Text(detail).font(.subheadline).foregroundStyle(Theme.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(Theme.tertiary)
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .glass(cornerRadius: 24)
    }

    private var playlists: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: L.t("library.playlists"))
            if library.playlists.isEmpty {
                MessageView(systemImage: "music.note.list", title: L.t("library.no_playlists"), detail: L.t("library.no_playlists_detail"),
                            actionTitle: L.t("library.new_playlist")) { askName = true }
                    .glass(cornerRadius: 24)
            } else {
                VStack(spacing: 2) {
                    ForEach(library.playlists) { p in
                        NavigationLink(value: LibraryRoute.playlist(p.id)) {
                            HStack(spacing: 12) {
                                PlaylistCover(playlist: p, size: 56)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(p.name).font(.body.weight(.semibold)).foregroundStyle(.white).lineLimit(1)
                                    Text(L.f("library.count", p.tracks.count)).font(.subheadline).foregroundStyle(Theme.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(Theme.tertiary)
                            }
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 6)
                .glass(cornerRadius: 24)
            }
        }
    }
}

/// First track's cover, or a gradient tile for an empty playlist.
struct PlaylistCover: View {
    let playlist: Playlist
    var size: CGFloat

    var body: some View {
        if let t = playlist.tracks.first(where: { $0.artworkURL != nil }) {
            ArtworkView(url: t.artworkURL, source: t.source, size: size, cornerRadius: 16)
        } else {
            Image(systemName: "music.note.list")
                .font(.system(size: size * 0.36, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.accent))
        }
    }
}

struct TrackListScreen<Header: View>: View {
    let title: String
    let tracks: [Track]
    var removeTitle: String = L.t("action.remove_from_playlist")
    var onRemove: ((Int) -> Void)?
    var emptyTitle: String
    var emptyDetail: String?
    @ViewBuilder var header: () -> Header

    @EnvironmentObject private var player: PlayerModel

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 2) {
                header()
                if tracks.isEmpty {
                    MessageView(systemImage: "music.note", title: emptyTitle, detail: emptyDetail).padding(.top, 20)
                }
                ForEach(Array(tracks.enumerated()), id: \.offset) { i, t in
                    TrackRow(track: t, onRemove: onRemove.map { remove in { remove(i) } }, removeTitle: removeTitle) {
                        player.play(tracks, startAt: i)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(AppBackground())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
    }
}

struct LikedView: View {
    @EnvironmentObject private var library: LibraryStore

    var body: some View {
        TrackListScreen(
            title: L.t("library.liked"), tracks: library.liked, removeTitle: L.t("action.unlike"),
            onRemove: { i in if library.liked.indices.contains(i) { library.toggleLike(library.liked[i]) } },
            emptyTitle: L.t("library.liked_empty"), emptyDetail: L.t("library.liked_empty_detail")
        ) {
            ListHeader(title: L.t("library.liked"), subtitle: L.f("library.count", library.liked.count), systemImage: "heart.fill", tracks: library.liked)
        }
    }
}

struct HistoryView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var confirm = false

    var body: some View {
        TrackListScreen(title: L.t("library.history"), tracks: library.history, emptyTitle: L.t("library.history_empty")) {
            ListHeader(title: L.t("library.history"), subtitle: L.f("library.count", library.history.count), systemImage: "clock.arrow.circlepath", tracks: library.history)
        }
        .toolbar {
            if !library.history.isEmpty {
                Button(L.t("library.clear_history")) { confirm = true }
            }
        }
        .confirmationDialog(L.t("library.clear_history_confirm"), isPresented: $confirm, titleVisibility: .visible) {
            Button(L.t("library.clear_history"), role: .destructive) { library.clearHistory() }
        }
    }
}

struct PlaylistView: View {
    let id: UUID
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var name = ""
    @State private var confirmDelete = false

    var body: some View {
        let p = library.playlist(id)
        TrackListScreen(
            title: p?.name ?? "", tracks: p?.tracks ?? [],
            onRemove: { i in library.remove(at: IndexSet(integer: i), from: id) },
            emptyTitle: L.t("library.playlist_empty"), emptyDetail: L.t("library.playlist_empty_detail")
        ) {
            ListHeader(title: p?.name ?? "", subtitle: L.f("library.count", p?.tracks.count ?? 0), tracks: p?.tracks ?? [])
        }
        .toolbar {
            Menu {
                Button { name = p?.name ?? ""; renaming = true } label: { Label(L.t("library.rename"), systemImage: "pencil") }
                Button(role: .destructive) { confirmDelete = true } label: { Label(L.t("library.delete"), systemImage: "trash") }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
        .alert(L.t("library.rename"), isPresented: $renaming) {
            TextField(L.t("library.playlist_name"), text: $name)
            Button(L.t("action.cancel"), role: .cancel) {}
            Button(L.t("action.save")) { library.rename(id, to: name) }
        }
        .confirmationDialog(L.f("library.delete_confirm", p?.name ?? ""), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L.t("library.delete"), role: .destructive) {
                dismiss()
                library.deletePlaylist(id)
            }
        }
    }
}
