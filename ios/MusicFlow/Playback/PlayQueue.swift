import Foundation
import SwiftUI // move(fromOffsets:toOffset:), remove(atOffsets:)

/// The play queue: pure value logic (unit-tested), no AVFoundation.
struct PlayQueue: Equatable {
    private(set) var items: [Track] = []
    private(set) var index: Int?
    private(set) var isShuffled = false
    /// Order before shuffling, kept in sync with additions/removals so it can be restored.
    private var original: [Track]?
    var repeatMode: RepeatMode = .off

    var current: Track? {
        guard let i = index, items.indices.contains(i) else { return nil }
        return items[i]
    }

    var upcoming: [Track] {
        guard let i = index else { return items }
        return i + 1 < items.count ? Array(items[(i + 1)...]) : []
    }

    var isEmpty: Bool { items.isEmpty }

    mutating func set(_ tracks: [Track], start: Int, shuffle: Bool = false) {
        items = tracks
        index = tracks.isEmpty ? nil : min(max(start, 0), tracks.count - 1)
        original = nil
        isShuffled = false
        if shuffle { setShuffle(true) }
    }

    /// Shuffle keeps the current track playing and shuffles everything else after it.
    mutating func setShuffle<R: RandomNumberGenerator>(_ on: Bool, using rng: inout R) {
        guard on != isShuffled else { return }
        if on {
            original = items
            if let i = index {
                var others = items
                let cur = others.remove(at: i)
                others.shuffle(using: &rng)
                items = [cur] + others
                index = 0
            } else {
                items.shuffle(using: &rng)
            }
            isShuffled = true
        } else {
            let cur = current
            if let o = original { items = o }
            original = nil
            isShuffled = false
            if let cur { index = items.firstIndex(of: cur) ?? index.map { min($0, items.count - 1) } }
        }
    }

    mutating func setShuffle(_ on: Bool) {
        var g = SystemRandomNumberGenerator()
        setShuffle(on, using: &g)
    }

    /// Moves to the next track. With `auto` (the track ended) "repeat one" stays on the same track.
    /// Returns false at the end of the queue.
    @discardableResult
    mutating func next(auto: Bool) -> Bool {
        guard let i = index else { return false }
        if auto && repeatMode == .one { return true }
        if i + 1 < items.count { index = i + 1; return true }
        if repeatMode != .off && !items.isEmpty { index = 0; return true }
        return false
    }

    @discardableResult
    mutating func previous() -> Bool {
        guard let i = index else { return false }
        if i > 0 { index = i - 1; return true }
        if repeatMode != .off && !items.isEmpty { index = items.count - 1; return true }
        return false
    }

    mutating func jump(to i: Int) {
        guard items.indices.contains(i) else { return }
        index = i
    }

    /// Inserts right after the current track.
    mutating func playNext(_ t: Track) {
        guard let i = index else { items = [t]; index = 0; original = original.map { $0 + [t] }; return }
        items.insert(t, at: i + 1)
        if var o = original, let cur = current, let j = o.firstIndex(of: cur) {
            o.insert(t, at: j + 1)
            original = o
        }
    }

    mutating func append(_ t: Track) {
        items.append(t)
        original?.append(t)
        if index == nil { index = 0 }
    }

    /// Returns true when the removed entry was the current track (the caller starts the new current).
    @discardableResult
    mutating func remove(at i: Int) -> Bool {
        guard items.indices.contains(i) else { return false }
        let removed = items.remove(at: i)
        if var o = original, let j = o.firstIndex(of: removed) { o.remove(at: j); original = o }
        guard let cur = index else { return false }
        if items.isEmpty { index = nil; return true }
        if i < cur { index = cur - 1; return false }
        if i == cur { index = min(cur, items.count - 1); return true }
        return false
    }

    /// Reorders entries; the current track keeps playing.
    mutating func move(from source: IndexSet, to destination: Int) {
        let cur = index.flatMap { items.indices.contains($0) ? $0 : nil }
        var tagged = items.enumerated().map { ($0.offset, $0.element) }
        tagged.move(fromOffsets: source, toOffset: destination)
        items = tagged.map(\.1)
        if let cur { index = tagged.firstIndex { $0.0 == cur } }
    }

    mutating func clear() {
        items = []
        index = nil
        original = nil
        isShuffled = false
    }
}
