import Foundation

/// Turns a track into a playable stream, whatever its source.
final class StreamResolver: @unchecked Sendable {
    let youtube: YouTubeMusic
    let radio: RadioBrowser
    let musicbrainz: MusicBrainz

    private let lock = NSLock()
    /// MusicBrainz recording id → matched YouTube video id (this session), so replays are instant.
    private var matches: [String: String] = [:]
    private var prefetching = Set<String>()

    init(youtube: YouTubeMusic, radio: RadioBrowser, musicbrainz: MusicBrainz) {
        self.youtube = youtube
        self.radio = radio
        self.musicbrainz = musicbrainz
    }

    func resolve(_ track: Track) async throws -> ResolvedStream {
        switch track.source {
        case .youtube:
            return try await youtube.resolve(videoId: track.key)
        case .radio:
            return try await radio.resolve(uuid: track.key)
        case .musicbrainz:
            let videoId = try await match(track)
            return try await youtube.resolve(videoId: videoId)
        case nil:
            throw ProviderError.notSupported(L.t("error.unknown_source"))
        }
    }

    /// The YouTube Music recording of a MusicBrainz track — only a reliable match (title, artist, duration).
    func match(_ track: Track) async throws -> String {
        if let v = lock.withLock({ matches[track.key] }) { return v }
        let query = [track.artist, track.title].filter { !$0.isEmpty }.joined(separator: " ")
        let candidates = try await youtube.search(query)
        guard let best = AudioMatch.best(for: track, in: candidates) else { throw ProviderError.noMatch(track.title) }
        lock.withLock { matches[track.key] = best.key }
        return best.key
    }

    /// Resolves the next tracks in the background so they start without waiting. Live radio is
    /// skipped (its URL is fetched at play time and counted as a click only then).
    func prefetch(_ tracks: [Track]) {
        for t in tracks.prefix(2) where t.source == .youtube || t.source == .musicbrainz {
            guard lock.withLock({ prefetching.insert(t.id).inserted }) else { continue }
            Task.detached(priority: .utility) { [weak self] in
                _ = try? await self?.resolve(t)
                _ = self?.lock.withLock { self?.prefetching.remove(t.id) }
            }
        }
    }
}
