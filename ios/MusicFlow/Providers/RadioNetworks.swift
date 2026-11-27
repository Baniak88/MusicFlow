import Foundation

/// Polish radio networks whose channels are listed on their own websites (port of the Android
/// `RadioNetworks`). Unofficial: no documented API, may break when a site changes.
enum RadioNetwork: String, CaseIterable, Identifiable, Sendable {
    case openFM = "openfm"
    case rmfOn = "rmf"
    case eska = "eska"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .openFM: return "Open FM"
        case .rmfOn: return "RMF ON"
        case .eska: return "ESKA"
        }
    }
}

struct NetworkStation: Hashable, Sendable {
    let network: RadioNetwork
    let id: String
    let name: String
    /// Nil when looked up at play time (RMF ON: per-station server list).
    let streamURL: URL?
    let logoURL: URL?
    var category: String?

    /// Track id in the radio namespace: `radio:net/<network>/<id>`.
    var track: Track {
        Track(
            id: Track.makeId(.radio, "\(RadioNetworks.prefix)\(network.rawValue)/\(id)"),
            title: name,
            artist: [network.displayName, category].compactMap { $0 }.joined(separator: " · "),
            album: nil,
            artworkURL: logoURL?.scheme == "https" ? logoURL : nil,
            duration: nil,
            isLive: true
        )
    }
}

final class RadioNetworks: @unchecked Sendable {
    static let prefix = "net/"
    static let ttl: TimeInterval = 6 * 3600
    private static let browserUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    private let http: HTTP
    private let lock = NSLock()
    private var cache: [RadioNetwork: (at: Date, list: [NetworkStation])] = [:]

    init(http: HTTP = .shared) { self.http = http }

    /// (network, station id) of a radio track key, or nil for Radio Browser stations.
    static func parse(key: String) -> (RadioNetwork, String)? {
        guard key.hasPrefix(prefix) else { return nil }
        let parts = key.dropFirst(prefix.count).split(separator: "/", maxSplits: 1).map(String.init)
        guard parts.count == 2, let n = RadioNetwork(rawValue: parts[0]), !parts[1].isEmpty else { return nil }
        return (n, parts[1])
    }

    private func fetch(_ url: String) async throws -> String {
        let (data, resp) = try await http.get(URL(string: url)!, headers: ["User-Agent": Self.browserUA])
        if resp.statusCode == 429 { throw ProviderError.rateLimited }
        guard (200..<300).contains(resp.statusCode) else { throw ProviderError.network("\(URL(string: url)?.host ?? url) HTTP \(resp.statusCode)") }
        return String(decoding: data, as: UTF8.self)
    }

    func stations(_ network: RadioNetwork) async throws -> [NetworkStation] {
        let cached = lock.withLock { cache[network] }
        if let c = cached, Date().timeIntervalSince(c.at) < Self.ttl { return c.list }
        do {
            let list: [NetworkStation]
            switch network {
            case .openFM: list = try NetworkParsers.openFM(try await fetch("https://open.fm/"))
            case .rmfOn: list = try NetworkParsers.rmfOn(try await fetch("https://www.rmfon.pl/json/stations.txt"))
            case .eska: list = NetworkParsers.eska(try await fetch("https://player.eska.pl/"))
            }
            guard !list.isEmpty else { throw ProviderError.parsing("\(network.displayName): no stations") }
            lock.withLock { cache[network] = (Date(), list) }
            return list
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if let c = cached { return c.list } // stale list is better than nothing
            throw error
        }
    }

    /// All networks; a network that fails is left out.
    func all() async -> [NetworkStation] {
        await withTaskGroup(of: (Int, [NetworkStation]).self) { group in
            for (i, n) in RadioNetwork.allCases.enumerated() {
                group.addTask { (i, (try? await self.stations(n)) ?? []) }
            }
            var parts: [(Int, [NetworkStation])] = []
            for await p in group { parts.append(p) }
            return parts.sorted { $0.0 < $1.0 }.flatMap(\.1)
        }
    }

    func search(_ query: String) async -> [NetworkStation] {
        NetworkParsers.match(query, await all())
    }

    func station(_ network: RadioNetwork, _ id: String) async throws -> NetworkStation {
        guard let s = try await stations(network).first(where: { $0.id == id }) else {
            throw ProviderError.unavailable(L.f("radio.network_gone", network.displayName))
        }
        return s
    }

    func resolve(key: String) async throws -> ResolvedStream {
        guard let (n, id) = Self.parse(key: key) else { throw ProviderError.unavailable("") }
        let s = try await station(n, id)
        let url: URL
        if let u = s.streamURL {
            url = u
        } else {
            let servers = NetworkParsers.rmfStreams(try await fetch("https://www.rmfon.pl/stacje/flash_aac_\(s.id).xml.txt"))
            guard let first = servers.first, let u = URL(string: first) else { throw ProviderError.unavailable(s.name) }
            url = u
        }
        // Codec/bitrate are not stated by the sites, so none is shown.
        return ResolvedStream(url: url, userAgent: AppInfo.userAgent, expiresAt: nil, codec: nil, bitrateKbps: nil, isLive: true, duration: nil)
    }
}

/// Pure parsing of the networks' pages (unit-tested with saved copies).
enum NetworkParsers {
    /// Open FM: station list embedded in the home page (`__NEXT_DATA__`); premium channels skipped.
    static func openFM(_ html: String) throws -> [NetworkStation] {
        guard let r = html.range(of: #"<script id="__NEXT_DATA__"[^>]*>"#, options: .regularExpression),
              let end = html.range(of: "</script>", range: r.upperBound..<html.endIndex),
              let root = try? JSONSerialization.jsonObject(with: Data(html[r.upperBound..<end.lowerBound].utf8)),
              let fallback = J.at(root, "props", "pageProps", "fallback") as? [String: Any] else {
            throw ProviderError.parsing("Open FM")
        }
        var categoryOf: [Int: String] = [:]
        var order: [Int] = []
        var seen = Set<Int>()
        for c in (fallback["categories"] as? [Any]) ?? [] {
            let name = (J.str(J.at(c, "name"))).map(cleanCategory)
            for id in ((J.at(c, "stations") as? [Any]) ?? []).compactMap(J.int) {
                if seen.insert(id).inserted { order.append(id) }
                if let name, !name.isEmpty, categoryOf[id] == nil { categoryOf[id] = name }
            }
        }
        var byId: [Int: NetworkStation] = [:]
        for case let s as [String: Any] in ((fallback["stations"] as? [String: Any]) ?? [:]).values {
            guard let id = J.int(s["id"]), (s["premium"] as? Bool) != true,
                  let name = J.str(s["name"])?.trimmingCharacters(in: .whitespaces), !name.isEmpty,
                  let urlString = J.str(s["streamUrl"]), urlString.lowercased().hasPrefix("https://"),
                  let url = URL(string: urlString) else { continue }
            byId[id] = NetworkStation(network: .openFM, id: String(id), name: name, streamURL: url,
                                      logoURL: J.str(s["logoUrl"]).flatMap(URL.init(string:)), category: categoryOf[id])
        }
        let rest = byId.keys.filter { !seen.contains($0) }.sorted()
        return (order.filter { byId[$0] != nil } + rest).compactMap { byId[$0] }
    }

    private static func cleanCategory(_ s: String) -> String {
        String(s.drop { !$0.isLetter && !$0.isNumber }).trimmingCharacters(in: .whitespaces)
    }

    private struct RmfStation: Decodable { var id: String; var name: String }

    /// RMF ON `json/stations.txt`; servers are looked up per station at play time.
    static func rmfOn(_ text: String) throws -> [NetworkStation] {
        guard let list = try? JSONDecoder().decode([RmfStation].self, from: Data(text.utf8)) else { throw ProviderError.parsing("RMF ON") }
        return list.filter { !$0.id.isEmpty && !$0.name.isEmpty }.map {
            NetworkStation(network: .rmfOn, id: $0.id, name: $0.name.trimmingCharacters(in: .whitespaces), streamURL: nil,
                           logoURL: URL(string: "https://www.rmfon.pl/img/z/s\($0.id).jpg"), category: nil)
        }
    }

    /// RMF ON server list: MP3 (128 kbps) first, then AAC; distinct, HTTPS only.
    static func rmfStreams(_ xml: String) -> [String] {
        let mp3 = matches(#"<item_mp3[^>]*>\s*([^<\s]+)\s*</item_mp3>"#, xml)
        let aac = matches(#"<item(?:\s[^>]*)?>\s*([^<\s]+)\s*</item>"#, xml)
        var seen = Set<String>()
        return (mp3 + aac).map(unescape).filter { $0.lowercased().hasPrefix("https://") && seen.insert($0).inserted }
    }

    /// ESKA: channel tiles of player.eska.pl.
    static func eska(_ html: String) -> [NetworkStation] {
        let re = try! NSRegularExpression(pattern: #"<a\s[^>]*data-stream-url="([^"]+)"[^>]*>(.*?)</a>"#, options: [.dotMatchesLineSeparators])
        let ns = html as NSString
        var seen = Set<String>()
        return re.matches(in: html, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            let url = unescape(ns.substring(with: m.range(at: 1)))
            let body = ns.substring(with: m.range(at: 2))
            guard let name = (first(#"<span>([^<]+)</span>"#, body) ?? first(#"alt="([^"]+)""#, body)).map({ unescape($0).trimmingCharacters(in: .whitespacesAndNewlines) }),
                  !name.isEmpty, url.lowercased().hasPrefix("https://"), let streamURL = URL(string: url),
                  let id = first(#"/(\d+-\d+)\.aac"#, url), seen.insert(id).inserted else { return nil }
            return NetworkStation(network: .eska, id: id, name: name, streamURL: streamURL,
                                  logoURL: first(#"<img[^>]*\ssrc="([^"]+)""#, body).map(unescape).flatMap(URL.init(string:)), category: nil)
        }
    }

    /// Stations whose name (or network name + name) contains all query words.
    static func match(_ query: String, _ stations: [NetworkStation]) -> [NetworkStation] {
        let q = TextMatching.normalize(query)
        guard !q.isEmpty else { return [] }
        let words = q.split(separator: " ").map(String.init).filter { $0 != "radio" && $0 != "on" }
        var scored: [(NetworkStation, Int, Int)] = []
        for (i, s) in stations.enumerated() {
            let hay = " " + TextMatching.normalize("\(s.network.displayName) \(s.network.rawValue) \(s.name) \(s.category ?? "")") + " "
            let name = TextMatching.normalize(s.name)
            let all = !words.isEmpty && words.allSatisfy { hay.contains(" " + $0) }
            if name == q { scored.append((s, 3, i)) } else if all && name.contains(q) { scored.append((s, 2, i)) } else if all { scored.append((s, 1, i)) }
        }
        return scored.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.2 < $1.2 }.map(\.0)
    }

    private static func matches(_ pattern: String, _ s: String) -> [String] {
        let re = try! NSRegularExpression(pattern: pattern)
        let ns = s as NSString
        return re.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range(at: 1)) }
    }

    private static func first(_ pattern: String, _ s: String) -> String? { matches(pattern, s).first }

    private static func unescape(_ s: String) -> String {
        s.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&#039;", with: "'")
            .replacingOccurrences(of: "&rsquo;", with: "’").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
    }
}
