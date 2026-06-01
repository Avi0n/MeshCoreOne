import os
import SwiftUI
import MC1Services

private let logger = Logger(subsystem: "com.mc1", category: "DeviceSelectionSheet")

/// Represents a device that can be selected for connection
private enum SelectableDevice: Identifiable, Equatable {
    case saved(DeviceDTO)
    case accessory(id: UUID, name: String)

    var id: UUID {
        switch self {
        case .saved(let device): device.id
        case .accessory(let id, _): id
        }
    }

    var name: String {
        switch self {
        case .saved(let device): device.nodeName
        case .accessory(_, let name): name
        }
    }

    /// The primary connection method for display purposes.
    /// WiFi methods are preferred over Bluetooth when available.
    var primaryConnectionMethod: ConnectionMethod? {
        switch self {
        case .saved(let device):
            // Prefer WiFi if available
            device.connectionMethods.first { $0.isWiFi } ?? device.connectionMethods.first
        case .accessory:
            nil
        }
    }

    /// Whether this device will connect over WiFi (its preferred method is WiFi).
    /// Such rows stay tappable regardless of BLE advertisement, since the connect
    /// path routes them to WiFi; only BLE-reachable-only rows gate on a live signal.
    /// A radio reachable over both transports is included here — its peripheral UUID
    /// may differ from `id`, so a BLE-signal gate would strand a WiFi-connectable row.
    var connectsViaWiFi: Bool {
        primaryConnectionMethod?.isWiFi == true
    }
}

/// Filters saved `Device` rows to those the user can actually reach from this phone.
///
/// A backup restore inserts "shadow" `Device` rows: their Bluetooth connection methods
/// were stripped by `cleanedForImport()` and their `id` is a fresh `UUID` that isn't
/// registered with AccessorySetupKit on this phone. Those rows stay in SwiftData so
/// `ConnectionManager.buildServicesAndSaveDevice` can reconcile them by `publicKey`
/// when the user later pairs the radio, but they must not appear in the picker until
/// then — they look like saved devices but no tap can connect them.
enum DeviceSelectionFilter {
    static func isConnectable(_ device: DeviceDTO, pairedAccessoryIDs: Set<UUID>, hasSystemPairingRegistry: Bool = true) -> Bool {
        if device.connectionMethods.contains(where: \.isWiFi) { return true }
        guard hasSystemPairingRegistry else {
            // No AccessorySetupKit registry to validate against (macOS). A real saved BLE
            // device retains its `.bluetooth` connection method, while demoted ghosts and
            // backup shadows have it stripped — so the method itself is the reachability signal.
            return device.connectionMethods.contains(where: \.isBluetooth)
        }
        return pairedAccessoryIDs.contains(device.id)
    }
}

/// Sheet for selecting and reconnecting to previously paired devices
struct DeviceSelectionSheet: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss

    @State private var devices: [SelectableDevice] = []
    @State private var showingWiFiConnection = false
    @State private var editingWiFiDevice: SelectableDevice?
    @State private var devicesConnectedElsewhere: Set<UUID> = []
    @State private var tracker = RSSIScanTracker()

    var body: some View {
        NavigationStack {
            Group {
                if devices.isEmpty {
                    makeEmptyStateView()
                } else {
                    makeDeviceListView()
                }
            }
            .navigationTitle(L10n.Settings.DeviceSelection.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Localizable.Common.cancel) {
                        dismiss()
                    }
                }
            }
            .task {
                await loadDevices()
                await startBLEScanning()
            }
        }
    }

    // MARK: - Subviews

    private func makeDeviceListView() -> some View {
        DeviceListView(
            devices: devices,
            devicesConnectedElsewhere: devicesConnectedElsewhere,
            tracker: tracker,
            showingWiFiConnection: $showingWiFiConnection,
            editingWiFiDevice: $editingWiFiDevice,
            onConnect: { connectToDevice($0) },
            onDelete: { deleteDevice($0) },
            onScanForNew: { scanForNewDevice() }
        )
    }

    private func makeEmptyStateView() -> some View {
        EmptyStateView(
            showingWiFiConnection: $showingWiFiConnection,
            onScanForNew: { scanForNewDevice() }
        )
    }

    // MARK: - Actions

    private func startBLEScanning() async {
        await tracker.consume(appState.connectionManager.startBLEScanning())
    }

    private func loadDevices() async {
        // Try to load from SwiftData first
        let pairedAccessoryIDs = Set(appState.connectionManager.pairedAccessoryInfos.map(\.id))
        let hasSystemPairingRegistry = appState.connectionManager.hasSystemPairingRegistry
        do {
            let savedDevices = try await appState.connectionManager.fetchSavedDevices()
            let connectableDevices = savedDevices.filter {
                DeviceSelectionFilter.isConnectable($0, pairedAccessoryIDs: pairedAccessoryIDs, hasSystemPairingRegistry: hasSystemPairingRegistry)
            }
            if !connectableDevices.isEmpty {
                devices = connectableDevices.map { .saved($0) }

                // Check which devices are connected elsewhere (BLE only)
                var connectedElsewhere: Set<UUID> = []
                for device in connectableDevices {
                    // Skip WiFi-only devices
                    let hasBluetooth = device.connectionMethods.isEmpty ||
                        device.connectionMethods.contains { !$0.isWiFi }
                    if hasBluetooth {
                        if await appState.connectionManager.isDeviceConnectedToOtherApp(device.id) {
                            connectedElsewhere.insert(device.id)
                        }
                    }
                }
                devicesConnectedElsewhere = connectedElsewhere
                return
            }
        } catch {
            logger.error("Failed to load devices: \(error)")
        }

        // Fall back to ASK accessories when the filter drops every saved row (or the DB is empty)
        let accessories = appState.connectionManager.pairedAccessoryInfos
        devices = accessories.map { .accessory(id: $0.id, name: $0.name) }

        // Check which accessories are connected elsewhere
        var connectedElsewhere: Set<UUID> = []
        for accessory in accessories where await appState.connectionManager.isDeviceConnectedToOtherApp(accessory.id) {
            connectedElsewhere.insert(accessory.id)
        }
        devicesConnectedElsewhere = connectedElsewhere
    }

    private func scanForNewDevice() {
        dismiss()
        Task {
            await appState.connectionManager.stopBLEScanning()
            await appState.disconnect(reason: .switchingDevice)
            // Trigger ASK picker flow via AppState
            appState.startDeviceScan()
        }
    }

    private func connectToDevice(_ device: SelectableDevice) {
        dismiss()
        Task {
            logger.info("[UI] User tapped Connect for device: \(device.id.uuidString.prefix(8)), name: \(device.name)")
            do {
                if case .wifi(let host, let port, _) = device.primaryConnectionMethod {
                    try await appState.connectViaWiFi(host: host, port: port, forceFullSync: true)
                } else {
                    try await appState.connectionManager.connect(to: device.id, forceFullSync: true, forceReconnect: true)
                }
            } catch BLEError.deviceConnectedToOtherApp {
                appState.connectionUI.otherAppWarningDeviceID = device.id
            } catch {
                appState.connectionUI.presentConnectionFailure(message: error.localizedDescription)
            }
        }
    }

    private func deleteDevice(_ device: SelectableDevice) {
        guard case .saved(let deviceDTO) = device else { return }

        Task {
            do {
                try await appState.connectionManager.deleteDevice(id: deviceDTO.id)
                devices.removeAll { $0.id == device.id }
            } catch {
                logger.error("Failed to delete device: \(error)")
            }
        }
    }
}

// MARK: - Device List View

private struct DeviceListView: View {
    @Environment(\.appTheme) private var theme
    let devices: [SelectableDevice]
    let devicesConnectedElsewhere: Set<UUID>
    let tracker: RSSIScanTracker
    @Binding var showingWiFiConnection: Bool
    @Binding var editingWiFiDevice: SelectableDevice?
    let onConnect: (SelectableDevice) -> Void
    let onDelete: (SelectableDevice) -> Void
    let onScanForNew: () -> Void

    var body: some View {
        List {
            Section {
                ForEach(devices) { device in
                    let tier = device.connectsViaWiFi ? nil : tracker.signalTier(for: device.id)
                    let isDisabledByBLE = !device.connectsViaWiFi && !tracker.isAdvertising(device.id)
                    Button {
                        guard !isDisabledByBLE else { return }
                        onConnect(device)
                    } label: {
                        DeviceRow(
                            device: device,
                            connectsViaWiFi: device.connectsViaWiFi,
                            isConnectedElsewhere: devicesConnectedElsewhere.contains(device.id),
                            signalTier: tier
                        )
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button(role: .destructive) {
                            onDelete(device)
                        } label: {
                            Label(L10n.Localizable.Common.delete, systemImage: "trash")
                        }

                        if device.primaryConnectionMethod?.isWiFi == true {
                            Button {
                                editingWiFiDevice = device
                            } label: {
                                Label(L10n.Localizable.Common.edit, systemImage: "pencil")
                            }
                        }
                    }
                }
            } header: {
                Text(L10n.Settings.DeviceSelection.previouslyPaired)
            }
            .themedRowBackground(theme)

            Section {
                Button {
                    showingWiFiConnection = true
                } label: {
                    Label(L10n.Settings.DeviceSelection.connectViaWifi, systemImage: "wifi.circle")
                }

                Button {
                    onScanForNew()
                } label: {
                    Label(L10n.Settings.DeviceSelection.scanBluetooth, systemImage: "antenna.radiowaves.left.and.right")
                }
            }
            .themedRowBackground(theme)
        }
        .themedCanvas(theme)
        .sheet(isPresented: $showingWiFiConnection) {
            WiFiConnectionSheet()
        }
        .sheet(item: $editingWiFiDevice) { device in
            if case .wifi(let host, let port, _) = device.primaryConnectionMethod {
                WiFiEditSheet(initialHost: host, initialPort: port)
            }
        }
    }
}

// MARK: - Empty State View

private struct EmptyStateView: View {
    @Binding var showingWiFiConnection: Bool
    let onScanForNew: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(L10n.Settings.DeviceSelection.noPairedDevices, systemImage: "antenna.radiowaves.left.and.right.slash")
        } description: {
            VStack(spacing: 20) {
                Text(L10n.Settings.DeviceSelection.noPairedDescription)

                VStack(spacing: 12) {
                    Button(L10n.Settings.DeviceSelection.connectViaWifi, systemImage: "wifi.circle") {
                        showingWiFiConnection = true
                    }
                    .liquidGlassProminentButtonStyle()

                    Button(
                        L10n.Settings.DeviceSelection.scanForDevices,
                        systemImage: "antenna.radiowaves.left.and.right"
                    ) {
                        onScanForNew()
                    }
                    .liquidGlassProminentButtonStyle()
                }
            }
        }
        .sheet(isPresented: $showingWiFiConnection) {
            WiFiConnectionSheet()
        }
    }
}

// MARK: - Device Row

private struct DeviceRow: View {
    let device: SelectableDevice
    let connectsViaWiFi: Bool
    let isConnectedElsewhere: Bool
    let signalTier: RSSITuning.SignalTier?

    private var isUnreachable: Bool {
        !connectsViaWiFi && signalTier == nil
    }

    private var transportIcon: String {
        guard let method = device.primaryConnectionMethod else {
            return "antenna.radiowaves.left.and.right"
        }
        return method.isWiFi ? "wifi" : "antenna.radiowaves.left.and.right"
    }

    private var transportColor: Color {
        guard let method = device.primaryConnectionMethod else {
            return .green
        }
        return method.isWiFi ? .blue : .green
    }

    private var connectionDescription: String {
        if let method = device.primaryConnectionMethod, method.isWiFi {
            return method.shortDescription
        }
        return L10n.Settings.DeviceSelection.bluetooth
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: transportIcon)
                .font(.title2)
                .foregroundStyle(transportColor)
                .frame(width: 40, height: 40)
                .background(transportColor.opacity(0.1), in: .circle)

            VStack(alignment: .leading, spacing: 2) {
                Text(device.name)
                    .font(.headline)

                if isConnectedElsewhere {
                    Label(
                        L10n.Settings.DeviceSelection.connectedElsewhere,
                        systemImage: "exclamationmark.triangle.fill"
                    )
                        .font(.caption)
                        .foregroundStyle(.orange)
                } else {
                    Text(connectionDescription)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            if let tier = signalTier {
                SignalBars(tier: tier)
            }
        }
        .padding(.vertical, 4)
        .contentShape(.rect)
        .opacity(isConnectedElsewhere || isUnreachable ? 0.4 : 1.0)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(isConnectedElsewhere
            ? L10n.Settings.DeviceSelection.Accessibility.connectedElsewhereLabel(device.name)
            : L10n.Settings.DeviceSelection.Accessibility.deviceLabel(device.name, connectionDescription))
        .accessibilityValue(signalDescription)
        .accessibilityHint(isConnectedElsewhere
            ? L10n.Settings.DeviceSelection.Accessibility.connectedElsewhereHint
            : isUnreachable
            ? L10n.Settings.DeviceSelection.Accessibility.outOfRangeHint
            : L10n.Settings.DeviceSelection.Accessibility.selectHint)
    }

    // MARK: - Signal Tier Helpers

    /// VoiceOver descriptor for the signal tier, announced as the row's accessibility value so
    /// it survives the explicit `accessibilityLabel` above (which overrides combined children).
    /// Empty when the device is out of range so VoiceOver announces no value.
    private var signalDescription: String {
        guard let tier = signalTier else { return "" }
        return SignalBars.accessibilityDescription(forTier: tier)
    }
}
