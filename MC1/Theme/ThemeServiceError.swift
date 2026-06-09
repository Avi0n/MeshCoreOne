import Foundation

/// Errors surfaced by `ThemeService`. Localized via `L10n` (this type lives in the MC1 target,
/// where the generated `L10n` enum is visible — unlike the MC1Services error types).
public enum ThemeServiceError: LocalizedError, Sendable {
    case notOwned(productID: String)

    public var errorDescription: String? {
        L10n.Settings.Support.Error.themeNotOwned
    }
}
