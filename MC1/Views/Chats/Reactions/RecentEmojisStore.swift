import Foundation

/// Stores recently used reaction emojis for quick access
@Observable
@MainActor
public final class RecentEmojisStore {
    private static let key = "recentReactionEmojis"
    private static let maxRecent = 6

    /// Default emojis shown before any usage
    public static let defaultEmojis = ["👍", "👎", "❤️", "😂", "😮", "😢"]

    /// Recently used emojis (most recent first), falls back to defaults
    public private(set) var recentEmojis: [String]

    public init() {
        if let stored = UserDefaults.standard.stringArray(forKey: Self.key), !stored.isEmpty {
            self.recentEmojis = stored
        } else {
            self.recentEmojis = Self.defaultEmojis
        }
    }

    /// Records emoji usage, moving it to front of recent list
    public func recordUsage(_ emoji: String) {
        var recent = recentEmojis
        recent.removeAll { $0 == emoji }
        recent.insert(emoji, at: 0)
        recent = Array(recent.prefix(Self.maxRecent))
        recentEmojis = recent
        UserDefaults.standard.set(recent, forKey: Self.key)
    }
}
