import Foundation

/// The catalog of built-in themes. Plain enum with `Sendable` static values — no `@MainActor`,
/// since the constants are immutable and require no actor hop to read.
public enum ThemeRegistry {
    public static let allThemes: [Theme] = [
        .default, .ember, .fern, .marine, .olive, .lavender, .sakura,
        .solarized, .nord, .catppuccin
    ]

    public static func theme(forID id: String) -> Theme? {
        allThemes.first { $0.id == id }
    }
}
