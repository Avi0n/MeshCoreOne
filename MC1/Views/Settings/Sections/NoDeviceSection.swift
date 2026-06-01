import SwiftUI

/// Shown when no device is connected
struct NoDeviceSection: View {
    @Environment(\.appTheme) private var theme
    @Binding var showingDeviceSelection: Bool
    let isSidebar: Bool

    var body: some View {
        Section {
            Button {
                showingDeviceSelection = true
            } label: {
                TintedLabel(L10n.Settings.Device.connect, systemImage: "antenna.radiowaves.left.and.right")
            }
        } header: {
            Text(L10n.Settings.Device.header)
        } footer: {
            Text(L10n.Settings.Device.noDeviceConnected)
        }
        .themedRowBackground(theme, flatten: isSidebar)
    }
}
