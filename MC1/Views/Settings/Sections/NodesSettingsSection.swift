import SwiftUI
import MC1Services

/// Auto-add mode and type settings for node discovery
struct NodesSettingsSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?
    @State private var retryAlert = RetryAlertState()
    @State private var isApplying = false
    @State private var showSuccess = false

    // Local state for editing
    @State private var autoAddMode: AutoAddMode = .manual
    @State private var autoAddContacts = false
    @State private var autoAddRepeaters = false
    @State private var autoAddRoomServers = false
    @State private var overwriteOldest = false
    @State private var autoAddMaxHops: UInt8 = 0

    private var device: DeviceDTO? { appState.connectedDevice }

    /// Whether the device supports v1.12+ auto-add config features
    private var supportsAutoAddConfig: Bool {
        device?.supportsAutoAddConfig ?? false
    }

    private var supportsAutoAddMaxHops: Bool {
        device?.supportsAutoAddMaxHops ?? false
    }

    private var settingsModified: Bool {
        guard let device else { return false }
        if device.supportsAutoAddConfig {
            return autoAddMode != device.autoAddMode ||
                autoAddContacts != device.autoAddContacts ||
                autoAddRepeaters != device.autoAddRepeaters ||
                autoAddRoomServers != device.autoAddRoomServers ||
                overwriteOldest != device.overwriteOldest ||
                autoAddMaxHops != device.autoAddMaxHops
        } else {
            let deviceMode: AutoAddMode = device.manualAddContacts ? .manual : .all
            return autoAddMode != deviceMode
        }
    }

    private var canApply: Bool {
        appState.connectionState == .ready && settingsModified && !isApplying && !showSuccess
    }

    /// Combined hash of all node settings for change detection
    private var deviceNodeSettingsHash: Int {
        var hasher = Hasher()
        hasher.combine(appState.connectedDevice?.autoAddMode)
        hasher.combine(appState.connectedDevice?.autoAddContacts)
        hasher.combine(appState.connectedDevice?.autoAddRepeaters)
        hasher.combine(appState.connectedDevice?.autoAddRoomServers)
        hasher.combine(appState.connectedDevice?.overwriteOldest)
        hasher.combine(appState.connectedDevice?.supportsAutoAddConfig)
        hasher.combine(appState.connectedDevice?.autoAddMaxHops)
        hasher.combine(appState.connectedDevice?.supportsAutoAddMaxHops)
        return hasher.finalize()
    }

    var body: some View {
        Section {
            Picker(L10n.Settings.Nodes.autoAddMode, selection: $autoAddMode) {
                Text(L10n.Settings.Nodes.AutoAddMode.manual).tag(AutoAddMode.manual)
                if supportsAutoAddConfig {
                    Text(L10n.Settings.Nodes.AutoAddMode.selectedTypes).tag(AutoAddMode.selectedTypes)
                }
                Text(L10n.Settings.Nodes.AutoAddMode.all).tag(AutoAddMode.all)
            }
            .pickerStyle(.menu)
            .onChange(of: autoAddMode) { _, newValue in
                // Mode changes reveal or hide the type and hop rows below the picker.
                if newValue != .manual {
                    AccessibilityNotification.LayoutChanged().post()
                }
            }

            if supportsAutoAddConfig && autoAddMode == .selectedTypes {
                Toggle(L10n.Settings.Nodes.autoAddContacts, isOn: $autoAddContacts)
                Toggle(L10n.Settings.Nodes.autoAddRepeaters, isOn: $autoAddRepeaters)
                Toggle(L10n.Settings.Nodes.autoAddRoomServers, isOn: $autoAddRoomServers)
            }

            if supportsAutoAddMaxHops && autoAddMode != .manual {
                Picker(L10n.Settings.Nodes.maxHops, selection: $autoAddMaxHops) {
                    Text(L10n.Settings.Nodes.MaxHops.noLimit).tag(UInt8(0))
                    Text(L10n.Settings.Nodes.MaxHops.directOnly).tag(UInt8(1))
                    Text(L10n.Settings.Nodes.MaxHops.oneHop).tag(UInt8(2))
                    ForEach(Array(2...6), id: \.self) { hops in
                        Text(L10n.Settings.Nodes.MaxHops.hops(hops)).tag(UInt8(hops + 1))
                    }
                }
                .pickerStyle(.menu)
            }

            if supportsAutoAddConfig {
                Toggle(isOn: $overwriteOldest) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.Settings.Nodes.overwriteOldest)
                        Text(L10n.Settings.Nodes.overwriteOldestDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Button {
                applySettings()
            } label: {
                AsyncActionLabel(isLoading: isApplying, showSuccess: showSuccess) {
                    Text(L10n.Settings.AdvancedRadio.apply)
                        .foregroundStyle(canApply ? Color.accentColor : .secondary)
                        .transition(.opacity)
                }
            }
            .radioDisabled(for: appState.connectionState, or: isApplying || showSuccess || !settingsModified)
        } header: {
            Text(L10n.Settings.Nodes.header)
        } footer: {
            Text(footerDescription)
        }
        .themedRowBackground(theme)
        .radioDisabled(for: appState.connectionState, or: isApplying)
        .onAppear {
            loadFromDevice()
        }
        .onChange(of: deviceNodeSettingsHash) { _, _ in
            loadFromDevice()
        }
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
    }

    private var footerDescription: String {
        var description = autoAddModeDescription
        if supportsAutoAddMaxHops && autoAddMode != .manual && autoAddMaxHops > 0 {
            description += "\n" + L10n.Settings.Nodes.MaxHops.footerActive
        }
        return description
    }

    private var autoAddModeDescription: String {
        switch autoAddMode {
        case .manual:
            return L10n.Settings.Nodes.AutoAddMode.manualDescription
        case .selectedTypes:
            return L10n.Settings.Nodes.AutoAddMode.selectedTypesDescription
        case .all:
            return L10n.Settings.Nodes.AutoAddMode.allDescription
        }
    }

    private func loadFromDevice() {
        guard let device else { return }

        if device.supportsAutoAddConfig {
            autoAddMode = device.autoAddMode
            autoAddContacts = device.autoAddContacts
            autoAddRepeaters = device.autoAddRepeaters
            autoAddRoomServers = device.autoAddRoomServers
            overwriteOldest = device.overwriteOldest
            autoAddMaxHops = device.autoAddMaxHops
        } else {
            // Older firmware only supports manual/all toggle via manualAddContacts
            autoAddMode = device.manualAddContacts ? .manual : .all
            autoAddContacts = false
            autoAddRepeaters = false
            autoAddRoomServers = false
            overwriteOldest = false
        }
    }

    private func applySettings() {
        guard !isApplying else { return }
        guard let device, let settingsService = appState.services?.settingsService else { return }

        isApplying = true
        Task {
            do {
                // Protocol: manualAddContacts=true for .manual and .selectedTypes, false only for .all
                let manualAdd = autoAddMode != .all

                // Save manualAddContacts (works on all firmware versions)
                _ = try await settingsService.setOtherParamsVerified(from: device, autoAddContacts: !manualAdd)

                // Save autoAddConfig only on v1.12+ firmware
                if device.supportsAutoAddConfig {
                    var config: UInt8 = 0
                    if overwriteOldest { config |= AutoAddConfig.overwriteOldestBit }

                    switch autoAddMode {
                    case .manual:
                        break
                    case .selectedTypes:
                        if autoAddContacts { config |= AutoAddConfig.contactsBit }
                        if autoAddRepeaters { config |= AutoAddConfig.repeatersBit }
                        if autoAddRoomServers { config |= AutoAddConfig.roomServersBit }
                    case .all:
                        break
                    }

                    _ = try await settingsService.setAutoAddConfigVerified(
                        AutoAddConfig(bitmask: config, maxHops: autoAddMaxHops)
                    )
                }

                retryAlert.reset()
                isApplying = false

                withAnimation {
                    showSuccess = true
                }
                try? await Task.sleep(for: .seconds(1.5))
                withAnimation {
                    showSuccess = false
                }
                return
            } catch let error as SettingsServiceError where error.isRetryable {
                loadFromDevice()
                retryAlert.show(
                    message: error.errorDescription ?? L10n.Localizable.Common.Error.connectionError,
                    onRetry: { applySettings() },
                    onMaxRetriesExceeded: { dismiss() }
                )
            } catch {
                loadFromDevice()
                errorMessage = error.localizedDescription
            }
            isApplying = false
        }
    }
}
