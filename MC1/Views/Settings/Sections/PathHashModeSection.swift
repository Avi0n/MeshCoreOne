import SwiftUI
import MC1Services

/// Picker for configuring the path hash size on firmware v10+ devices.
struct PathHashModeSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var selectedMode: UInt8?
    @State private var isApplying = false
    @State private var errorMessage: String?
    @State private var retryAlert = RetryAlertState()

    var body: some View {
        Section {
            Picker(L10n.Settings.PathHashMode.label, selection: Binding(
                get: { selectedMode ?? appState.connectedDevice?.pathHashMode ?? 0 },
                set: { newMode in
                    guard newMode != selectedMode else { return }
                    selectedMode = newMode
                    applyMode(newMode)
                }
            )) {
                Text(L10n.Settings.PathHashMode.oneByte).tag(UInt8(0))
                Text(L10n.Settings.PathHashMode.twoBytes).tag(UInt8(1))
                Text(L10n.Settings.PathHashMode.threeBytes).tag(UInt8(2))
            }
            .pickerStyle(.menu)
            .tint(.primary)
            .radioDisabled(for: appState.connectionState, or: isApplying)
        } header: {
            Text(L10n.Settings.PathHashMode.header)
        } footer: {
            Text(L10n.Settings.PathHashMode.footer)
        }
        .themedRowBackground(theme)
        .onAppear {
            selectedMode = appState.connectedDevice?.pathHashMode ?? 0
        }
        .onChange(of: appState.connectedDevice?.pathHashMode) { _, newValue in
            if let newValue {
                selectedMode = newValue
            }
        }
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
    }

    private func applyMode(_ mode: UInt8) {
        isApplying = true
        Task {
            do {
                guard let settingsService = appState.services?.settingsService else {
                    throw ConnectionError.notConnected
                }
                _ = try await settingsService.setPathHashModeVerified(mode)
                retryAlert.reset()
            } catch let error as SettingsServiceError where error.isRetryable {
                selectedMode = appState.connectedDevice?.pathHashMode ?? 0
                retryAlert.show(
                    message: error.userFacingMessage,
                    onRetry: { applyMode(mode) },
                    onMaxRetriesExceeded: { dismiss() }
                )
            } catch {
                errorMessage = error.userFacingMessage
                selectedMode = appState.connectedDevice?.pathHashMode ?? 0
            }
            isApplying = false
        }
    }
}
