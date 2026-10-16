import SwiftUI

/// Global app appearance preference, independent of which theme is selected.
/// Raw values are pinned (persisted + backed up); a rename must not change the on-disk format.
enum AppColorSchemePreference: String, CaseIterable, Identifiable {
  case system
  case light
  case dark

  var id: String {
    rawValue
  }

  /// `nil` means "defer to the system" — the value passed to `.preferredColorScheme(_:)`.
  var colorScheme: ColorScheme? {
    switch self {
    case .system: nil
    case .light: .light
    case .dark: .dark
    }
  }
}
