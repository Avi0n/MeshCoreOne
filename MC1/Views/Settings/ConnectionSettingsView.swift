import SwiftUI

/// Sub-page wrapping BluetoothSection or WiFiSection based on transport type
struct ConnectionSettingsView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @State private var showingWiFiEditSheet = false

    private var isWiFi: Bool {
        appState.connectionManager.currentTransportType == .wifi
    }

    var body: some View {
        List {
            if isWiFi {
                WiFiSection(showingEditSheet: $showingWiFiEditSheet)
            } else {
                BluetoothSection()
            }
        }
        .themedCanvas(theme)
        .navigationTitle(isWiFi ? L10n.Settings.Wifi.header : L10n.Settings.Bluetooth.header)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingWiFiEditSheet) {
            WiFiEditSheet()
        }
    }
}
