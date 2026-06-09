import SwiftUI
import MC1Services

/// Device maintenance actions (reboot)
struct DeviceActionsSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @State private var showingRebootAlert = false
    @State private var isRebooting = false
    @State private var errorMessage: String?

    var body: some View {
        Section {
            Button {
                showingRebootAlert = true
            } label: {
                if isRebooting {
                    HStack {
                        ProgressView()
                        Text(L10n.Settings.DeviceActions.rebooting)
                    }
                } else {
                    Label(L10n.Settings.DeviceActions.rebootDevice, systemImage: "arrow.clockwise")
                }
            }
            .radioDisabled(for: appState.connectionState, or: isRebooting)
        } header: {
            Text(L10n.Settings.DeviceActions.header)
        }
        .themedRowBackground(theme)
        .alert(L10n.Settings.DeviceActions.Alert.Reboot.title, isPresented: $showingRebootAlert) {
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
            Button(L10n.Settings.DeviceActions.Alert.Reboot.confirm) {
                rebootDevice()
            }
        } message: {
            Text(L10n.Settings.DeviceActions.Alert.Reboot.message)
        }
        .errorAlert($errorMessage)
    }

    private func rebootDevice() {
        guard let settingsService = appState.services?.settingsService else { return }

        isRebooting = true
        Task {
            defer { isRebooting = false }
            do {
                try await settingsService.reboot()
            } catch BLEError.operationTimeout {
                // Expected - device reboots before BLE write callback arrives
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
