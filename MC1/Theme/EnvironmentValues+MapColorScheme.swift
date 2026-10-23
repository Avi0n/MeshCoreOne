import MC1Services
import SwiftUI

extension EnvironmentValues {
  /// Unset means System, so a view outside the scene injection follows chrome.
  @Entry var mapColorSchemePreference: AppColorSchemePreference = .system
}

extension View {
  /// The only view-tree read of `AppStorageKey.mapColorSchemePreference`.
  func propagatingMapColorSchemePreference() -> some View {
    modifier(MapColorSchemePreferenceModifier())
  }
}

private struct MapColorSchemePreferenceModifier: ViewModifier {
  @AppStorage(AppStorageKey.mapColorSchemePreference.rawValue)
  private var raw = AppStorageKey.defaultMapColorSchemePreference

  func body(content: Content) -> some View {
    content.environment(
      \.mapColorSchemePreference,
      mapColorSchemePreference(from: raw)
    )
  }
}
