import Foundation
import SwiftUI // move(fromOffsets:toOffset:), remove(atOffsets:)

/// Likes, playlists and listening history, stored as JSON on the device only.
@MainActor
final class LibraryStore: ObservableObject {
    struct Snapshot: Codable {
        var version = 1
        var liked: [Track] = []
        var playlists: [Playlist] = []
        var history: [Track] = []
    }

    static let historyLimit = 200

    @Published private(set) var liked: [Track] = []
    @Published private(set) var playlists: [Playlist] = []
    /// Most recent first, one entry per track.
    @Published private(set) var history: [Track] = []

    private let fileURL: URL

    init(fileURL: URL = LibraryStore.defaultURL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL), let s = try? JSONDecoder().decode(Snapshot.self, from: data) {
            liked = s.liked
            playlists = s.playlists
            history = s.history
        }
    }

    nonisolated static var defaultURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("library.json")
    }

    private func save() {
        let snapshot = Snapshot(liked: liked, playlists: playlists, history: history)
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(snapshot)
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            NSLog("MusicFlow: library not saved: \(error)")
        }
    }

    // MARK: Likes

    func isLiked(_ t: Track) -> Bool { liked.contains { $0.id == t.id } }

    /// Returns the new state.
    @discardableResult
    func toggleLike(_ t: Track) -> Bool {
        if let i = liked.firstIndex(where: { $0.id == t.id }) {
            liked.remove(at: i)
            save()
            return false
        }
        liked.insert(t, at: 0)
        save()
        return true
    }

    // MARK: Playlists

    @discardableResult
    func createPlaylist(_ name: String) -> Playlist {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = Playlist(name: trimmed.isEmpty ? L.t("library.new_playlist_default") : trimmed)
        playlists.insert(p, at: 0)
        save()
        return p
    }

    func playlist(_ id: UUID) -> Playlist? { playlists.first { $0.id == id } }

    /// False when the track is already in the playlist.
    @discardableResult
    func add(_ t: Track, to id: UUID) -> Bool {
        guard let i = playlists.firstIndex(where: { $0.id == id }) else { return false }
        if playlists[i].tracks.contains(where: { $0.id == t.id }) { return false }
        playlists[i].tracks.append(t)
        save()
        return true
    }

    func remove(at offsets: IndexSet, from id: UUID) {
        guard let i = playlists.firstIndex(where: { $0.id == id }) else { return }
        playlists[i].tracks.remove(atOffsets: offsets)
        save()
    }

    func move(from source: IndexSet, to destination: Int, in id: UUID) {
        guard let i = playlists.firstIndex(where: { $0.id == id }) else { return }
        playlists[i].tracks.move(fromOffsets: source, toOffset: destination)
        save()
    }

    func rename(_ id: UUID, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let i = playlists.firstIndex(where: { $0.id == id }) else { return }
        playlists[i].name = trimmed
        save()
    }

    func deletePlaylist(_ id: UUID) {
        playlists.removeAll { $0.id == id }
        save()
    }

    // MARK: History

    func recordPlay(_ t: Track) {
        history.removeAll { $0.id == t.id }
        history.insert(t, at: 0)
        if history.count > Self.historyLimit { history.removeLast(history.count - Self.historyLimit) }
        save()
    }

    func clearHistory() {
        history = []
        save()
    }
}
