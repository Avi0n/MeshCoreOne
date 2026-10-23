import MC1Services
import SwiftUI

/// Contacts granted remote CLI on the paired radio.
struct RemoteCLISettingsSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  var body: some View {
    if appState.connectedDevice?.supportsRadioCLI == true {
      Section {
        NavigationLink(value: SettingsSubpage.remoteCLIContacts) {
          Text(L10n.Settings.RemoteCLI.manage)
        }
      } header: {
        Text(L10n.Settings.RemoteCLI.header)
      } footer: {
        Text(L10n.Settings.RemoteCLI.footer)
      }
      .themedRowBackground(theme)
    }
  }
}
