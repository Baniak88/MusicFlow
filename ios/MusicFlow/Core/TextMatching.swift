import Foundation

/// Normalisation for cross-provider matching (port of the Android `TextMatching`).
/// Removes diacritics, case, punctuation and decorations such as "(Official Video)".
enum TextMatching {
    private static let decorations = try! NSRegularExpression(
        pattern: #"[(\[](?:official|lyrics?|lyric video|audio|video|visualizer|hd|hq|4k|remaster(?:ed)?(?: \d{4})?|explicit|clean)[^)\]]*[)\]]"#,
        options: [.caseInsensitive]
    )
    private static let featuring = try! NSRegularExpression(pattern: #"\s+(?:feat\.?|ft\.?|featuring)\s+.*$"#, options: [.caseInsensitive])

    static func normalize(_ input: String) -> String {
        var s = replace(decorations, in: input, with: " ")
        s = replace(featuring, in: s, with: "")
        s = s.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: Locale(identifier: "en_US_POSIX")).lowercased()
        s = s.replacingOccurrences(of: #"[^\p{L}\p{N}]+"#, with: " ", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespaces)
    }

    private static func replace(_ re: NSRegularExpression, in s: String, with t: String) -> String {
        re.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: t)
    }

    static func levenshtein(_ a: String, _ b: String) -> Int {
        let x = Array(a.unicodeScalars), y = Array(b.unicodeScalars)
        if x == y { return 0 }
        if x.isEmpty { return y.count }
        if y.isEmpty { return x.count }
        var prev = Array(0...y.count)
        var cur = [Int](repeating: 0, count: y.count + 1)
        for i in 1...x.count {
            cur[0] = i
            for j in 1...y.count {
                let cost = x[i - 1] == y[j - 1] ? 0 : 1
                cur[j] = min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            swap(&prev, &cur)
        }
        return prev[y.count]
    }

    /// Similarity in [0, 1] after normalisation.
    static func similarity(_ a: String, _ b: String) -> Double {
        let x = normalize(a), y = normalize(b)
        if x.isEmpty && y.isEmpty { return 1 }
        let longest = max(x.unicodeScalars.count, y.unicodeScalars.count)
        return 1 - Double(levenshtein(x, y)) / Double(longest)
    }

    /// Same recording: title, artist and duration agree. Used before playing a MusicBrainz track
    /// through YouTube Music — a guess is never played.
    static func isReliableMatch(
        titleA: String, artistA: String?, durationA: TimeInterval?,
        titleB: String, artistB: String?, durationB: TimeInterval?,
        tolerance: TimeInterval = 5
    ) -> Bool {
        if similarity(titleA, titleB) < 0.85 { return false }
        if let a0 = artistA, let b0 = artistB, similarity(a0, b0) < 0.7 {
            let a = normalize(a0), b = normalize(b0)
            if !a.contains(b) && !b.contains(a) { return false }
        }
        if let da = durationA, let db = durationB, abs(da - db) > tolerance { return false }
        return true
    }
}

enum AudioMatch {
    /// First candidate that is the same recording: title, artist and duration (±7 s) agree.
    static func best(for t: Track, in candidates: [Track]) -> Track? {
        candidates.first { c in
            var titles = [c.title]
            if let r = c.title.range(of: " - ") { titles.append(String(c.title[r.upperBound...])) }
            return titles.contains { title in
                TextMatching.isReliableMatch(
                    titleA: t.title, artistA: t.artist.isEmpty ? nil : t.artist, durationA: t.duration,
                    titleB: title, artistB: c.artist.isEmpty ? nil : c.artist, durationB: c.duration,
                    tolerance: 7
                )
            }
        }
    }
}
