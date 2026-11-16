import MC1Services
import SwiftUI

struct PathFallbackSettingsSection: View {
  @Environment(\.appTheme) private var theme
  @AppStorage(AppStorageKey.remoteFloodFallback.rawValue)
  private var remoteFloodFallback = AppStorageKey.defaultRemoteFloodFallback

  var body: some View {
    Section {
      Toggle(L10n.Settings.PathFallback.fallback, isOn: $remoteFloodFallback)
    } header: {
      Text(L10n.Settings.PathFallback.header)
    } footer: {
      Text(
        remoteFloodFallback
          ? L10n.Settings.PathFallback.footerOn
          : L10n.Settings.PathFallback.footerOff
      )
    }
    .themedRowBackground(theme)
  }
}
