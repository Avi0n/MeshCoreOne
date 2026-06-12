import SwiftUI
import MC1Services

/// Firmware telemetry permission levels, shared by the base, location, and environment modes.
private enum TelemetryMode {
    static let off: UInt8 = 0
    static let trustedOnly: UInt8 = 1
    static let everyone: UInt8 = 2
}

/// Telemetry sharing configuration
struct TelemetrySettingsSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?
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

            if device?.telemetryModeBase ?? TelemetryMode.off > TelemetryMode.off {
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
                    NavigationLink(value: SettingsSubpage.trustedContacts) {
                        Text(L10n.Settings.Telemetry.manageTrusted)
                    }
                }
            }
        } header: {
            Text(L10n.Settings.Telemetry.header)
        } footer: {
            Text(L10n.Settings.Telemetry.footer)
        }
        .themedRowBackground(theme)
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
    }

    // MARK: - Bindings

    private var isFilterByTrusted: Bool {
        device?.telemetryModeBase == TelemetryMode.trustedOnly
    }

    /// Mode value for "enabled" telemetry: trusted-only if trusted filtering is active, everyone otherwise
    private var enabledMode: UInt8 {
        isFilterByTrusted ? TelemetryMode.trustedOnly : TelemetryMode.everyone
    }

    private var telemetryEnabledBinding: Binding<Bool> {
        Binding(
            get: { device?.telemetryModeBase ?? TelemetryMode.off > TelemetryMode.off },
            set: { saveTelemetry(base: $0 ? enabledMode : TelemetryMode.off) }
        )
    }

    private var locationEnabledBinding: Binding<Bool> {
        Binding(
            get: { device?.telemetryModeLoc ?? TelemetryMode.off > TelemetryMode.off },
            set: { saveTelemetry(location: $0 ? enabledMode : TelemetryMode.off) }
        )
    }

    private var environmentEnabledBinding: Binding<Bool> {
        Binding(
            get: { device?.telemetryModeEnv ?? TelemetryMode.off > TelemetryMode.off },
            set: { saveTelemetry(environment: $0 ? enabledMode : TelemetryMode.off) }
        )
    }

    private var filterByTrustedBinding: Binding<Bool> {
        Binding(
            get: { device?.telemetryModeBase == TelemetryMode.trustedOnly },
            set: { newValue in
                let mode: UInt8 = newValue ? TelemetryMode.trustedOnly : TelemetryMode.everyone
                saveTelemetry(
                    base: (device?.telemetryModeBase ?? TelemetryMode.off) > TelemetryMode.off ? mode : TelemetryMode.off,
                    location: (device?.telemetryModeLoc ?? TelemetryMode.off) > TelemetryMode.off ? mode : TelemetryMode.off,
                    environment: (device?.telemetryModeEnv ?? TelemetryMode.off) > TelemetryMode.off ? mode : TelemetryMode.off
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
                    message: error.userFacingMessage,
                    onRetry: { saveTelemetry(base: base, location: location, environment: environment) },
                    onMaxRetriesExceeded: { dismiss() }
                )
            } catch {
                errorMessage = error.userFacingMessage
            }
            isSaving = false
        }
    }
}
