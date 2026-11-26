import XCTest
@testable import MusicFlow

final class TextMatchingTests: XCTestCase {
    func testNormalizeStripsDecorationsCaseAndDiacritics() {
        XCTAssertEqual(TextMatching.normalize("Café Déjà Vu (Official Video)"), "cafe deja vu")
        XCTAssertEqual(TextMatching.normalize("Song Title [Lyrics] feat. Someone"), "song title")
    }

    func testLevenshtein() {
        XCTAssertEqual(TextMatching.levenshtein("kitten", "sitting"), 3)
        XCTAssertEqual(TextMatching.levenshtein("", "abc"), 3)
    }

    func testReliableMatchRequiresTitleArtistAndDuration() {
        XCTAssertTrue(TextMatching.isReliableMatch(titleA: "Strobe", artistA: "deadmau5", durationA: 600, titleB: "Strobe (Official Audio)", artistB: "deadmau5", durationB: 602))
        XCTAssertFalse(TextMatching.isReliableMatch(titleA: "Strobe", artistA: "deadmau5", durationA: 600, titleB: "Strobe", artistB: "deadmau5", durationB: 200))
        XCTAssertFalse(TextMatching.isReliableMatch(titleA: "Strobe", artistA: "deadmau5", durationA: nil, titleB: "Ghosts n Stuff", artistB: "deadmau5", durationB: nil))
        XCTAssertFalse(TextMatching.isReliableMatch(titleA: "Strobe", artistA: "deadmau5", durationA: nil, titleB: "Strobe", artistB: "Tiesto", durationB: nil))
    }

    func testAudioMatchAcceptsArtistDashTitleAndRejectsCovers() {
        let mb = Track(id: "mbr:1", title: "Karma Police", artist: "Radiohead", duration: 264)
        let cover = Track(id: "yt:c", title: "Karma Police", artist: "Cover Band", duration: 264)
        let uploaded = Track(id: "yt:u", title: "Radiohead - Karma Police", artist: "Radiohead", duration: 266)
        XCTAssertEqual(AudioMatch.best(for: mb, in: [cover, uploaded])?.id, "yt:u")
        let wrongLength = Track(id: "yt:w", title: "Karma Police", artist: "Radiohead", duration: 300)
        XCTAssertNil(AudioMatch.best(for: mb, in: [cover, wrongLength]))
    }

    func testFormat() {
        XCTAssertEqual(Format.time(185), "3:05")
        XCTAssertEqual(Format.time(3723), "1:02:03")
        XCTAssertEqual(Format.time(nil), "–:––")
        XCTAssertEqual(Format.parseDuration("3:45"), 225)
        XCTAssertEqual(Format.parseDuration("1:02:03"), 3723)
        XCTAssertNil(Format.parseDuration("21 mln"))
        XCTAssertNil(Format.parseDuration("Utwór"))
    }
}
