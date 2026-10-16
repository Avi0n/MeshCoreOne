import MC1Services
import SwiftUI

/// WiFi connection settings - shown when connected via WiFi instead of Bluetooth.
struct WiFiSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Binding var showingEditSheet: Bool

  private var currentConnection: ConnectionMethod? {
    appState.connectedDevice?.connectionMethods.first { $0.isWiFi }
  }

  var body: some View {
    Section {
      if case let .wifi(host, port, _) = currentConnection {
        LabeledContent(L10n.Settings.Wifi.address, value: host)
        LabeledContent(L10n.Settings.Wifi.port, value: "\(port)")
      }

      Button(L10n.Settings.Wifi.editConnection) {
        showingEditSheet = true
      }
    } header: {
      Text(L10n.Settings.Wifi.header)
    } footer: {
      Text(L10n.Settings.Wifi.footer)
    }
    .themedRowBackground(theme)
  }
}

#Preview {
  @Previewable @State var showingEditSheet = false
  List {
    WiFiSection(showingEditSheet: $showingEditSheet)
  }
  .environment(\.appState, AppState())
}
