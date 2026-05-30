import SwiftUI

/// A `Hashable` string capturing the appearance inputs that change a theme's resolved identity
/// colors: light/dark and increased-contrast. The chat table reconfigures its hosted cells when
/// this token changes, the same way it does for a theme-id change, so sender names and avatars
/// repaint on an appearance switch even though the bubble cells diff as equal.
enum AppearanceToken {
    static func make(colorScheme: ColorScheme, contrast: ColorSchemeContrast) -> String {
        let appearance = colorScheme == .dark ? "dark" : "light"
        let contrastTag = contrast == .increased ? "hc" : "std"
        return "\(appearance)-\(contrastTag)"
    }
}
