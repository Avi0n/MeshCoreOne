import SwiftUI

/// Resolves whether map basemap tiles and pin sprites should use the dark style.
/// Controlled by Settings → Maps appearance; independent of app chrome.
func resolvedMapIsDark(
  preference: AppColorSchemePreference,
  colorScheme: ColorScheme
) -> Bool {
  switch preference {
  case .system: colorScheme == .dark
  case .light: false
  case .dark: true
  }
}

/// Missing, empty, or unknown stored values are System.
func mapColorSchemePreference(from raw: String?) -> AppColorSchemePreference {
  guard let raw, let preference = AppColorSchemePreference(rawValue: raw) else {
    return .system
  }
  return preference
}
