import SwiftUI

/// Section for managing the device's cryptographic identity
struct DeviceIdentitySection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Binding var showingImportKeySheet: Bool
    @Binding var showingRegenerateSheet: Bool

    var body: some View {
        Section {
            Button {
                showingImportKeySheet = true
            } label: {
                Label(L10n.Settings.ImportKey.title, systemImage: "square.and.arrow.down")
            }
            .radioDisabled(for: appState.connectionState)

            Button {
                showingRegenerateSheet = true
            } label: {
                Label(L10n.Settings.RegenerateIdentity.title, systemImage: "arrow.triangle.2.circlepath")
            }
            .radioDisabled(for: appState.connectionState)
        } header: {
            Text(L10n.Settings.RegenerateIdentity.header)
        }
        .themedRowBackground(theme)
    }
}
