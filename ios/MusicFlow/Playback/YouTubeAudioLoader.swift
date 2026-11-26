import AVFoundation

/// Feeds YouTube progressive audio to AVPlayer the way current YouTube clients read it: POST
/// requests for bounded byte ranges (`&range=a-b&rn=n`), stitched together. Large unbounded
/// GET requests to these URLs are throttled or rejected. Port of the Android
/// `YouTubeChunkedDataSource`.
final class YouTubeAudioLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    static let scheme = "mfyt"
    static let chunk: Int64 = 512 * 1024

    let queue = DispatchQueue(label: "app.musicflow.youtube-audio")
    private let original: URL
    private let userAgent: String?
    private let knownLength: Int64?
    private let session: URLSession
    private let lock = NSLock()
    private var requestNumber = 0
    /// Accessed on `queue` only.
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(url: URL, userAgent: String?, contentLength: Int64?, session: URLSession = HTTP.shared.session) {
        self.original = url
        self.userAgent = userAgent
        self.knownLength = contentLength ?? Self.clen(url)
        self.session = session
    }

    static func clen(_ url: URL) -> Int64? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "clen" }?.value.flatMap { Int64($0) }
    }

    /// An asset whose bytes come from this loader (custom URL scheme).
    func makeAsset() -> AVURLAsset {
        var c = URLComponents(url: original, resolvingAgainstBaseURL: false)!
        c.scheme = Self.scheme
        let asset = AVURLAsset(url: c.url!)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    func cancelAll() {
        queue.async {
            self.tasks.values.forEach { $0.cancel() }
            self.tasks.removeAll()
        }
    }

    // MARK: AVAssetResourceLoaderDelegate (called on `queue`)

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        let id = ObjectIdentifier(loadingRequest)
        tasks[id] = Task { [weak self] in
            await self?.serve(loadingRequest)
            self?.queue.async { self?.tasks[id] = nil }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        tasks.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    // MARK: Serving

    private func serve(_ request: AVAssetResourceLoadingRequest) async {
        do {
            let total = try await length()
            if let info = request.contentInformationRequest {
                info.contentType = AVFileType.m4a.rawValue
                info.contentLength = total
                info.isByteRangeAccessSupported = true
            }
            guard let data = request.dataRequest else {
                finish(request, nil)
                return
            }
            var offset = data.requestedOffset
            let end = data.requestsAllDataToEndOfResource ? total : min(total, data.requestedOffset + Int64(data.requestedLength))
            while offset < end {
                try Task.checkCancellation()
                let last = min(offset + Self.chunk, end) - 1
                let bytes = try await fetch(from: offset, to: last)
                guard !bytes.isEmpty else { throw ProviderError.network("YouTube audio: empty response") }
                try Task.checkCancellation()
                queue.sync {
                    if !request.isCancelled && !request.isFinished { data.respond(with: bytes) }
                }
                offset += Int64(bytes.count)
            }
            finish(request, nil)
        } catch is CancellationError {
            return
        } catch {
            finish(request, error)
        }
    }

    private func finish(_ request: AVAssetResourceLoadingRequest, _ error: Error?) {
        queue.async {
            guard !request.isCancelled, !request.isFinished else { return }
            if let error { request.finishLoading(with: error) } else { request.finishLoading() }
        }
    }

    private func length() async throws -> Int64 {
        if let n = knownLength { return n }
        // Unknown length: the first byte tells the total in Content-Range ("bytes 0-0/12345").
        let (_, resp) = try await post(from: 0, to: 0)
        if let range = resp.value(forHTTPHeaderField: "Content-Range"), let slash = range.lastIndex(of: "/"),
           let n = Int64(range[range.index(after: slash)...]) {
            return n
        }
        throw ProviderError.parsing("YouTube audio length")
    }

    /// One bounded range (inclusive), as YouTube's own clients request it.
    func fetch(from: Int64, to: Int64) async throws -> Data {
        try await post(from: from, to: to).0
    }

    private func post(from: Int64, to: Int64) async throws -> (Data, HTTPURLResponse) {
        let rn = lock.withLock { () -> Int in
            defer { requestNumber += 1 }
            return requestNumber
        }
        var c = URLComponents(url: original, resolvingAgainstBaseURL: false)!
        c.percentEncodedQuery = (c.percentEncodedQuery.map { $0 + "&" } ?? "") + "range=\(from)-\(to)&rn=\(rn)"
        var r = URLRequest(url: c.url!)
        r.httpMethod = "POST"
        r.httpBody = Data([0x78, 0x00])
        if let userAgent { r.setValue(userAgent, forHTTPHeaderField: "User-Agent") }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: r)
        } catch {
            throw ProviderError.classify(error)
        }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code), let http = response as? HTTPURLResponse else {
            throw ProviderError.network("YouTube audio HTTP \(code)")
        }
        return (data, http)
    }
}
