import XCTest
@testable import MusicFlow

/// Real network checks against the services. Skipped unless LIVE_TESTS=1
/// (CI: `TEST_RUNNER_LIVE_TESTS=1 xcodebuild test …`).
final class LiveTests: XCTestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["LIVE_TESTS"] == "1", "live tests disabled")
    }

    private func firstBytes(_ s: ResolvedStream) async throws -> Int {
        if s.chunked {
            let loader = YouTubeAudioLoader(url: s.url, userAgent: s.userAgent, contentLength: s.contentLength)
            let data = try await loader.fetch(from: 0, to: 65_535)
            let next = try await loader.fetch(from: 65_536, to: 65_536 + 524_287)
            print("LIVE stream \(s.url.host ?? "?") chunked POST: \(data.count) + \(next.count) bytes, \(s.qualityLabel ?? "-"), length \(s.contentLength ?? -1)")
            // Informational: does a plain GET also work for these URLs?
            var g = URLRequest(url: s.url)
            g.setValue("bytes=0-65535", forHTTPHeaderField: "Range")
            if let ua = s.userAgent { g.setValue(ua, forHTTPHeaderField: "User-Agent") }
            let (gd, gr) = try await URLSession.shared.data(for: g)
            print("LIVE plain GET: HTTP \((gr as? HTTPURLResponse)?.statusCode ?? 0), \(gd.count) bytes")
            XCTAssertEqual(data.count, 65_536)
            return data.count
        }
        var r = URLRequest(url: s.url)
        r.setValue("bytes=0-65535", forHTTPHeaderField: "Range")
        if let ua = s.userAgent { r.setValue(ua, forHTTPHeaderField: "User-Agent") }
        let (data, resp) = try await URLSession.shared.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        print("LIVE stream \(s.url.host ?? "?") HTTP \(code), \(data.count) bytes, \(s.qualityLabel ?? "-")")
        XCTAssertTrue([200, 206].contains(code), "stream HTTP \(code)")
        return data.count
    }

    func testYouTubeMusicSearchAndStream() async throws {
        let yt = YouTubeMusic()
        let tracks = try await yt.search("dawid podsiadło małomiasteczkowy")
        print("LIVE yt search:", tracks.prefix(5).map { "\($0.title) — \($0.artist) [\(Format.time($0.duration))]" })
        XCTAssertFalse(tracks.isEmpty)
        let stream = try await yt.resolve(videoId: tracks[0].key)
        let bytes = try await firstBytes(stream)
        XCTAssertGreaterThan(bytes, 1000)
    }

    func testRadioSearchFindsRegionalStation() async throws {
        let radio = RadioBrowser()
        let found = try await radio.search("radio fama kielce")
        print("LIVE radio:", found.prefix(5).map { "\($0.title) — \($0.artist)" })
        XCTAssertTrue(found.prefix(5).contains { $0.title.lowercased().contains("fama") })
        let top = try await radio.top(country: "PL", limit: 5)
        XCTAssertFalse(top.isEmpty)
        let stream = try await radio.resolve(uuid: top[0].key)
        print("LIVE radio stream:", stream.url.absoluteString.prefix(60))
    }

    func testMusicBrainzSearchAndYouTubeMatch() async throws {
        let mb = MusicBrainz()
        let found = try await mb.search("karma police radiohead")
        print("LIVE mb:", found.prefix(3).map { "\($0.title) — \($0.artist) [\(Format.time($0.duration))]" })
        let first = try XCTUnwrap(found.first)
        XCTAssertEqual(first.artist, "Radiohead")
        let resolver = StreamResolver(youtube: YouTubeMusic(), radio: RadioBrowser(), musicbrainz: mb)
        let videoId = try await resolver.match(first)
        print("LIVE mb → yt:", videoId)
        let stream = try await resolver.resolve(first)
        let bytes = try await firstBytes(stream)
        XCTAssertGreaterThan(bytes, 1000)
    }
}
