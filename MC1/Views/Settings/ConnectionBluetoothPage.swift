import SwiftUI

/// PIN rows for the phone's Bluetooth link.
struct ConnectionBluetoothPage: View {
  @Environment(\.appTheme) private var theme

  var body: some View {
    List {
      BluetoothSection()
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.Bluetooth.header)
    .navigationBarTitleDisplayMode(.inline)
  }
}
