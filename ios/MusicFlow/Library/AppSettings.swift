import Foundation

/// User settings, stored in UserDefaults on the device only.
@MainActor
final class AppSettings: ObservableObject {
    private let defaults: UserDefaults

    @Published var radioEnabled: Bool { didSet { defaults.set(radioEnabled, forKey: "radioEnabled") } }
    @Published var musicbrainzEnabled: Bool { didSet { defaults.set(musicbrainzEnabled, forKey: "musicbrainzEnabled") } }
    /// Also offer stations that stream over unencrypted HTTP (many small broadcasters).
    @Published var radioAllowHTTP: Bool { didSet { defaults.set(radioAllowHTTP, forKey: "radioAllowHTTP") } }
    @Published var quality: AudioQuality { didSet { defaults.set(quality.rawValue, forKey: "quality") } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        defaults.register(defaults: ["radioEnabled": true, "musicbrainzEnabled": true, "radioAllowHTTP": true, "quality": AudioQuality.high.rawValue])
        radioEnabled = defaults.bool(forKey: "radioEnabled")
        musicbrainzEnabled = defaults.bool(forKey: "musicbrainzEnabled")
        radioAllowHTTP = defaults.bool(forKey: "radioAllowHTTP")
        quality = AudioQuality(rawValue: defaults.string(forKey: "quality") ?? "") ?? .high
    }

    /// Read from provider threads (UserDefaults is thread-safe).
    nonisolated static func current(_ key: String, defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: key) == nil ? true : defaults.bool(forKey: key)
    }

    nonisolated static func currentQuality(defaults: UserDefaults = .standard) -> AudioQuality {
        AudioQuality(rawValue: defaults.string(forKey: "quality") ?? "") ?? .high
    }
}

enum AppInfo {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0" }
    static var userAgent: String { "MusicFlow-iOS/\(version) ( https://github.com/Baniak88/MusicFlow )" }
    static let repository = URL(string: "https://github.com/Baniak88/MusicFlow")!
}

/// Shared providers.
enum Services {
    static let youtube = YouTubeMusic(quality: { AppSettings.currentQuality() })
    static let networks = RadioNetworks()
    static let radio = RadioBrowser(allowHTTP: { AppSettings.current("radioAllowHTTP") }, networks: networks)
    static let musicbrainz = MusicBrainz()
    static let resolver = StreamResolver(youtube: youtube, radio: radio, musicbrainz: musicbrainz)
}
