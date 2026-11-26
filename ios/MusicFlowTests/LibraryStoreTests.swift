import XCTest
@testable import MusicFlow

@MainActor
final class LibraryStoreTests: XCTestCase {
    private var url: URL!

    override func setUp() async throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("lib-\(UUID().uuidString).json")
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: url)
    }

    private let song = Track(id: "yt:abc", title: "Song", artist: "Artist", duration: 200)

    func testLikesPlaylistsAndHistoryPersist() {
        let store = LibraryStore(fileURL: url)
        XCTAssertTrue(store.toggleLike(song))
        let p = store.createPlaylist("  Road trip ")
        XCTAssertEqual(p.name, "Road trip")
        XCTAssertTrue(store.add(song, to: p.id))
        XCTAssertFalse(store.add(song, to: p.id), "no duplicates")
        store.recordPlay(song)
        store.recordPlay(Track(id: "radio:x", title: "Station", artist: "", isLive: true))
        store.recordPlay(song)

        let reopened = LibraryStore(fileURL: url)
        XCTAssertTrue(reopened.isLiked(song))
        XCTAssertEqual(reopened.playlists.first?.tracks, [song])
        XCTAssertEqual(reopened.history.map(\.id), ["yt:abc", "radio:x"], "most recent first, one entry per track")
    }

    func testRenameRemoveDelete() {
        let store = LibraryStore(fileURL: url)
        let p = store.createPlaylist("")
        XCTAssertFalse(p.name.isEmpty, "an empty name gets a default")
        store.add(song, to: p.id)
        store.rename(p.id, to: "Chill")
        XCTAssertEqual(store.playlist(p.id)?.name, "Chill")
        store.remove(at: IndexSet(integer: 0), from: p.id)
        XCTAssertEqual(store.playlist(p.id)?.tracks.count, 0)
        store.deletePlaylist(p.id)
        XCTAssertNil(store.playlist(p.id))
        XCTAssertFalse(store.toggleLike(song) == false)
        XCTAssertFalse(store.toggleLike(song))
    }

    func testHistoryIsCapped() {
        let store = LibraryStore(fileURL: url)
        for i in 0..<(LibraryStore.historyLimit + 20) { store.recordPlay(Track(id: "yt:\(i)", title: "\(i)", artist: "")) }
        XCTAssertEqual(store.history.count, LibraryStore.historyLimit)
        store.clearHistory()
        XCTAssertTrue(store.history.isEmpty)
    }
}
