import Foundation

// Subset of the MusicBrainz JSON web service (ws/2) that MusicFlow reads.

struct MbArtistRef: Decodable, Hashable { var id: String = ""; var name: String = "" }

struct MbCredit: Decodable, Hashable {
    var name: String = ""
    var joinPhrase: String = ""
    enum CodingKeys: String, CodingKey { case name, joinPhrase = "joinphrase" }
    init(name: String, joinPhrase: String = "") { self.name = name; self.joinPhrase = joinPhrase }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        joinPhrase = (try? c.decodeIfPresent(String.self, forKey: .joinPhrase)) ?? ""
    }
}

struct MbReleaseGroup: Decodable, Hashable {
    var id: String = ""
    var title: String = ""
    var primaryType: String?
    var secondaryTypes: [String] = []
    enum CodingKeys: String, CodingKey { case id, title, primaryType = "primary-type", secondaryTypes = "secondary-types" }
    init(id: String, title: String = "", primaryType: String? = nil, secondaryTypes: [String] = []) {
        self.id = id; self.title = title; self.primaryType = primaryType; self.secondaryTypes = secondaryTypes
    }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? ""
        title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? ""
        primaryType = (try? c.decodeIfPresent(String.self, forKey: .primaryType))
        secondaryTypes = (try? c.decodeIfPresent([String].self, forKey: .secondaryTypes)) ?? []
    }
}

struct MbRelease: Decodable, Hashable {
    var id: String = ""
    var title: String = ""
    var status: String?
    var date: String?
    var releaseGroup: MbReleaseGroup?
    enum CodingKeys: String, CodingKey { case id, title, status, date, releaseGroup = "release-group" }
    init(id: String, title: String = "", status: String? = nil, date: String? = nil, releaseGroup: MbReleaseGroup? = nil) {
        self.id = id; self.title = title; self.status = status; self.date = date; self.releaseGroup = releaseGroup
    }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? ""
        title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? ""
        status = (try? c.decodeIfPresent(String.self, forKey: .status))
        date = (try? c.decodeIfPresent(String.self, forKey: .date))
        releaseGroup = (try? c.decodeIfPresent(MbReleaseGroup.self, forKey: .releaseGroup))
    }
}

struct MbRecording: Decodable, Hashable {
    var id: String = ""
    var title: String = ""
    /// Milliseconds.
    var length: Int?
    var score: Int?
    var artistCredit: [MbCredit] = []
    var releases: [MbRelease] = []
    var video: Bool = false
    enum CodingKeys: String, CodingKey { case id, title, length, score, artistCredit = "artist-credit", releases, video }
    init(id: String, title: String, length: Int? = nil, score: Int? = nil, artistCredit: [MbCredit] = [], releases: [MbRelease] = [], video: Bool = false) {
        self.id = id; self.title = title; self.length = length; self.score = score; self.artistCredit = artistCredit; self.releases = releases; self.video = video
    }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? ""
        title = (try? c.decodeIfPresent(String.self, forKey: .title)) ?? ""
        length = (try? c.decodeIfPresent(Int.self, forKey: .length))
        score = (try? c.decodeIfPresent(Int.self, forKey: .score))
        artistCredit = (try? c.decodeIfPresent([MbCredit].self, forKey: .artistCredit)) ?? []
        releases = (try? c.decodeIfPresent([MbRelease].self, forKey: .releases)) ?? []
        video = (try? c.decodeIfPresent(Bool.self, forKey: .video)) ?? false
    }
}

struct MbRecordingSearch: Decodable {
    var count: Int = 0
    var recordings: [MbRecording] = []
    enum CodingKeys: String, CodingKey { case count, recordings }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        count = (try? c.decodeIfPresent(Int.self, forKey: .count)) ?? 0
        recordings = try c.decodeIfPresent([MbRecording].self, forKey: .recordings) ?? []
    }
}

/// Serialises requests to at most one per `interval` (MusicBrainz asks for ≤ 1 request/s).
actor RateGate {
    private var next = Date.distantPast
    private let interval: TimeInterval
    init(interval: TimeInterval) { self.interval = interval }

    func wait() async throws {
        let now = Date()
        let slot = max(now, next)
        next = slot.addingTimeInterval(interval)
        let delay = slot.timeIntervalSince(now)
        if delay > 0 { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
    }
}

/// MusicBrainz — the open music encyclopedia — as a song catalogue, with covers from the
/// Cover Art Archive. It has no audio: a track is played through YouTube Music only when title,
/// artist and duration agree; otherwise it is reported as unavailable instead of playing a guess.
final class MusicBrainz: @unchecked Sendable {
    static let page = 25
    private static let base = URL(string: "https://musicbrainz.org/ws/2/")!

    private let http: HTTP
    private let gate: RateGate
    private let userAgent: String

    init(http: HTTP = .shared, userAgent: String = AppInfo.userAgent, minInterval: TimeInterval = 1.1) {
        self.http = http
        self.userAgent = userAgent
        self.gate = RateGate(interval: minInterval)
    }

    private func get<T: Decodable>(_ path: String, _ query: [String: String], as: T.Type) async throws -> T {
        var c = URLComponents(url: Self.base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        c.queryItems = (query.merging(["fmt": "json"]) { $1 }).sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        var attempt = 0
        while true {
            try await gate.wait()
            let (data, resp) = try await http.get(c.url!, headers: ["User-Agent": userAgent, "Accept": "application/json"])
            switch resp.statusCode {
            case 200..<300:
                do { return try JSONDecoder().decode(T.self, from: data) } catch { throw ProviderError.parsing("MusicBrainz") }
            case 503, 429:
                attempt += 1
                if attempt > 3 { throw ProviderError.rateLimited }
                let after = (resp.value(forHTTPHeaderField: "Retry-After").flatMap(Double.init)) ?? Double(attempt * 2)
                try await Task.sleep(nanoseconds: UInt64(min(after, 8) * 1_000_000_000))
            case 404:
                throw ProviderError.unavailable(L.t("mb.not_found"))
            default:
                throw ProviderError.network("MusicBrainz HTTP \(resp.statusCode)")
            }
        }
    }

    func search(_ query: String) async throws -> [Track] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        let terms = MbMapper.luceneTerms(q)
        // Free text only searches titles: also match the artist ("karma police radiohead").
        let any = "recording:(\(terms))^2 OR artist:(\(terms))"
        // Studio-first: recordings released on an album/single/EP, not live-only.
        let studio = "(\(any)) AND primarytype:(album OR single OR ep) AND NOT secondarytype:live"
        var result = try? await get("recording", ["query": studio, "limit": "100"], as: MbRecordingSearch.self)
        if result?.recordings.isEmpty ?? true {
            result = try await get("recording", ["query": any, "limit": "100"], as: MbRecordingSearch.self)
        }
        return MbMapper.rankRecordings(q, result?.recordings ?? []).prefix(Self.page).map(MbMapper.track)
    }
}

enum MbMapper {
    /// Cover Art Archive front image of a release group (may not exist; the UI falls back to a placeholder).
    static func cover(_ releaseGroupId: String, size: Int = 250) -> URL? {
        URL(string: "https://coverartarchive.org/release-group/\(releaseGroupId)/front-\(size)")
    }

    /// Query words for Lucene with its special characters removed (no syntax injection).
    static func luceneTerms(_ query: String) -> String {
        let cleaned = query.replacingOccurrences(of: #"[+\-&|!(){}\[\]^"~*?:\\/]"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return cleaned.isEmpty ? "*" : cleaned
    }

    static func credit(_ credits: [MbCredit]) -> String {
        credits.map { $0.name + $0.joinPhrase }.joined().trimmingCharacters(in: .whitespaces)
    }

    private static func titleFit(_ query: String, _ title: String, _ credit: String) -> Int {
        var q = " " + TextMatching.normalize(query) + " "
        let c = TextMatching.normalize(credit)
        if !c.isEmpty { q = q.replacingOccurrences(of: " " + c + " ", with: " ") }
        q = q.trimmingCharacters(in: .whitespaces)
        let t = TextMatching.normalize(title)
        if t == q { return 2 }
        return TextMatching.similarity(t, q) >= 0.8 ? 1 : 0
    }

    private static func mentions(_ query: String, _ credit: String) -> Bool {
        let c = TextMatching.normalize(credit)
        return !c.isEmpty && (" " + TextMatching.normalize(query) + " ").contains(" \(c) ")
    }

    /// The item is what was typed: its title is the whole query, or the query is artist + title.
    static func fits(_ query: String, _ title: String, _ credit: String) -> Bool {
        titleFit(query, title, "") == 2 || (mentions(query, credit) && titleFit(query, title, credit) >= 1)
    }

    /// Appears on an original album/single/EP (not live, compilation, bootleg…).
    static func onStudioRelease(_ r: MbRecording) -> Bool {
        r.releases.contains { rel in
            guard let g = rel.releaseGroup else { return false }
            return ["Album", "Single", "EP"].contains(g.primaryType ?? "") && g.secondaryTypes.isEmpty && rel.status != "Bootleg"
        }
    }

    /// Re-rank: what was typed first, then studio versions, then the most released version
    /// (a good proxy for "the well-known recording"), then MusicBrainz' score. Videos are dropped.
    static func rankRecordings(_ query: String, _ items: [MbRecording]) -> [MbRecording] {
        items.filter { !$0.video }.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            let fx = fits(query, x.title, credit(x.artistCredit)), fy = fits(query, y.title, credit(y.artistCredit))
            if fx != fy { return fx }
            let sx = onStudioRelease(x), sy = onStudioRelease(y)
            if sx != sy { return sx }
            if x.releases.count != y.releases.count { return x.releases.count > y.releases.count }
            if (x.score ?? 0) != (y.score ?? 0) { return (x.score ?? 0) > (y.score ?? 0) }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Prefer an official release, then the earliest dated one.
    static func representative(_ releases: [MbRelease]) -> MbRelease? {
        releases.enumerated().sorted { a, b in
            let oa = a.element.status == "Official" ? 0 : 1, ob = b.element.status == "Official" ? 0 : 1
            if oa != ob { return oa < ob }
            let da = (a.element.date?.isEmpty == false ? a.element.date! : "9999"), db = (b.element.date?.isEmpty == false ? b.element.date! : "9999")
            if da != db { return da < db }
            return a.offset < b.offset
        }.first?.element
    }

    static func track(_ r: MbRecording) -> Track {
        let release = representative(r.releases)
        let group = release?.releaseGroup
        return Track(
            id: Track.makeId(.musicbrainz, r.id),
            title: r.title,
            artist: credit(r.artistCredit),
            album: group?.title ?? release?.title,
            artworkURL: group.flatMap { cover($0.id) },
            duration: r.length.flatMap { $0 > 0 ? TimeInterval($0) / 1000 : nil }
        )
    }
}
