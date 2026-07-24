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
