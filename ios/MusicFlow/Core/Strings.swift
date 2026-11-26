import Foundation

/// Localised strings (en.lproj / pl.lproj).
enum L {
    static func t(_ key: String) -> String { NSLocalizedString(key, comment: "") }
    static func f(_ key: String, _ args: CVarArg...) -> String { String(format: t(key), locale: Locale.current, arguments: args) }
}

enum Format {
    /// 3:05, 1:02:03
    static func time(_ seconds: TimeInterval?) -> String {
        guard let s = seconds, s.isFinite, s >= 0 else { return "–:––" }
        let total = Int(s.rounded(.down))
        let h = total / 3600, m = (total % 3600) / 60, sec = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }

    /// Parses "3:05" / "1:02:03" into seconds.
    static func parseDuration(_ text: String) -> TimeInterval? {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ":")
        guard (2...3).contains(parts.count), parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) else { return nil }
        return parts.reduce(0.0) { $0 * 60 + (Double($1) ?? 0) }
    }
}
