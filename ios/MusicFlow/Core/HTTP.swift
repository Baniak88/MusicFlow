import Foundation

/// Every provider failure is reported as a ProviderError (same rule as on Android).
enum ProviderError: LocalizedError, Equatable {
    case offline
    case network(String)
    case rateLimited
    case unavailable(String)
    case restricted(String)
    case parsing(String)
    case notSupported(String)
    case noMatch(String)

    var errorDescription: String? {
        switch self {
        case .offline: return L.t("error.offline")
        case .network(let d): return L.f("error.network", d)
        case .rateLimited: return L.t("error.rate_limited")
        case .unavailable(let d): return d.isEmpty ? L.t("error.unavailable") : L.f("error.unavailable_detail", d)
        case .restricted(let d): return L.f("error.restricted", d)
        case .parsing(let d): return L.f("error.parsing", d)
        case .notSupported(let d): return d
        case .noMatch(let title): return L.f("error.no_match", title)
        }
    }

    /// Maps transport errors; cancellation is passed through as CancellationError.
    static func classify(_ error: Error) -> Error {
        if error is CancellationError || error is ProviderError { return error }
        if let u = error as? URLError {
            switch u.code {
            case .cancelled: return CancellationError()
            case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff:
                return ProviderError.offline
            case .timedOut: return ProviderError.network("timeout")
            default: return ProviderError.network("\(u.code.rawValue)")
            }
        }
        return ProviderError.network(String(describing: error))
    }
}

/// Thin URLSession wrapper. No tracking, no cookies kept between launches.
final class HTTP: @unchecked Sendable {
    static let shared = HTTP()

    let session: URLSession

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let c = URLSessionConfiguration.default
            c.timeoutIntervalForRequest = 15
            c.timeoutIntervalForResource = 40
            c.httpCookieStorage = nil
            c.httpShouldSetCookies = false
            c.urlCache = nil
            self.session = URLSession(configuration: c)
        }
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw ProviderError.network("no HTTP response") }
            return (data, http)
        } catch {
            throw ProviderError.classify(error)
        }
    }

    func get(_ url: URL, headers: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        var r = URLRequest(url: url)
        headers.forEach { r.setValue($1, forHTTPHeaderField: $0) }
        return try await send(r)
    }

    func postJSON(_ url: URL, body: [String: Any], headers: [String: String] = [:]) async throws -> (Data, HTTPURLResponse) {
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        headers.forEach { r.setValue($1, forHTTPHeaderField: $0) }
        return try await send(r)
    }
}

/// Helpers for walking untyped JSON (YouTube's InnerTube responses).
enum J {
    static func parse(_ data: Data) throws -> Any {
        do { return try JSONSerialization.jsonObject(with: data) } catch { throw ProviderError.parsing("JSON") }
    }

    /// `J.at(root, "a", 0, "b")` — String keys for objects, Int indices for arrays.
    static func at(_ root: Any?, _ path: Any...) -> Any? {
        var cur = root
        for p in path {
            if let k = p as? String {
                cur = (cur as? [String: Any])?[k]
            } else if let i = p as? Int {
                guard let a = cur as? [Any], a.indices.contains(i) else { return nil }
                cur = a[i]
            } else { return nil }
        }
        return cur
    }

    static func str(_ v: Any?) -> String? { v as? String }

    static func int(_ v: Any?) -> Int? {
        if let i = v as? Int { return i }
        if let s = v as? String { return Int(s) }
        if let d = v as? Double { return Int(d) }
        return nil
    }

    /// Depth-first visit of every value stored under `key`.
    static func each(_ key: String, in root: Any, _ body: ([String: Any]) -> Void) {
        if let d = root as? [String: Any] {
            for (k, v) in d {
                if k == key, let obj = v as? [String: Any] { body(obj) } else { each(key, in: v, body) }
            }
        } else if let a = root as? [Any] {
            for v in a { each(key, in: v, body) }
        }
    }

    /// Like `each`, but keeps document order (dictionaries are unordered; arrays keep the order).
    static func collect(_ key: String, in root: Any) -> [[String: Any]] {
        var out: [[String: Any]] = []
        func visit(_ v: Any) {
            if let d = v as? [String: Any] {
                if let obj = d[key] as? [String: Any] { out.append(obj); return }
                // Visit array-valued and object-valued children; arrays carry the order that matters.
                for k in d.keys.sorted() { visit(d[k]!) }
            } else if let a = v as? [Any] {
                a.forEach(visit)
            }
        }
        visit(root)
        return out
    }
}
