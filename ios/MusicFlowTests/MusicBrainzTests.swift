import XCTest
@testable import MusicFlow

final class MusicBrainzTests: XCTestCase {
    private func recordings() throws -> [MbRecording] {
        try JSONDecoder().decode(MbRecordingSearch.self, from: Fixture.data("mb_recordings.json")).recordings
    }

    func testLuceneTermsRemoveSyntax() {
        XCTAssertEqual(MbMapper.luceneTerms("AC/DC: \"Back\" (in) black!"), "AC DC Back in black")
        XCTAssertEqual(MbMapper.luceneTerms("  ***  "), "*")
    }

    func testRankingPrefersNamedArtistStudioVersionAndDropsVideos() throws {
        let ranked = MbMapper.rankRecordings("karma police radiohead", try recordings())
        XCTAssertEqual(ranked.map(\.id), ["rec-orig", "rec-cover"])
    }

    func testTrackMapping() throws {
        let orig = try recordings()[1]
        let t = MbMapper.track(orig)
        XCTAssertEqual(t.id, "mbr:rec-orig")
        XCTAssertEqual(t.artist, "Radiohead")
        XCTAssertEqual(t.album, "OK Computer", "official release first, then the earliest")
        XCTAssertEqual(t.duration ?? 0, 264.066, accuracy: 0.001)
        XCTAssertEqual(t.artworkURL?.absoluteString, "https://coverartarchive.org/release-group/rg-okc/front-250")
        XCTAssertEqual(t.shareURL?.absoluteString, "https://musicbrainz.org/recording/rec-orig")
    }

    func testRateGateSpacesRequests() async throws {
        let gate = RateGate(interval: 0.2)
        let start = Date()
        try await gate.wait()
        try await gate.wait()
        try await gate.wait()
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(start), 0.39)
    }
}
