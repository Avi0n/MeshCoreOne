import MC1Services
import SwiftUI

/// A `Hashable` string capturing the appearance inputs that change a chat row's resolved
/// rendering: light/dark, increased-contrast, and the Dynamic Type size. The chat table
/// reconfigures its hosted cells when this token changes, the same way it does for a theme-id
/// change, so sender names and avatars repaint on an appearance switch and message bubbles
/// re-measure and reflow on a Dynamic Type change even though the bubble cells diff as equal.
enum AppearanceToken {
  static func make(
    colorScheme: ColorScheme,
    contrast: ColorSchemeContrast,
    dynamicTypeSize: DynamicTypeSize
  ) -> String {
    let appearance = colorScheme == .dark ? "dark" : "light"
    let contrastTag = contrast == .increased ? "hc" : "std"
    return "\(appearance)-\(contrastTag)-\(contentSizeCategoryToken(dynamicTypeSize))"
  }

  /// Stable, `Hashable` string for a Dynamic Type size, mirroring `EnvInputs.contentSizeCategory`
  /// and the bubble text view's size-cache key. `DynamicTypeSize` is not `RawRepresentable`, so
  /// the mapping is explicit; `.large` resolves to the shared unscaled baseline so this token and
  /// `EnvInputs.default.contentSizeCategory` agree.
  static func contentSizeCategoryToken(_ size: DynamicTypeSize) -> String {
    switch size {
    case .xSmall: "xSmall"
    case .small: "small"
    case .medium: "medium"
    case .large: EnvInputs.defaultContentSizeCategory
    case .xLarge: "xLarge"
    case .xxLarge: "xxLarge"
    case .xxxLarge: "xxxLarge"
    case .accessibility1: "accessibility1"
    case .accessibility2: "accessibility2"
    case .accessibility3: "accessibility3"
    case .accessibility4: "accessibility4"
    case .accessibility5: "accessibility5"
    @unknown default: "unknown"
    }
  }
}
