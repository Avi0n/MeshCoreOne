import SwiftUI

/// Global app appearance preference, independent of which theme is selected.
/// Raw values are pinned (persisted + backed up); a rename must not change the on-disk format.
public enum AppColorSchemePreference: String, Sendable, CaseIterable, Identifiable {
    // swiftlint:disable redundant_string_enum_value
    case system = "system"
    case light = "light"
    case dark = "dark"
    // swiftlint:enable redundant_string_enum_value

    public var id: String { rawValue }

    /// `nil` means "defer to the system" — the value passed to `.preferredColorScheme(_:)`.
    public var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}
