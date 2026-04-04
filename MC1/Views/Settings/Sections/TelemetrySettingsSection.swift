import SwiftUI
import MC1Services

/// Telemetry sharing configuration
struct TelemetrySettingsSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @State private var showError: String?
    @State private var retryAlert = RetryAlertState()
    @State private var isSaving = false

    private var device: DeviceDTO? { appState.connectedDevice }

    var body: some View {
        Section {
            Toggle(isOn: telemetryEnabledBinding) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.Settings.Telemetry.allowRequests)
                    Text(L10n.Settings.Telemetry.allowRequestsDescription)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .radioDisabled(for: appState.connectionState, or: isSaving)

            if device?.telemetryModeBase ?? 0 > 0 {
                Toggle(isOn: locationEnabledBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.Settings.Telemetry.includeLocation)
                        Text(L10n.Settings.Telemetry.includeLocationDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .radioDisabled(for: appState.connectionState, or: isSaving)

                Toggle(isOn: environmentEnabledBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.Settings.Telemetry.includeEnvironment)
                        Text(L10n.Settings.Telemetry.includeEnvironmentDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .radioDisabled(for: appState.connectionState, or: isSaving)

                Toggle(isOn: filterByTrustedBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.Settings.Telemetry.trustedOnly)
                        Text(L10n.Settings.Telemetry.trustedOnlyDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .radioDisabled(for: appState.connectionState, or: isSaving)

                if isFilterByTrusted {
                    NavigationLink {
                        TrustedContactsPickerView()
                    } label: {
                        Text(L10n.Settings.Telemetry.manageTrusted)
                    }
                }
            }
        } header: {
            Text(L10n.Settings.Telemetry.header)
        } footer: {
            Text(L10n.Settings.Telemetry.footer)
        }
        .errorAlert($showError)
        .retryAlert(retryAlert)
    }

    // MARK: - Bindings

    private var isFilterByTrusted: Bool {
        device?.telemetryModeBase == 1
    }

    /// Mode value for "enabled" telemetry: 1 if trusted filtering active, 2 otherwise
    private var enabledMode: UInt8 {
        (device?.telemetryModeBase == 1) ? 1 : 2
    }

    private var telemetryEnabledBinding: Binding<Bool> {
        Binding(
            get: { device?.telemetryModeBase ?? 0 > 0 },
            set: { saveTelemetry(base: $0 ? enabledMode : 0) }
        )
    }

    private var locationEnabledBinding: Binding<Bool> {
        Binding(
            get: { device?.telemetryModeLoc ?? 0 > 0 },
            set: { saveTelemetry(location: $0 ? enabledMode : 0) }
        )
    }

    private var environmentEnabledBinding: Binding<Bool> {
        Binding(
            get: { device?.telemetryModeEnv ?? 0 > 0 },
            set: { saveTelemetry(environment: $0 ? enabledMode : 0) }
        )
    }

    private var filterByTrustedBinding: Binding<Bool> {
        Binding(
            get: { device?.telemetryModeBase == 1 },
            set: { newValue in
                let mode: UInt8 = newValue ? 1 : 2
                saveTelemetry(
                    base: (device?.telemetryModeBase ?? 0) > 0 ? mode : 0,
                    location: (device?.telemetryModeLoc ?? 0) > 0 ? mode : 0,
                    environment: (device?.telemetryModeEnv ?? 0) > 0 ? mode : 0
                )
            }
        )
    }

    // MARK: - Save

    private func saveTelemetry(
        base: UInt8? = nil,
        location: UInt8? = nil,
        environment: UInt8? = nil
    ) {
        guard let device, let settingsService = appState.services?.settingsService else { return }

        isSaving = true
        Task {
            do {
                let modes = TelemetryModes(
                    base: base ?? device.telemetryModeBase,
                    location: location ?? device.telemetryModeLoc,
                    environment: environment ?? device.telemetryModeEnv
                )
                _ = try await settingsService.setOtherParamsVerified(from: device, telemetryModes: modes)
                retryAlert.reset()
            } catch let error as SettingsServiceError where error.isRetryable {
                retryAlert.show(
                    message: error.errorDescription ?? L10n.Localizable.Common.Error.connectionError,
                    onRetry: { saveTelemetry(base: base, location: location, environment: environment) },
                    onMaxRetriesExceeded: { dismiss() }
                )
            } catch {
                showError = error.localizedDescription
            }
            isSaving = false
        }
    }
}
