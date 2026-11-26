import SwiftUI

enum Tab: Hashable { case home, search, library }

/// App-wide UI state: tab, full player, sheets and short confirmations.
@MainActor
final class Router: ObservableObject {
    @Published var tab: Tab = .home
    @Published var showPlayer = false
    @Published var showSettings = false
    @Published var addToPlaylist: Track?
    @Published private(set) var toast: String?
    private var toastTask: Task<Void, Never>?

    func show(_ message: String) {
        toastTask?.cancel()
        withAnimation(.spring(duration: 0.3)) { toast = message }
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.25)) { self?.toast = nil }
        }
    }
}

struct ToastView: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 16).padding(.vertical, 11)
            .glass(cornerRadius: 18)
            .padding(.horizontal, 24)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .accessibilityIdentifier("toast")
    }
}

/// Cover art with a placeholder (also when the source has no cover, e.g. Cover Art Archive 404).
struct ArtworkView: View {
    let url: URL?
    var source: TrackSource?
    var size: CGFloat
    var circle = false
    var cornerRadius: CGFloat = 14

    var body: some View {
        ZStack {
            placeholder
            if let url {
                AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                    if case .success(let image) = phase {
                        image.resizable().scaledToFill()
                    }
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(circle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)))
        .overlay {
            (circle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)))
                .stroke(Color.white.opacity(0.08), lineWidth: 1)
        }
    }

    private var placeholder: some View {
        ZStack {
            LinearGradient(colors: [Theme.violet.opacity(0.55), Theme.magenta.opacity(0.35)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Image(systemName: source == .radio ? "dot.radiowaves.left.and.right" : "music.note")
                .font(.system(size: size * 0.34, weight: .semibold))
                .foregroundStyle(.white.opacity(0.85))
        }
    }
}

/// Track actions shown in "⋯" menus and long-press menus.
struct TrackMenuItems: View {
    let track: Track
    var onRemove: (() -> Void)?
    var removeTitle: String = L.t("action.remove_from_playlist")

    @EnvironmentObject private var player: PlayerModel
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var router: Router

    var body: some View {
        Button {
            player.playNext(track)
            router.show(L.f("toast.play_next", track.title))
        } label: { Label(L.t("action.play_next"), systemImage: "text.insert") }

        Button {
            player.addToQueue(track)
            router.show(L.f("toast.queued", track.title))
        } label: { Label(L.t("action.add_to_queue"), systemImage: "text.badge.plus") }

        Button {
            router.addToPlaylist = track
        } label: { Label(L.t("action.add_to_playlist"), systemImage: "music.note.list") }

        Button {
            let liked = library.toggleLike(track)
            router.show(L.t(liked ? "toast.liked" : "toast.unliked"))
        } label: {
            library.isLiked(track)
                ? Label(L.t("action.unlike"), systemImage: "heart.slash")
                : Label(L.t("action.like"), systemImage: "heart")
        }

        if let url = track.shareURL {
            ShareLink(item: url) { Label(L.t("action.share"), systemImage: "square.and.arrow.up") }
        }

        if let onRemove {
            Button(role: .destructive, action: onRemove) { Label(removeTitle, systemImage: "trash") }
        }
    }
}

/// One row in a track list: artwork, title, subtitle, "now playing" marker and a ⋯ menu.
struct TrackRow: View {
    let track: Track
    var onRemove: (() -> Void)?
    var removeTitle: String = L.t("action.remove_from_playlist")
    let onTap: () -> Void

    @EnvironmentObject private var player: PlayerModel

    var body: some View {
        let isCurrent = player.current?.id == track.id
        HStack(spacing: 12) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    ArtworkView(url: track.artworkURL, source: track.source, size: 52, circle: track.isLive, cornerRadius: 14)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(track.title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(isCurrent ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.white))
                            .lineLimit(1)
                        HStack(spacing: 5) {
                            if track.isLive {
                                Text(L.t("badge.live")).font(.caption2.weight(.heavy)).foregroundStyle(Theme.magenta)
                            }
                            Text(subtitle).font(.subheadline).foregroundStyle(Theme.secondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    if isCurrent && player.isPlaying {
                        Image(systemName: "waveform").foregroundStyle(Theme.accent).symbolEffect(.variableColor.iterative)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("track.row")

            Menu {
                TrackMenuItems(track: track, onRemove: onRemove, removeTitle: removeTitle)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Theme.secondary)
                    .frame(width: 36, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(L.t("action.more"))
        }
        .padding(.vertical, 4)
        .contextMenu { TrackMenuItems(track: track, onRemove: onRemove, removeTitle: removeTitle) }
    }

    private var subtitle: String {
        var parts = [track.artist]
        if let d = track.duration, !track.isLive { parts.append(Format.time(d)) }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// Square card for horizontal shelves.
struct TrackCard: View {
    let track: Track
    var size: CGFloat = 148
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 8) {
                ArtworkView(url: track.artworkURL, source: track.source, size: size, cornerRadius: 22)
                    .shadow(color: .black.opacity(0.35), radius: 10, y: 6)
                Text(track.title).font(.subheadline.weight(.semibold)).foregroundStyle(.white).lineLimit(1)
                Text(track.artist).font(.caption).foregroundStyle(Theme.secondary).lineLimit(1)
            }
            .frame(width: size, alignment: .leading)
        }
        .buttonStyle(.plain)
        .contextMenu { TrackMenuItems(track: track) }
    }
}

/// Big title + play / shuffle buttons for track list screens.
struct ListHeader: View {
    let title: String
    let subtitle: String
    var systemImage: String = "music.note.list"
    let tracks: [Track]

    @EnvironmentObject private var player: PlayerModel
    @EnvironmentObject private var router: Router

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 132, height: 132)
                .background(RoundedRectangle(cornerRadius: 32, style: .continuous).fill(Theme.accent))
                .shadow(color: Theme.magenta.opacity(0.4), radius: 22, y: 10)
            VStack(spacing: 4) {
                Text(title).font(.title2.weight(.bold)).foregroundStyle(.white).multilineTextAlignment(.center)
                Text(subtitle).font(.subheadline).foregroundStyle(Theme.secondary)
            }
            HStack(spacing: 12) {
                Button {
                    player.play(tracks, startAt: 0, shuffle: true)
                    router.showPlayer = true
                } label: {
                    Label(L.t("action.shuffle"), systemImage: "shuffle")
                        .font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .glassCapsule()
                }
                .buttonStyle(.plain)
                Button {
                    player.play(tracks, startAt: 0)
                    router.showPlayer = true
                } label: {
                    Label(L.t("action.play_all"), systemImage: "play.fill")
                        .font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(Capsule().fill(Theme.accent))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("list.play")
            }
            .disabled(tracks.isEmpty)
            .opacity(tracks.isEmpty ? 0.5 : 1)
        }
        .padding(.top, 8)
        .padding(.bottom, 6)
    }
}

/// Add-to-playlist sheet (also creates a new playlist).
struct AddToPlaylistSheet: View {
    let track: Track
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @State private var newName = ""
    @State private var askName = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        askName = true
                    } label: {
                        Label(L.t("library.new_playlist"), systemImage: "plus.circle.fill")
                            .foregroundStyle(Theme.magenta)
                    }
                    .accessibilityIdentifier("playlist.new")
                }
                Section {
                    if library.playlists.isEmpty {
                        Text(L.t("library.no_playlists")).foregroundStyle(Theme.secondary)
                    }
                    ForEach(library.playlists) { p in
                        Button {
                            add(to: p)
                        } label: {
                            HStack {
                                Image(systemName: "music.note.list").foregroundStyle(Theme.magenta)
                                VStack(alignment: .leading) {
                                    Text(p.name).foregroundStyle(.white)
                                    Text(L.f("library.count", p.tracks.count)).font(.caption).foregroundStyle(Theme.secondary)
                                }
                            }
                        }
                    }
                } header: {
                    Text(L.t("library.playlists"))
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .navigationTitle(L.t("action.add_to_playlist"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L.t("action.cancel")) { dismiss() } }
            }
            .alert(L.t("library.new_playlist"), isPresented: $askName) {
                TextField(L.t("library.playlist_name"), text: $newName)
                Button(L.t("action.cancel"), role: .cancel) { newName = "" }
                Button(L.t("action.create")) {
                    let p = library.createPlaylist(newName)
                    newName = ""
                    add(to: p)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }

    private func add(to p: Playlist) {
        let added = library.add(track, to: p.id)
        router.show(added ? L.f("toast.added_to", p.name) : L.f("toast.already_in", p.name))
        dismiss()
    }
}
