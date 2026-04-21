import SwiftUI
import MC1Services

/// Section for configuring the device's persisted default flood scope (firmware v11+).
///
/// Lets the user choose between ``FloodScope/disabled`` (clear), one of the
/// radio's already-known regions, or a custom region name. Selections are sent
/// via ``SettingsService/setDefaultFloodScopeVerified(name:)`` and the accepted
/// value is cached in ``DeviceDTO/defaultFloodScopeName``.
struct DefaultFloodScopeSection: View {
    @Environment(\.appState) private var appState
    @State private var isApplying = false
    @State private var errorMessage: String?
    @State private var retryAlert = RetryAlertState()
    @State private var customName = ""
    @FocusState private var customFieldFocused: Bool

    var body: some View {
        Section {
            disabledRow

            ForEach(sortedKnownRegions, id: \.self) { region in
                scopeRow(region)
            }

            customEntryRow
        } header: {
            Text(L10n.Settings.DefaultFloodScope.header)
        } footer: {
            Text(L10n.Settings.DefaultFloodScope.footer)
        }
        .radioDisabled(for: appState.connectionState, or: isApplying)
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
    }

    // MARK: - Rows

    private var disabledRow: some View {
        Button {
            apply(name: nil)
        } label: {
            row(title: L10n.Settings.DefaultFloodScope.disabled, selected: currentScope == nil)
        }
        .buttonStyle(.plain)
    }

    private func scopeRow(_ region: String) -> some View {
        Button {
            apply(name: region)
        } label: {
            row(title: region, selected: currentScope == region)
        }
        .buttonStyle(.plain)
    }

    private var customEntryRow: some View {
        HStack {
            TextField(
                L10n.Settings.DefaultFloodScope.customPlaceholder,
                text: $customName
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.done)
            .focused($customFieldFocused)
            .onSubmit(applyCustom)

            Button(L10n.Settings.DefaultFloodScope.apply, action: applyCustom)
                .disabled(trimmedCustomName.isEmpty)
        }
    }

    private func row(title: String, selected: Bool) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(Color.primary)
            Spacer()
            if selected {
                Image(systemName: "checkmark")
                    .foregroundStyle(.tint)
            }
        }
        .contentShape(.rect)
    }

    // MARK: - Derived state

    private var currentScope: String? {
        appState.connectedDevice?.defaultFloodScopeName
    }

    private var sortedKnownRegions: [String] {
        (appState.connectedDevice?.knownRegions ?? [])
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    private var trimmedCustomName: String {
        customName.trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Actions

    private func applyCustom() {
        let trimmed = trimmedCustomName
        guard !trimmed.isEmpty else { return }
        let existing = appState.connectedDevice?.knownRegions ?? []
        if let validationError = RegionNameValidator.validate(trimmed, existingRegions: existing) {
            errorMessage = validationText(for: validationError)
            return
        }
        customFieldFocused = false
        customName = ""
        apply(name: trimmed, addToKnownRegions: true)
    }

    private func validationText(for error: RegionNameValidator.ValidationError) -> String? {
        switch error {
        case .empty: nil
        case .invalidCharacters: L10n.Chats.Chats.ChannelInfo.Region.invalidName
        case .duplicate: L10n.Chats.Chats.ChannelInfo.Region.duplicate
        }
    }

    private func apply(name: String?, addToKnownRegions: Bool = false) {
        isApplying = true
        Task {
            do {
                guard let settingsService = appState.services?.settingsService else {
                    throw ConnectionError.notConnected
                }
                _ = try await settingsService.setDefaultFloodScopeVerified(name: name)

                if addToKnownRegions,
                   let name,
                   let radioID = appState.connectedDevice?.radioID,
                   appState.connectedDevice?.knownRegions.contains(name) == false {
                    try? await appState.offlineDataStore?.addDeviceKnownRegion(radioID: radioID, region: name)
                }

                retryAlert.reset()
            } catch let error as SettingsServiceError where error.isRetryable {
                retryAlert.show(
                    message: error.errorDescription ?? L10n.Settings.Alert.Retry.fallbackMessage,
                    onRetry: { apply(name: name, addToKnownRegions: addToKnownRegions) },
                    onMaxRetriesExceeded: {}
                )
            } catch {
                errorMessage = error.localizedDescription
            }
            isApplying = false
        }
    }
}
