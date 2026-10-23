import SwiftUI

/// The phone's Wi-Fi session, then the radio's Wi-Fi network when the radio reports one.
struct ConnectionWiFiPage: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Bindable var radioWiFi: RadioWiFiViewModel
  @State private var showingWiFiEditSheet = false

  var body: some View {
    List {
      if appState.connectionManager.currentTransportType == .wifi {
        WiFiSection(showingEditSheet: $showingWiFiEditSheet)
      }
      if radioWiFi.showsSection {
        RadioWiFiSection(viewModel: radioWiFi)
      }
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.Connection.Link.wifi)
    .navigationBarTitleDisplayMode(.inline)
    .refreshable {
      await radioWiFi.refresh()
    }
    .errorAlert(pageRecoveryMessage, retryAction: { Task { await radioWiFi.refresh() } })
    .sheet(isPresented: $showingWiFiEditSheet) {
      WiFiEditSheet()
    }
    .sheet(isPresented: $radioWiFi.editorOpen) {
      RadioWiFiEditSheet(viewModel: radioWiFi)
    }
  }

  private var pageRecoveryMessage: Binding<String?> {
    Binding(
      get: {
        guard radioWiFi.recoverySurface(wifiPageVisible: true) == .wifiPage else { return nil }
        return radioWiFi.errorMessage
      },
      set: { radioWiFi.errorMessage = $0 }
    )
  }
}
