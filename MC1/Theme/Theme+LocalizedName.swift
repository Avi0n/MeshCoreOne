import Foundation

extension Theme {
    /// The user-facing theme name, resolved through static `L10n` members for the known themes
    /// (compile-time-safe key references). A theme added to the registry without a case here is a
    /// bug: it traps in debug (caught by `ThemeLocalizedNameTests`) and, in release, falls back to
    /// a dynamic lookup of `displayNameKey` so the user never sees a raw dotted key string.
    var localizedName: String {
        switch id {
        case Theme.default.id:  return L10n.Settings.Support.Theme.default
        case Theme.ember.id:    return L10n.Settings.Support.Theme.ember
        case Theme.fern.id:     return L10n.Settings.Support.Theme.fern
        case Theme.marine.id:   return L10n.Settings.Support.Theme.marine
        case Theme.olive.id:    return L10n.Settings.Support.Theme.olive
        case Theme.lavender.id: return L10n.Settings.Support.Theme.lavender
        // Proper nouns, never translated: Sakura is romanized Japanese; Solarized, Nord, and
        // Catppuccin are the names of the upstream MIT-licensed palettes.
        case Theme.sakura.id:     return "Sakura"
        case Theme.solarized.id:  return "Solarized"
        case Theme.nord.id:       return "Nord"
        case Theme.catppuccin.id: return "Catppuccin"
        default:
            assertionFailure("Theme '\(id)' is missing a localizedName case")
            return dynamicallyResolvedName
        }
    }

    /// Fallback for a registry theme that has a localization key but no explicit `localizedName`
    /// case above. Resolves `displayNameKey` against the Settings strings table; `displayNameKey` is
    /// the SwiftGen accessor path (`Settings.Support.Theme.X`), and the on-disk key is that path
    /// minus the leading table component. Proper-noun themes carry no key — they return their fixed
    /// name from the switch and never reach here; a keyless theme that somehow does falls back to its
    /// raw id rather than crashing.
    private var dynamicallyResolvedName: String {
        guard let displayNameKey else { return id }
        let table = "Settings"
        let prefix = table + "."
        let key = displayNameKey.hasPrefix(prefix) ? String(displayNameKey.dropFirst(prefix.count)) : displayNameKey
        return Bundle.main.localizedString(forKey: key, value: displayNameKey, table: table)
    }
}
