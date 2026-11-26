import Foundation

/// Where a track comes from. The raw value is the id prefix, the same as in the Android app.
enum TrackSource: String, Codable, CaseIterable, Sendable {
    case youtube = "yt"
    case radio = "radio"
    case musicbrainz = "mbr"
}

/// A playable item: a song (YouTube Music, MusicBrainz) or a live radio station.
/// Only data reported by the source is stored here, nothing is made up.
struct Track: Codable, Hashable, Identifiable, Sendable {
    /// `<source>:<key>`, e.g. `yt:oCZugu1ea18`, `radio:<uuid>`, `mbr:<recording mbid>`.
    var id: String
    var title: String
    var artist: String
    var album: String?
    var artworkURL: URL?
    /// Seconds; nil when unknown or live.
    var duration: TimeInterval?
    var isLive: Bool = false

    static func makeId(_ source: TrackSource, _ key: String) -> String { "\(source.rawValue):\(key)" }

    var source: TrackSource? { TrackSource(rawValue: String(id.prefix { $0 != ":" })) }

    var key: String {
        guard let i = id.firstIndex(of: ":") else { return id }
        return String(id[id.index(after: i)...])
    }

    /// Public page of the item at its source (for sharing).
    var shareURL: URL? {
        switch source {
        case .youtube: return URL(string: "https://music.youtube.com/watch?v=\(key)")
        case .musicbrainz: return URL(string: "https://musicbrainz.org/recording/\(key)")
        default: return nil
        }
    }
}

struct Playlist: Codable, Hashable, Identifiable, Sendable {
    var id: UUID = UUID()
    var name: String
    var tracks: [Track] = []
    var created: Date = Date()
}

/// A stream URL ready for AVPlayer.
struct ResolvedStream: Sendable, Equatable {
    var url: URL
    var userAgent: String?
    var expiresAt: Date?
    /// As reported by the source (e.g. "AAC", "MP3"); never "lossless" for compressed audio.
    var codec: String?
    var bitrateKbps: Int?
    var isLive: Bool = false
    var duration: TimeInterval?
    /// YouTube progressive audio: read in bounded POST ranges (see YouTubeAudioLoader).
    var chunked: Bool = false
    var contentLength: Int64?

    /// e.g. "AAC · 128 kbps" — only what the source reported.
    var qualityLabel: String? {
        let parts = [codec, bitrateKbps.map { "\($0) kbps" }].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    func isFresh(at now: Date = Date()) -> Bool {
        guard let e = expiresAt else { return true }
        return e.timeIntervalSince(now) > 600
    }
}

enum AudioQuality: String, CaseIterable, Codable, Sendable {
    case high
    case saver
}

enum RepeatMode: String, Codable, Sendable {
    case off, all, one
}

/// Loading state for screens.
enum LoadState<Value> {
    case idle
    case loading
    case loaded(Value)
    case failed(String)

    var value: Value? { if case .loaded(let v) = self { return v } else { return nil } }
    var isLoading: Bool { if case .loading = self { return true } else { return false } }
}
