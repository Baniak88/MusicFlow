import XCTest
@testable import MusicFlow

final class PlayQueueTests: XCTestCase {
    private func t(_ n: Int) -> Track { Track(id: "yt:\(n)", title: "T\(n)", artist: "A") }
    private var five: [Track] { (1...5).map(t) }

    func testNextPreviousAndEnd() {
        var q = PlayQueue()
        q.set(five, start: 3)
        XCTAssertEqual(q.current?.id, "yt:4")
        XCTAssertTrue(q.next(auto: true))
        XCTAssertEqual(q.current?.id, "yt:5")
        XCTAssertFalse(q.next(auto: true), "end of the queue")
        XCTAssertTrue(q.previous())
        XCTAssertEqual(q.current?.id, "yt:4")
        XCTAssertEqual(q.upcoming.map(\.id), ["yt:5"])
    }

    func testRepeatModes() {
        var q = PlayQueue()
        q.set(five, start: 4)
        q.repeatMode = .all
        XCTAssertTrue(q.next(auto: true))
        XCTAssertEqual(q.current?.id, "yt:1")
        q.repeatMode = .one
        XCTAssertTrue(q.next(auto: true))
        XCTAssertEqual(q.current?.id, "yt:1", "repeat one stays on the track when it ends")
        XCTAssertTrue(q.next(auto: false))
        XCTAssertEqual(q.current?.id, "yt:2", "the next button still moves on")
    }

    func testPlayNextAndAppend() {
        var q = PlayQueue()
        q.set(five, start: 1)
        q.playNext(t(9))
        q.append(t(8))
        XCTAssertEqual(q.items.map(\.id), ["yt:1", "yt:2", "yt:9", "yt:3", "yt:4", "yt:5", "yt:8"])
        var empty = PlayQueue()
        empty.append(t(7))
        XCTAssertEqual(empty.current?.id, "yt:7")
    }

    func testShuffleKeepsCurrentFirstAndRestoresOrder() {
        var q = PlayQueue()
        q.set(five, start: 2)
        var rng = SeededGenerator(seed: 42)
        q.setShuffle(true, using: &rng)
        XCTAssertEqual(q.current?.id, "yt:3")
        XCTAssertEqual(q.index, 0)
        XCTAssertEqual(Set(q.items.map(\.id)), Set(five.map(\.id)))
        q.append(t(6))
        q.setShuffle(false, using: &rng)
        XCTAssertEqual(q.items.map(\.id), ["yt:1", "yt:2", "yt:3", "yt:4", "yt:5", "yt:6"])
        XCTAssertEqual(q.current?.id, "yt:3")
    }

    func testRemoveAndMoveKeepCurrent() {
        var q = PlayQueue()
        q.set(five, start: 2)
        XCTAssertFalse(q.remove(at: 0))
        XCTAssertEqual(q.current?.id, "yt:3")
        XCTAssertTrue(q.remove(at: q.index!), "removing the current track is reported")
        XCTAssertEqual(q.current?.id, "yt:4")
        q.move(from: IndexSet(integer: q.index!), to: 0)
        XCTAssertEqual(q.items.first?.id, "yt:4")
        XCTAssertEqual(q.current?.id, "yt:4")
        q.clear()
        XCTAssertNil(q.current)
    }
}

/// Deterministic generator for shuffle tests.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
