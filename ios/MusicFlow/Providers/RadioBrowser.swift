import Foundation

/// A station as returned by the Radio Browser API (only the fields MusicFlow uses).
struct RadioStation: Decodable, Hashable, Sendable {
    var uuid: String
    var name: String = ""
    var url: String = ""
    var urlResolved: String = ""
    var favicon: String = ""
    var tags: String = ""
    var country: String = ""
    /// Region/city as entered in the directory (e.g. "Kielce").
    var state: String = ""
    var countryCode: String = ""
    var codec: String = ""
    var bitrate: Int = 0
    var hls: Int = 0
    var lastCheckOk: Int = 1
    var clickCount: Int = 0

    enum CodingKeys: String, CodingKey {
        case uuid = "stationuuid", name, url, urlResolved = "url_resolved", favicon, tags, country, state
        case countryCode = "countrycode", codec, bitrate, hls, lastCheckOk = "lastcheckok", clickCount = "clickcount"
    }

    init(uuid: String, name: String = "", url: String = "", urlResolved: String = "", favicon: String = "", tags: String = "",
         country: String = "", state: String = "", countryCode: String = "", codec: String = "", bitrate: Int = 0,
         hls: Int = 0, lastCheckOk: Int = 1, clickCount: Int = 0) {
        self.uuid = uuid; self.name = name; self.url = url; self.urlResolved = urlResolved; self.favicon = favicon
        self.tags = tags; self.country = country; self.state = state; self.countryCode = countryCode; self.codec = codec
        self.bitrate = bitrate; self.hls = hls; self.lastCheckOk = lastCheckOk; self.clickCount = clickCount
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func s(_ k: CodingKeys) -> String { ((try? c.decodeIfPresent(String.self, forKey: k)) ?? nil) ?? "" }
        func i(_ k: CodingKeys) -> Int? {
            if let v = try? c.decodeIfPresent(Int.self, forKey: k) { return v }
            if let v = try? c.decodeIfPresent(String.self, forKey: k) { return Int(v) }
            return nil
        }
        uuid = try c.decode(String.self, forKey: .uuid)
        name = s(.name); url = s(.url); urlResolved = s(.urlResolved); favicon = s(.favicon); tags = s(.tags)
        country = s(.country); state = s(.state); countryCode = s(.countryCode); codec = s(.codec)
        bitrate = i(.bitrate) ?? 0; hls = i(.hls) ?? 0; lastCheckOk = i(.lastCheckOk) ?? 1; clickCount = i(.clickCount) ?? 0
    }
}

/// Internet radio from the community directory Radio Browser (open data, no key).
/// Playing a station is reported to `/json/url/<uuid>` (station id only), as the API asks.
final class RadioBrowser: @unchecked Sendable {
    static let page = 40
    static let servers = ["de1", "fi1", "de2", "nl1"].map { URL(string: "https://\($0).api.radio-browser.info/")! }

    private let http: HTTP
    private let allowHTTP: () -> Bool
    private let userAgent: String
    private let lock = NSLock()
    private var preferred = 0

    init(http: HTTP = .shared, userAgent: String = AppInfo.userAgent, allowHTTP: @escaping () -> Bool = { true }) {
        self.http = http
        self.userAgent = userAgent
        self.allowHTTP = allowHTTP
    }

    private func get(_ path: String, _ query: [String: String] = [:]) async throws -> Data {
        var last: Error = ProviderError.network("Radio Browser")
        let start = lock.withLock { preferred }
        for attempt in 0..<Self.servers.count {
            let i = (start + attempt) % Self.servers.count
            var c = URLComponents(url: Self.servers[i].appendingPathComponent(path), resolvingAgainstBaseURL: false)!
            if !query.isEmpty { c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) } }
            do {
                let (data, resp) = try await http.get(c.url!, headers: ["User-Agent": userAgent])
                if resp.statusCode == 429 { throw ProviderError.rateLimited }
                guard (200..<300).contains(resp.statusCode) else { throw ProviderError.network("Radio Browser HTTP \(resp.statusCode)") }
                lock.withLock { preferred = i }
                return data
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                last = error
            }
        }
        throw last
    }

    private func stations(_ query: [String: String]) async throws -> [RadioStation] {
        var q = query
        if !allowHTTP() { q["is_https"] = "true" }
        let data = try await get("json/stations/search", q)
        do {
            return try JSONDecoder().decode([RadioStation].self, from: data).filter { RadioSearch.playable($0, allowHTTP: allowHTTP()) }
        } catch {
            throw ProviderError.parsing("Radio Browser")
        }
    }

    private func common(_ limit: Int = page) -> [String: String] {
        ["limit": "\(limit)", "offset": "0", "hidebroken": "true", "order": "clickcount", "reverse": "true"]
    }

    /// "radio fama kielce": searches the name, then each distinctive word in names and regions and
    /// the whole query as a genre tag, and ranks stations matching the most words first.
    func search(_ query: String) async throws -> [Track] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        let spaced = RadioSearch.unglue(q)
        var byName = try await stations(common().merging(["name": q]) { $1 })
        if spaced != q { byName += (try? await stations(common().merging(["name": spaced]) { $1 })) ?? [] }
        let words = RadioSearch.words(spaced)
        var queries: [[String: String]] = [common().merging(["tag": q.lowercased()]) { $1 }]
        if words.count > 1 || byName.count < 5 {
            for w in words.prefix(3) {
                queries.append(common().merging(["name": w]) { $1 })
                queries.append(common().merging(["state": w]) { $1 })
            }
        }
        let extra = await withTaskGroup(of: [RadioStation].self) { group in
            for query in queries { group.addTask { (try? await self.stations(query)) ?? [] } }
            var all: [RadioStation] = []
            for await r in group { all += r }
            return all
        }
        var seen = Set<String>()
        let unique = (byName + extra).filter { seen.insert($0.uuid).inserted }
        return RadioSearch.rank(spaced, RadioSearch.bestVersions(unique)).prefix(60).map(RadioSearch.track)
    }

    /// Most popular stations in a country (ISO code), or worldwide when nil.
    func top(country: String?, limit: Int = 30) async throws -> [Track] {
        var q = common(limit)
        if let country { q["countrycode"] = country }
        return RadioSearch.bestVersions(try await stations(q)).map(RadioSearch.track)
    }

    func byTag(_ tag: String, limit: Int = 40) async throws -> [Track] {
        RadioSearch.bestVersions(try await stations(common(limit).merging(["tag": tag]) { $1 })).map(RadioSearch.track)
    }

    private struct Click: Decodable { var url: String? }

    func resolve(uuid: String) async throws -> ResolvedStream {
        let data = try await get("json/stations/byuuid/\(uuid)")
        guard let s = (try? JSONDecoder().decode([RadioStation].self, from: data))?.first else {
            throw ProviderError.unavailable(L.t("radio.not_listed"))
        }
        let counted = (try? await get("json/url/\(s.uuid)")).flatMap { try? JSONDecoder().decode(Click.self, from: $0) }?.url
        let candidates = [counted, s.urlResolved, s.url].compactMap { $0 }.filter { !$0.isEmpty }
        guard let chosen = candidates.first(where: { $0.lowercased().hasPrefix("https://") })
            ?? candidates.first(where: { allowHTTP() && $0.lowercased().hasPrefix("http://") }),
            let url = URL(string: chosen) else {
            throw ProviderError.notSupported(L.t("radio.http_only"))
        }
        let codec = s.codec.isEmpty || s.codec.uppercased() == "UNKNOWN" ? nil : s.codec.uppercased()
        return ResolvedStream(url: url, userAgent: userAgent, expiresAt: nil, codec: codec,
                              bitrateKbps: s.bitrate > 0 ? s.bitrate : nil, isLive: true, duration: nil)
    }
}

/// Word-based station matching (port of the Android `RadioSearch`), pure and unit-tested.
enum RadioSearch {
    private static let generic: Set<String> = ["radio", "fm", "am", "stacja", "station", "the", "online", "live", "polskie", "polska", "music", "muzyka"]

    static func words(_ query: String) -> [String] {
        var seen = Set<String>()
        return TextMatching.normalize(query).split(separator: " ").map(String.init)
            .filter { $0.count >= 3 && !generic.contains($0) && seen.insert($0).inserted }
            .enumerated().sorted { $0.element.count != $1.element.count ? $0.element.count > $1.element.count : $0.offset < $1.offset }
            .map(\.element)
    }

    private static let glue = try! NSRegularExpression(pattern: #"^(.{3,}?)(radio|fm|music)$|^(radio|open)(.{3,})$"#, options: [.caseInsensitive])

    /// "danceradio" → "dance radio", "openfm" → "open fm", "radiozet" → "radio zet".
    static func unglue(_ query: String) -> String {
        query.trimmingCharacters(in: .whitespaces).split(whereSeparator: { $0.isWhitespace }).map { sub -> String in
            let w = String(sub)
            let ns = w as NSString
            guard let m = glue.firstMatch(in: w, range: NSRange(location: 0, length: ns.length)) else { return w }
            func g(_ i: Int) -> String { m.range(at: i).location == NSNotFound ? "" : ns.substring(with: m.range(at: i)) }
            return g(1).isEmpty ? "\(g(3)) \(g(4))" : "\(g(1)) \(g(2))"
        }.joined(separator: " ")
    }

    /// "MP3 320 kbps" — only what the directory reports.
    static func quality(_ s: RadioStation) -> String? {
        let codec = s.codec.isEmpty || s.codec.uppercased() == "UNKNOWN" ? nil : s.codec
        let kbps = s.bitrate > 0 ? "\(s.bitrate) kbps" : nil
        let parts = [codec, kbps].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    private static let bitrateInURL = try! NSRegularExpression(pattern: #"(?:mp3|aac)[-_]?(\d{2,3})|(\d{3})\.mp3"#, options: [.caseInsensitive])

    /// Bitrate, with HE-AAC (AAC+) counted as roughly double.
    static func qualityScore(_ s: RadioStation) -> Int {
        var b = s.bitrate
        if b <= 0 {
            let u = (s.urlResolved.isEmpty ? s.url : s.urlResolved) as NSString
            if let m = bitrateInURL.firstMatch(in: u as String, range: NSRange(location: 0, length: u.length)) {
                for i in 1...2 where m.range(at: i).location != NSNotFound {
                    b = Int(u.substring(with: m.range(at: i))) ?? 0
                    break
                }
            }
        }
        return s.codec.contains("+") ? b * 2 : b
    }

    /// One entry per station name: best sound quality, HTTPS if equal, then most popular.
    /// Popularity of the kept entry is the sum of all its listings; first-appearance order is kept.
    static func bestVersions(_ stations: [RadioStation]) -> [RadioStation] {
        var order: [String] = []
        var groups: [String: [RadioStation]] = [:]
        for s in stations {
            let k = TextMatching.normalize(s.name)
            if groups[k] == nil { order.append(k) }
            groups[k, default: []].append(s)
        }
        return order.map { k in
            let list = groups[k]!
            var best = list.max { a, b in
                let qa = qualityScore(a), qb = qualityScore(b)
                if qa != qb { return qa < qb }
                let ha = a.urlResolved.lowercased().hasPrefix("https") ? 1 : 0, hb = b.urlResolved.lowercased().hasPrefix("https") ? 1 : 0
                if ha != hb { return ha < hb }
                return a.clickCount < b.clickCount
            }!
            best.clickCount = list.reduce(0) { $0 + $1.clickCount }
            return best
        }
    }

    /// Stations matching more query words (name, region, tags, country) first, then popularity.
    static func rank(_ query: String, _ stations: [RadioStation]) -> [RadioStation] {
        let ws = words(query)
        let norm = TextMatching.normalize(query)
        let tokens = norm.split(separator: " ").map(String.init)
        // A two-letter word that is a country code ("danceradio cz") points to that country.
        let countries = Set(tokens.filter { $0.count == 2 }.map { $0.uppercased() })
        let nameQuery = tokens.filter { $0.count != 2 || !countries.contains($0.uppercased()) }.joined(separator: " ")
        func exact(_ s: RadioStation) -> Bool { TextMatching.normalize(s.name) == nameQuery }
        func inCountry(_ s: RadioStation) -> Bool { countries.contains(s.countryCode.uppercased()) }
        func hits(_ s: RadioStation) -> Int {
            let hay = " " + TextMatching.normalize([s.name, s.state, s.tags, s.country].joined(separator: " ")) + " "
            return ws.filter { hay.contains($0) }.count
        }
        let indexed = Array(stations.enumerated())
        if ws.isEmpty {
            return indexed.sorted { a, b in
                let ea = exact(a.element), eb = exact(b.element)
                if ea != eb { return ea }
                let ca = inCountry(a.element), cb = inCountry(b.element)
                if ca != cb { return ca }
                return a.offset < b.offset
            }.map(\.element)
        }
        return indexed.sorted { a, b in
            let ha = hits(a.element) + (inCountry(a.element) ? 1 : 0), hb = hits(b.element) + (inCountry(b.element) ? 1 : 0)
            if ha != hb { return ha > hb }
            let ea = exact(a.element), eb = exact(b.element)
            if ea != eb { return ea }
            if a.element.clickCount != b.element.clickCount { return a.element.clickCount > b.element.clickCount }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// A named station with an HTTPS stream (or HTTP when the user allows unencrypted radio).
    static func playable(_ s: RadioStation, allowHTTP: Bool) -> Bool {
        !s.name.trimmingCharacters(in: .whitespaces).isEmpty && [s.urlResolved, s.url].contains { u in
            let l = u.lowercased()
            return l.hasPrefix("https://") || (allowHTTP && l.hasPrefix("http://"))
        }
    }

    /// Real directory data only: name, region/country, genre tags, codec/bitrate as listed.
    static func track(_ s: RadioStation) -> Track {
        let tags = s.tags.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.prefix(3)
        let place = [s.state.trimmingCharacters(in: .whitespaces), s.country].filter { !$0.isEmpty }.joined(separator: ", ")
        let line = [place, tags.joined(separator: ", "), quality(s) ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
        return Track(
            id: Track.makeId(.radio, s.uuid),
            title: s.name.trimmingCharacters(in: .whitespacesAndNewlines),
            artist: line.isEmpty ? "Radio" : line,
            album: nil,
            artworkURL: s.favicon.lowercased().hasPrefix("https://") ? URL(string: s.favicon) : nil,
            duration: nil,
            isLive: true
        )
    }
}
