import Foundation

/// YouTube Music through its public web API ("InnerTube"), the same requests NewPipe Extractor
/// makes on Android: search with the WEB_REMIX client, audio streams with the VISIONOS client
/// (direct AAC URLs that AVPlayer plays natively, no signature deciphering needed).
///
/// This is an unofficial integration; when YouTube changes its API it can break (see PROVIDERS.md).
final class YouTubeMusic: @unchecked Sendable {
    /// WEB_REMIX version NewPipe Extractor v0.27.1 sends.
    static let musicClientVersion = "1.20260804.16.00"
    /// "Songs" filter, exactly as NewPipe sends it (percent-encoded padding included).
    static let songsParams = "Eg-KAQwIARAAGAAgACgAMABqChAEEAUQAxAKEAk%3D"

    private let http: HTTP
    private let quality: () -> AudioQuality
    private let lock = NSLock()
    private var visitor: (value: String, at: Date)?
    private var streams: [String: ResolvedStream] = [:]

    init(http: HTTP = .shared, quality: @escaping () -> AudioQuality = { .high }) {
        self.http = http
        self.quality = quality
    }

    private var hl: String { Locale.current.language.languageCode?.identifier ?? "en" }
    private var gl: String { Locale.current.region?.identifier ?? "US" }

    // MARK: - Search

    func search(_ query: String) async throws -> [Track] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        let songs = YTMParser.tracks(try await rawSearch(q, params: Self.songsParams))
        if !songs.isEmpty { return songs }
        // Some networks get an empty "Songs" tab; the unfiltered results still list songs and videos.
        return YTMParser.tracks(try await rawSearch(q, params: nil))
    }

    private func rawSearch(_ q: String, params: String?) async throws -> Any {
        var body: [String: Any] = [
            "context": [
                "client": ["clientName": "WEB_REMIX", "clientVersion": Self.musicClientVersion, "hl": hl, "gl": gl, "platform": "DESKTOP", "utcOffsetMinutes": 0],
                "request": ["internalExperimentFlags": [Any](), "useSsl": true],
                "user": ["lockedSafetyMode": false],
            ] as [String: Any],
            "query": q,
        ]
        if let params { body["params"] = params }
        let (data, resp) = try await http.postJSON(
            URL(string: "https://music.youtube.com/youtubei/v1/search?prettyPrint=false")!,
            body: body,
            headers: [
                "Origin": "https://music.youtube.com",
                "Referer": "https://music.youtube.com",
                "X-YouTube-Client-Name": "67",
                "X-YouTube-Client-Version": Self.musicClientVersion,
            ]
        )
        try Self.check(resp)
        return try J.parse(data)
    }

    static func check(_ r: HTTPURLResponse) throws {
        switch r.statusCode {
        case 200..<300: return
        case 429: throw ProviderError.rateLimited
        default: throw ProviderError.network("YouTube HTTP \(r.statusCode)")
        }
    }

    // MARK: - Streams

    var userAgent: String {
        "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; \(gl))"
    }

    private func visionContext(_ visitorData: String?) -> [String: Any] {
        var client: [String: Any] = [
            "clientName": "VISIONOS", "clientVersion": "1.04", "clientScreen": "WATCH", "platform": "MOBILE",
            "deviceMake": "Apple", "deviceModel": "RealityDevice17,1", "osName": "visionOS", "osVersion": "26.6.0.23O770",
            "hl": hl, "gl": gl, "utcOffsetMinutes": 0,
        ]
        if let visitorData { client["visitorData"] = visitorData }
        return ["client": client, "request": ["internalExperimentFlags": [Any](), "useSsl": true], "user": ["lockedSafetyMode": false]]
    }

    private var apiHeaders: [String: String] { ["User-Agent": userAgent, "X-Goog-Api-Format-Version": "2"] }

    private func visitorData(refresh: Bool = false) async throws -> String {
        if !refresh, let v = lock.withLock({ visitor }), Date().timeIntervalSince(v.at) < 6 * 3600 { return v.value }
        let (data, resp) = try await http.postJSON(
            URL(string: "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")!,
            body: ["context": visionContext(nil)], headers: apiHeaders
        )
        try Self.check(resp)
        guard let v = J.str(J.at(try J.parse(data), "responseContext", "visitorData")), !v.isEmpty else {
            throw ProviderError.parsing("visitorData")
        }
        lock.withLock { visitor = (v, Date()) }
        return v
    }

    func cachedStream(_ videoId: String) -> ResolvedStream? {
        lock.withLock { streams[videoId] }.flatMap { $0.isFresh() ? $0 : nil }
    }

    func resolve(videoId: String) async throws -> ResolvedStream {
        if let s = cachedStream(videoId) { return s }
        do {
            return try await player(videoId, refreshVisitor: false)
        } catch let e as ProviderError {
            // A stale visitor id can make YouTube ask for a sign-in; retry once with a fresh one.
            if case .restricted = e { return try await player(videoId, refreshVisitor: true) }
            if case .parsing = e { return try await player(videoId, refreshVisitor: true) }
            throw e
        }
    }

    private func player(_ videoId: String, refreshVisitor: Bool) async throws -> ResolvedStream {
        let vd = try await visitorData(refresh: refreshVisitor)
        var c = URLComponents(string: "https://youtubei.googleapis.com/youtubei/v1/player")!
        c.queryItems = [.init(name: "prettyPrint", value: "false"), .init(name: "t", value: Self.random(12)), .init(name: "id", value: videoId)]
        let body: [String: Any] = [
            "context": visionContext(vd), "videoId": videoId, "cpn": Self.random(16),
            "contentCheckOk": true, "racyCheckOk": true,
        ]
        let (data, resp) = try await http.postJSON(c.url!, body: body, headers: apiHeaders)
        try Self.check(resp)
        let s = try YTMParser.stream(try J.parse(data), quality: quality(), userAgent: userAgent)
        lock.withLock { streams[videoId] = s }
        return s
    }

    static func random(_ n: Int) -> String {
        let chars = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        return String((0..<n).map { _ in chars.randomElement()! })
    }
}

/// Pure parsing of InnerTube responses (unit-tested with recorded responses).
enum YTMParser {
    private static let skippedPageTypes = ["PODCAST", "NON_MUSIC", "EPISODE", "AUDIOBOOK"]
    private static let typeLabels: Set<String> = [
        "song", "utwór", "video", "film", "teledysk", "episode", "odcinek", "single", "singiel", "ep", "album",
    ]

    struct Run { let text: String; let browseId: String?; let pageType: String? }

    static func runs(_ column: Any?) -> [Run] {
        ((J.at(column, "text", "runs") as? [Any]) ?? []).compactMap { r in
            guard let text = J.str(J.at(r, "text")) else { return nil }
            let be = J.at(r, "navigationEndpoint", "browseEndpoint")
            return Run(
                text: text,
                browseId: J.str(J.at(be, "browseId")),
                pageType: J.str(J.at(be, "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType"))
            )
        }
    }

    /// Songs and music videos from a search response (any layout: shelves or item sections).
    static func tracks(_ root: Any) -> [Track] {
        var seen = Set<String>()
        var out: [Track] = []
        for r in J.collect("musicResponsiveListItemRenderer", in: root) {
            guard let t = track(r), seen.insert(t.id).inserted else { continue }
            out.append(t)
        }
        return out
    }

    static func track(_ r: [String: Any]) -> Track? {
        let watch = J.at(r, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
        guard let videoId = J.str(J.at(r, "playlistItemData", "videoId")) ?? J.str(J.at(watch, "videoId")) else { return nil }
        let columns = ((r["flexColumns"] as? [Any]) ?? []).map { runs(J.at($0, "musicResponsiveListItemFlexColumnRenderer")) }
        guard let titleRuns = columns.first else { return nil }
        let title = titleRuns.map(\.text).joined().trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { return nil }
        let meta = columns.dropFirst().flatMap { $0 }
        if (titleRuns + meta).contains(where: { run in skippedPageTypes.contains { (run.pageType ?? "").contains($0) } }) { return nil }

        var artists = meta.filter { $0.pageType == "MUSIC_PAGE_TYPE_ARTIST" || ($0.browseId?.hasPrefix("UC") ?? false) }.map(\.text)
        let album = meta.first { $0.pageType == "MUSIC_PAGE_TYPE_ALBUM" || ($0.browseId?.hasPrefix("MPREb") ?? false) }?.text
        let fixed = ((r["fixedColumns"] as? [Any]) ?? []).flatMap { runs(J.at($0, "musicResponsiveListItemFixedColumnRenderer")) }
        let duration = (meta + fixed).reversed().lazy.compactMap { Format.parseDuration($0.text) }.first

        if artists.isEmpty {
            // Unlinked artist: first " • " segment that is not a type label, a duration or a count.
            let segments = meta.map(\.text).joined().components(separatedBy: " • ").map { $0.trimmingCharacters(in: .whitespaces) }
            if let s = segments.first(where: { s in
                !s.isEmpty && !typeLabels.contains(s.lowercased()) && Format.parseDuration(s) == nil && !(s.first?.isNumber ?? false)
            }) { artists = [s] }
        }

        return Track(
            id: Track.makeId(.youtube, videoId),
            title: title,
            artist: artists.joined(separator: ", "),
            album: album,
            artworkURL: artwork(J.at(r, "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")),
            duration: duration
        )
    }

    /// Largest thumbnail; square Google-hosted covers are requested at 544 px.
    static func artwork(_ thumbnails: Any?) -> URL? {
        guard let list = thumbnails as? [Any], let last = list.last, var url = J.str(J.at(last, "url")) else { return nil }
        if url.contains("googleusercontent.com") || url.contains("ggpht.com") {
            url = url.replacingOccurrences(of: #"=w\d+-h\d+"#, with: "=w544-h544", options: .regularExpression)
        }
        if url.hasPrefix("//") { url = "https:" + url }
        return URL(string: url)
    }

    /// Picks the audio stream: AAC (AVPlayer cannot play WebM/Opus), 128 kbps or 48 kbps in saver mode.
    static func stream(_ root: Any, quality: AudioQuality, userAgent: String, now: Date = Date()) throws -> ResolvedStream {
        let status = J.str(J.at(root, "playabilityStatus", "status")) ?? ""
        let reason = J.str(J.at(root, "playabilityStatus", "reason")) ?? ""
        switch status {
        case "OK": break
        case "LOGIN_REQUIRED", "AGE_CHECK_REQUIRED", "CONTENT_CHECK_REQUIRED": throw ProviderError.restricted(reason)
        case "": throw ProviderError.parsing("playabilityStatus")
        default: throw ProviderError.unavailable(reason)
        }
        let sd = J.at(root, "streamingData")
        let expires = J.int(J.at(sd, "expiresInSeconds")).map { now.addingTimeInterval(TimeInterval($0)) }
        let length = J.int(J.at(root, "videoDetails", "lengthSeconds")).flatMap { $0 > 0 ? TimeInterval($0) : nil }
        let isLive = (J.at(root, "videoDetails", "isLive") as? Bool) ?? false

        let formats = ((J.at(sd, "adaptiveFormats") as? [Any]) ?? []).compactMap { $0 as? [String: Any] }
        let aac = formats.filter { f in
            (J.str(f["mimeType"])?.hasPrefix("audio/mp4") ?? false) && J.str(f["url"]) != nil
        }
        func bitrate(_ f: [String: Any]) -> Int { J.int(f["averageBitrate"]) ?? J.int(f["bitrate"]) ?? 0 }
        let preferred = quality == .saver ? 139 : 140
        let chosen = aac.first { J.int($0["itag"]) == preferred }
            ?? (quality == .saver ? aac.min { bitrate($0) < bitrate($1) } : aac.max { bitrate($0) < bitrate($1) })

        if !isLive, let f = chosen, let url = URL(string: J.str(f["url"])!) {
            let kbps = bitrate(f) / 1000
            return ResolvedStream(url: url, userAgent: userAgent, expiresAt: expires, codec: "AAC",
                                  bitrateKbps: kbps > 0 ? kbps : nil, isLive: false, duration: length)
        }
        if let hls = J.str(J.at(sd, "hlsManifestUrl")), let url = URL(string: hls) {
            return ResolvedStream(url: url, userAgent: userAgent, expiresAt: expires, codec: nil, bitrateKbps: nil, isLive: isLive, duration: length)
        }
        throw ProviderError.unavailable("")
    }
}
