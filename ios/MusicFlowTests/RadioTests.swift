import XCTest
@testable import MusicFlow

final class RadioTests: XCTestCase {
    private func stations() throws -> [RadioStation] {
        try JSONDecoder().decode([RadioStation].self, from: Fixture.data("radio_stations.json"))
    }

    func testDecodingIsLenient() throws {
        let s = try stations()
        XCTAssertEqual(s.count, 5)
        XCTAssertEqual(s[1].bitrate, 320, "numbers sent as strings are accepted")
        XCTAssertEqual(s[2].urlResolved, "")
        XCTAssertEqual(s[4].codec, "", "missing fields get defaults")
    }

    func testWordsAndUnglue() {
        XCTAssertEqual(RadioSearch.words("Radio Fama Kielce"), ["kielce", "fama"])
        XCTAssertEqual(RadioSearch.unglue("danceradio cz"), "dance radio cz")
        XCTAssertEqual(RadioSearch.unglue("openfm"), "open fm")
        XCTAssertEqual(RadioSearch.unglue("radiozet"), "radio zet")
        XCTAssertEqual(RadioSearch.unglue("rmf"), "rmf")
    }

    func testPlayableHonoursHttpSetting() throws {
        let s = try stations()
        XCTAssertTrue(RadioSearch.playable(s[0], allowHTTP: false))
        XCTAssertFalse(RadioSearch.playable(s[3], allowHTTP: false))
        XCTAssertTrue(RadioSearch.playable(s[3], allowHTTP: true))
        XCTAssertFalse(RadioSearch.playable(s[4], allowHTTP: true), "a station needs a name")
    }

    func testBestVersionsKeepsBestQualityAndSumsPopularity() throws {
        let best = RadioSearch.bestVersions(try stations())
        let fama = best.filter { TextMatching.normalize($0.name) == "radio fama" }
        XCTAssertEqual(fama.count, 1)
        XCTAssertEqual(fama[0].uuid, "u-fama-320")
        XCTAssertEqual(fama[0].clickCount, 50)
        XCTAssertEqual(best.first?.uuid, "u-fama-320", "first-appearance order is kept")
    }

    func testRankPutsAllWordMatchesFirst() throws {
        let ranked = RadioSearch.rank("radio fama kielce", RadioSearch.bestVersions(try stations()))
        XCTAssertEqual(ranked.first?.uuid, "u-fama-320")
        let dance = RadioSearch.rank(RadioSearch.unglue("danceradio cz"), try stations())
        XCTAssertEqual(dance.first?.uuid, "u-dance", "two-letter country code points to the country")
    }

    func testTrackShowsOnlyListedData() throws {
        let t = RadioSearch.track(try stations()[0])
        XCTAssertEqual(t.id, "radio:u-fama-128")
        XCTAssertEqual(t.title, "Radio FAMA")
        XCTAssertEqual(t.artist, "Kielce, Poland · pop, local · MP3 128 kbps")
        XCTAssertTrue(t.isLive)
        XCTAssertNil(t.duration)
        XCTAssertEqual(t.artworkURL?.absoluteString, "https://fama.pl/icon.png")
        XCTAssertNil(RadioSearch.track(try stations()[1]).artworkURL, "HTTP favicons are not loaded")
        XCTAssertEqual(RadioSearch.quality(try stations()[3]), nil)
        XCTAssertEqual(RadioSearch.qualityScore(try stations()[2]), 128, "AAC+ counts double")
    }
}
