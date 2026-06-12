import SwiftUI
import MC1Services

/// Firmware BLE pairing PIN protocol values. The firmware treats both the factory
/// default and zero as "no custom PIN configured".
private enum BLEPin {
    static let firmwareDefault: UInt32 = 123456
    static let unset: UInt32 = 0
    /// A custom PIN must be exactly six digits.
    static let customRange: ClosedRange<UInt32> = 100000...999999
}

/// Bluetooth PIN configuration
struct BluetoothSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @State private var pinType: BluetoothPinType = .default
    @State private var customPin: String = ""
    @State private var showingPinEntry = false
    @State private var showingChangePinEntry = false
    @State private var showingRemoveConfirmation = false
    @State private var isChangingPin = false
    @State private var errorMessage: String?
    @State private var hasInitialized = false
    @State private var isPinVisible = false

    // Track what the user intended before confirmation dialogs
    @State private var pendingPinType: BluetoothPinType?
    @State private var isRenaming = false

    enum BluetoothPinType: String, CaseIterable {
        case `default` = "Default"
        case custom = "Custom PIN"

        var localizedName: String {
            switch self {
            case .default: L10n.Settings.Bluetooth.PinType.default
            case .custom: L10n.Settings.Bluetooth.PinType.custom
            }
        }
    }

    private var currentPinType: BluetoothPinType {
        guard let device = appState.connectedDevice else { return .default }
        if device.blePin == BLEPin.unset || device.blePin == BLEPin.firmwareDefault {
            return .default
        } else {
            return .custom
        }
    }

    var body: some View {
        Section {
            Picker(L10n.Settings.Bluetooth.pinType, selection: $pinType) {
                ForEach(BluetoothPinType.allCases, id: \.self) { type in
                    Text(type.localizedName).tag(type)
                }
            }
            .onChange(of: pinType) { oldValue, newValue in
                guard hasInitialized else { return }
                handlePinTypeChange(from: oldValue, to: newValue)
            }
            .radioDisabled(for: appState.connectionState, or: isChangingPin)

            if pinType == .custom, let device = appState.connectedDevice, device.blePin > 0 {
                Button {
                    isPinVisible.toggle()
                } label: {
                    HStack {
                        Text(L10n.Settings.Bluetooth.currentPin)
                        Spacer()
                        Group {
                            if isPinVisible {
                                Text(device.blePin, format: .number.grouping(.never))
                            } else {
                                Text("••••••")
                            }
                        }
                        .font(.body.monospacedDigit())
                        .foregroundStyle(.secondary)

                        Image(systemName: isPinVisible ? "eye" : "eye.slash")
                            .foregroundStyle(.tertiary)
                            .imageScale(.small)
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)

                Button(L10n.Settings.Bluetooth.changePin) {
                    customPin = ""
                    showingChangePinEntry = true
                }
                .radioDisabled(for: appState.connectionState)
            }

            if appState.connectionState == .ready,
               let deviceID = appState.connectedDevice?.id,
               appState.connectionManager.supportsDeviceRename,
               appState.connectionManager.hasAccessory(for: deviceID) {
                Button {
                    renameDevice()
                } label: {
                    HStack {
                        Text(L10n.Settings.Bluetooth.changeDisplayName)
                        Spacer()
                        if isRenaming {
                            ProgressView()
                                .controlSize(.small)
                        }
                    }
                }
                .radioDisabled(for: appState.connectionState, or: isRenaming)
            }
        } header: {
            Text(L10n.Settings.Bluetooth.header)
        } footer: {
            if pinType == .default {
                Text(L10n.Settings.Bluetooth.defaultPinFooter)
            }
        }
        .themedRowBackground(theme)
        .onAppear {
            isPinVisible = false
            pinType = currentPinType
            Task { @MainActor in
                hasInitialized = true
            }
        }
        .alert(L10n.Settings.Bluetooth.Alert.SetPin.title, isPresented: $showingPinEntry) {
            TextField(L10n.Settings.Bluetooth.pinPlaceholder, text: $customPin)
                .keyboardType(.numberPad)
            Button(L10n.Localizable.Common.cancel, role: .cancel) {
                // Revert to previous pin type without triggering onChange loop
                hasInitialized = false
                pinType = currentPinType
                Task { @MainActor in
                    hasInitialized = true
                }
            }
            Button(L10n.Settings.Bluetooth.setPin) {
                setCustomPin()
            }
        } message: {
            Text(L10n.Settings.Bluetooth.Alert.SetPin.message)
        }
        .alert(L10n.Settings.Bluetooth.Alert.ChangePin.title, isPresented: $showingChangePinEntry) {
            TextField(L10n.Settings.Bluetooth.pinPlaceholder, text: $customPin)
                .keyboardType(.numberPad)
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
            Button(L10n.Settings.Bluetooth.changePin) {
                setCustomPin()
            }
        } message: {
            Text(L10n.Settings.Bluetooth.Alert.ChangePin.message)
        }
        .alert(L10n.Settings.Bluetooth.Alert.ChangePinType.title, isPresented: $showingRemoveConfirmation) {
            Button(L10n.Localizable.Common.cancel, role: .cancel) {
                // Revert to previous pin type without triggering onChange loop
                hasInitialized = false
                pinType = currentPinType
                Task { @MainActor in
                    hasInitialized = true
                }
            }
            Button(L10n.Settings.Bluetooth.Alert.change) {
                applyPendingPinType()
            }
        } message: {
            Text(L10n.Settings.Bluetooth.Alert.ChangePinType.message)
        }
        .errorAlert($errorMessage)
    }

    private func handlePinTypeChange(from oldValue: BluetoothPinType, to newValue: BluetoothPinType) {
        // Skip if picker is being synced to device's current state (handles initialization race condition)
        guard newValue != currentPinType else { return }

        // If changing to custom, show PIN entry
        if newValue == .custom && oldValue != .custom {
            showingPinEntry = true
            return
        }

        // If changing from custom to default, show confirmation
        if oldValue == .custom && newValue == .default {
            pendingPinType = newValue
            showingRemoveConfirmation = true
        }
    }

    private func applyPendingPinType() {
        guard let pending = pendingPinType else { return }
        pendingPinType = nil

        let pinValue: UInt32 = pending == .default ? BLEPin.firmwareDefault : BLEPin.unset

        isChangingPin = true
        Task {
            do {
                guard let settingsService = appState.services?.settingsService else {
                    throw ConnectionError.notConnected
                }

                // Send PIN change command (written to RAM until reboot)
                try await settingsService.setBlePin(pinValue)

                // Reboot device to apply PIN change - iOS auto-reconnects
                // Timeout is expected since device disconnects before write callback
                do {
                    try await settingsService.reboot()
                } catch BLEError.operationTimeout {
                    // Expected - device reboots before BLE write callback arrives
                }
            } catch {
                errorMessage = error.userFacingMessage
                // Revert
                hasInitialized = false
                pinType = currentPinType
                Task { @MainActor in
                    hasInitialized = true
                }
            }
            isChangingPin = false
        }
    }

    private func setCustomPin() {
        guard let pin = UInt32(customPin), BLEPin.customRange.contains(pin) else {
            errorMessage = L10n.Settings.Bluetooth.Error.invalidPin
            customPin = ""
            // Revert
            hasInitialized = false
            pinType = currentPinType
            Task { @MainActor in
                hasInitialized = true
            }
            return
        }

        isChangingPin = true
        Task {
            do {
                guard let settingsService = appState.services?.settingsService else {
                    throw ConnectionError.notConnected
                }

                // Send PIN change command (written to RAM until reboot)
                try await settingsService.setBlePin(pin)

                // Reboot device to apply PIN change - iOS auto-reconnects
                // Timeout is expected since device disconnects before write callback
                do {
                    try await settingsService.reboot()
                } catch BLEError.operationTimeout {
                    // Expected - device reboots before BLE write callback arrives
                }
            } catch {
                errorMessage = error.userFacingMessage
                // Revert
                hasInitialized = false
                pinType = currentPinType
                Task { @MainActor in
                    hasInitialized = true
                }
            }
            isChangingPin = false
            customPin = ""
        }
    }

    private func renameDevice() {
        isRenaming = true
        Task { @MainActor in
            defer { isRenaming = false }

            do {
                try await appState.connectionManager.renameCurrentDevice()
            } catch {
                // User cancelled or rename failed - no error to show
                // Rename is optional and user can try again
            }
        }
    }
}
