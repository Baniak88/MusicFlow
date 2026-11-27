import XCTest
@testable import MusicFlow

/// Open FM / RMF ON / ESKA parsed from copies of their pages saved on 2026-11-27.
final class RadioNetworksTests: XCTestCase {
    private func text(_ name: String) throws -> String { String(decoding: try Fixture.data(name), as: UTF8.self) }

    func testOpenFMSkipsPremiumAndKeepsSiteOrder() throws {
        let list = try NetworkParsers.openFM(try text("openfm_home.html"))
        XCTAssertEqual(list.map(\.name), ["Tylko Polskie Przeboje", "Po Polsku", "Ballady Wszech Czasów", "Radio 357"])
        XCTAssertEqual(list[0].id, "3")
        XCTAssertEqual(list[0].streamURL?.absoluteString, "https://stream-cdn-1.open.fm/OFM3/ngrp:standard/playlist.m3u8")
        XCTAssertEqual(list[0].category, "Top 10")
        XCTAssertThrowsError(try NetworkParsers.openFM("<html>changed</html>"))
    }

    func testRmfOnListAndServers() throws {
        let list = try NetworkParsers.rmfOn(try text("rmfon_stations.json"))
        XCTAssertEqual(list.map(\.name), ["RMF FM", "RMF MAXX", "RMF Classic", "RADIO RMF24"])
        XCTAssertNil(list[0].streamURL)
        let urls = NetworkParsers.rmfStreams(try text("rmfon_streams_9.xml"))
        XCTAssertEqual(urls.first, "https://rs201-krk.rmfstream.pl/rmf_polskie_przeboje", "MP3 128 kbps first")
        XCTAssertTrue(urls.contains("https://rs201-krk.rmfstream.pl/PPRZEBOJE"), "AAC as fallback")
        XCTAssertEqual(urls.count, Set(urls).count)
    }

    func testEskaChannels() throws {
        let list = NetworkParsers.eska(try text("eska_player.html"))
        XCTAssertEqual(list.map(\.name), ["Radio ESKA", "ESKA TOY STORY Radio", "ESKA GORĄCA 20", "ESKA’00"])
        XCTAssertEqual(list[0].id, "2980-1")
        XCTAssertEqual(list[0].streamURL?.absoluteString, "https://liveradio.timesa.pl/2980-1.aac/playlist.m3u8")
    }

    func testMatchingAndTrackIds() throws {
        let all = try NetworkParsers.openFM(try text("openfm_home.html")) + NetworkParsers.rmfOn(try text("rmfon_stations.json"))
            + NetworkParsers.eska(try text("eska_player.html"))
        XCTAssertEqual(Set(NetworkParsers.match("rmf", all).map(\.network)), [.rmfOn])
        XCTAssertEqual(NetworkParsers.match("RMF FM", all).first?.name, "RMF FM")
        XCTAssertEqual(NetworkParsers.match("open fm", all).count, 4)
        XCTAssertEqual(NetworkParsers.match("eska gorąca", all).map(\.name), ["ESKA GORĄCA 20"])
        XCTAssertTrue(NetworkParsers.match("radio fama kielce", all).isEmpty)

        let t = all.first { $0.network == .eska }!.track
        XCTAssertEqual(t.id, "radio:net/eska/2980-1")
        XCTAssertTrue(t.isLive)
        XCTAssertEqual(t.artist, "ESKA")
        let parsed = RadioNetworks.parse(key: t.key)
        XCTAssertEqual(parsed?.0, .eska)
        XCTAssertEqual(parsed?.1, "2980-1")
        XCTAssertNil(RadioNetworks.parse(key: "96a5d4f2-0000"))
    }
}
