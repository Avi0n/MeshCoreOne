import AppIntents

extension AppState {
  /// Re-resolves the App Intents parameter queries when the addressable
  /// contact/channel set changes, so saved shortcuts and Siri offer the
  /// currently connected radio's contacts and channels rather than a stale set.
  func refreshAppShortcutParameters() {
    MC1AppShortcutsProvider.updateAppShortcutParameters()
  }
}
