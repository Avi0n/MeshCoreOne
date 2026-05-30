import SwiftUI
import MC1Services

/// Settings section for direct message acknowledgment count
struct DirectMessagesSettingsSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?
    @State private var retryAlert = RetryAlertState()
    @State private var isSaving = false

    private var device: DeviceDTO? { appState.connectedDevice }

    var body: some View {
        Section {
            Picker(L10n.Settings.DirectMessages.acknowledgments, selection: acksBinding) {
                Text("1").tag(1)
                Text("2").tag(2)
            }
            .pickerStyle(.menu)
            .radioDisabled(for: appState.connectionState, or: isSaving)
        } header: {
            Text(L10n.Settings.DirectMessages.header)
        } footer: {
            Text(L10n.Settings.DirectMessages.footer)
        }
        .themedRowBackground(theme)
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
    }

    // MARK: - Binding

    private var acksBinding: Binding<Int> {
        Binding(
            get: { Int(device?.multiAcks ?? 0) + 1 },
            set: { saveMultiAcks(UInt8($0 - 1)) }
        )
    }

    // MARK: - Save

    private func saveMultiAcks(_ value: UInt8) {
        guard let device, let settingsService = appState.services?.settingsService else { return }

        isSaving = true
        Task {
            do {
                _ = try await settingsService.setOtherParamsVerified(from: device, multiAcks: value)
                retryAlert.reset()
            } catch let error as SettingsServiceError where error.isRetryable {
                retryAlert.show(
                    message: error.errorDescription ?? L10n.Localizable.Common.Error.connectionError,
                    onRetry: { saveMultiAcks(value) },
                    onMaxRetriesExceeded: { dismiss() }
                )
            } catch {
                errorMessage = error.localizedDescription
            }
            isSaving = false
        }
    }
}

#Preview {
    Form {
        DirectMessagesSettingsSection()
    }
}
