import XCTest
@testable import MusicFlow

final class YouTubeMusicTests: XCTestCase {
    func testSongsShelf() throws {
        let tracks = YTMParser.tracks(try Fixture.json("ytm_search_songs.json"))
        XCTAssertEqual(tracks.map(\.id), ["yt:song00001A", "yt:song00002B"], "duplicates are dropped")
        let first = tracks[0]
        XCTAssertEqual(first.title, "Małomiasteczkowy")
        XCTAssertEqual(first.artist, "Dawid Podsiadło")
        XCTAssertEqual(first.album, "Małomiasteczkowy")
        XCTAssertEqual(first.duration, 225)
        XCTAssertEqual(first.artworkURL?.absoluteString, "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj")
        let second = tracks[1]
        XCTAssertEqual(second.artist, "Jan Kowalski", "unlinked artist: type label and duration skipped")
        XCTAssertEqual(second.duration, 3723)
        XCTAssertEqual(second.artworkURL?.absoluteString, "https://i.ytimg.com/vi/song00002B/hqdefault.jpg")
    }

    /// Recorded unfiltered response (item sections): videos are kept, artists and podcasts are not.
    func testRecordedUnfilteredResponse() throws {
        let tracks = YTMParser.tracks(try Fixture.json("ytm_search_all.json"))
        XCTAssertFalse(tracks.isEmpty)
        XCTAssertEqual(tracks.first?.id, "yt:oCZugu1ea18")
        XCTAssertTrue(tracks.first?.artist.contains("Dawid Podsiadło") ?? false)
        XCTAssertFalse(tracks.contains { $0.id == "yt:8dxgjqnIN8o" }, "podcast episodes are not songs")
        XCTAssertTrue(tracks.allSatisfy { $0.source == .youtube && !$0.title.isEmpty })
    }

    func testEmptyResponse() throws {
        XCTAssertTrue(YTMParser.tracks(try Fixture.json("ytm_search_empty.json")).isEmpty)
    }

    func testStreamPicksAacForAVPlayer() throws {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let root = try Fixture.json("ytp_player.json")
        let high = try YTMParser.stream(root, quality: .high, userAgent: "UA", now: now)
        XCTAssertTrue(high.url.absoluteString.contains("itag=140"))
        XCTAssertEqual(high.codec, "AAC")
        XCTAssertEqual(high.bitrateKbps, 129)
        XCTAssertEqual(high.duration, 291)
        XCTAssertEqual(high.userAgent, "UA")
        XCTAssertEqual(high.expiresAt, now.addingTimeInterval(21540))
        XCTAssertFalse(high.isLive)
        let saver = try YTMParser.stream(root, quality: .saver, userAgent: "UA", now: now)
        XCTAssertTrue(saver.url.absoluteString.contains("itag=139"))
    }

    func testStreamErrorsAreClassified() {
        let login: [String: Any] = ["playabilityStatus": ["status": "LOGIN_REQUIRED", "reason": "Sign in to confirm your age"]]
        XCTAssertThrowsError(try YTMParser.stream(login, quality: .high, userAgent: "UA")) { e in
            XCTAssertEqual(e as? ProviderError, .restricted("Sign in to confirm your age"))
        }
        let gone: [String: Any] = ["playabilityStatus": ["status": "ERROR", "reason": "Video unavailable"]]
        XCTAssertThrowsError(try YTMParser.stream(gone, quality: .high, userAgent: "UA")) { e in
            XCTAssertEqual(e as? ProviderError, .unavailable("Video unavailable"))
        }
        XCTAssertThrowsError(try YTMParser.stream([String: Any](), quality: .high, userAgent: "UA")) { e in
            XCTAssertEqual(e as? ProviderError, .parsing("playabilityStatus"))
        }
    }

    func testLiveVideoUsesHls() throws {
        let live: [String: Any] = [
            "playabilityStatus": ["status": "OK"],
            "videoDetails": ["isLive": true, "lengthSeconds": "0"],
            "streamingData": ["expiresInSeconds": "21540", "hlsManifestUrl": "https://manifest.googlevideo.com/x/index.m3u8",
                              "adaptiveFormats": [["itag": 140, "mimeType": "audio/mp4", "url": "https://x/140"]]],
        ]
        let s = try YTMParser.stream(live, quality: .high, userAgent: "UA")
        XCTAssertTrue(s.isLive)
        XCTAssertEqual(s.url.absoluteString, "https://manifest.googlevideo.com/x/index.m3u8")
        XCTAssertNil(s.duration)
    }
}
