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

    /// Whether this device connects only via WiFi (no BLE).
    var isWiFiOnly: Bool {
        switch self {
        case .saved(let device):
            !device.connectionMethods.isEmpty && device.connectionMethods.allSatisfy(\.isWiFi)
        case .accessory:
            false
        }
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
    static func isConnectable(_ device: DeviceDTO, pairedAccessoryIDs: Set<UUID>) -> Bool {
        device.connectionMethods.contains(where: \.isWiFi)
            || pairedAccessoryIDs.contains(device.id)
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
    @State private var deviceRSSI: [UUID: (rssi: Int, lastSeen: Date)] = [:]
    @State private var deviceSignalTier: [UUID: Int] = [:]

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
            deviceSignalTier: deviceSignalTier,
            deviceRSSI: deviceRSSI,
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
        let stream = appState.connectionManager.startBLEScanning()

        // Expire stale RSSI entries every 2 seconds
        let expiryTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                let cutoff = Date.now.addingTimeInterval(-4)
                for (id, entry) in deviceRSSI where entry.lastSeen < cutoff {
                    deviceRSSI.removeValue(forKey: id)
                    deviceSignalTier.removeValue(forKey: id)
                }
            }
        }
        defer { expiryTask.cancel() }

        for await (deviceID, rssi) in stream {
            // Filter invalid RSSI values (0, positive, or -127 indicate unavailable)
            guard rssi < 0, rssi != -127 else { continue }

            let smoothed: Int
            if let existing = deviceRSSI[deviceID] {
                smoothed = Int(0.2 * Double(rssi) + 0.8 * Double(existing.rssi))
            } else {
                smoothed = rssi
            }
            deviceRSSI[deviceID] = (rssi: smoothed, lastSeen: .now)
            deviceSignalTier[deviceID] = updatedSignalTier(
                currentTier: deviceSignalTier[deviceID],
                smoothedRSSI: smoothed
            )
        }
    }

    private func loadDevices() async {
        // Try to load from SwiftData first
        let pairedAccessoryIDs = Set(appState.connectionManager.pairedAccessoryInfos.map(\.id))
        do {
            let savedDevices = try await appState.connectionManager.fetchSavedDevices()
            let connectableDevices = savedDevices.filter {
                DeviceSelectionFilter.isConnectable($0, pairedAccessoryIDs: pairedAccessoryIDs)
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
            await appState.disconnect(reason: .switchingDevice)
            // Trigger ASK picker flow via AppState
            appState.startDeviceScan()
        }
    }

    /// Computes the signal tier with hysteresis to prevent flickering at boundaries.
    /// Requires crossing the threshold by 3 dBm before changing tiers.
    private func updatedSignalTier(currentTier: Int?, smoothedRSSI: Int) -> Int {
        let hysteresis = 3
        switch currentTier {
        case 2: // green — drop only if clearly below threshold
            return smoothedRSSI < -60 - hysteresis ? (smoothedRSSI < -80 - hysteresis ? 0 : 1) : 2
        case 1: // yellow — need margin to move up or down
            if smoothedRSSI >= -60 + hysteresis { return 2 }
            if smoothedRSSI < -80 - hysteresis { return 0 }
            return 1
        case 0: // red — need margin to move up
            return smoothedRSSI >= -80 + hysteresis ? (smoothedRSSI >= -60 + hysteresis ? 2 : 1) : 0
        default: // first reading, no hysteresis
            if smoothedRSSI >= -60 { return 2 }
            if smoothedRSSI >= -80 { return 1 }
            return 0
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
    let devices: [SelectableDevice]
    let devicesConnectedElsewhere: Set<UUID>
    let deviceSignalTier: [UUID: Int]
    let deviceRSSI: [UUID: (rssi: Int, lastSeen: Date)]
    @Binding var showingWiFiConnection: Bool
    @Binding var editingWiFiDevice: SelectableDevice?
    let onConnect: (SelectableDevice) -> Void
    let onDelete: (SelectableDevice) -> Void
    let onScanForNew: () -> Void

    var body: some View {
        List {
            Section {
                ForEach(devices) { device in
                    let tier = device.isWiFiOnly ? nil : deviceSignalTier[device.id]
                    let isDisabledByBLE = !device.isWiFiOnly && deviceRSSI[device.id] == nil
                    Button {
                        guard !isDisabledByBLE else { return }
                        onConnect(device)
                    } label: {
                        DeviceRow(
                            device: device,
                            isWiFiOnly: device.isWiFiOnly,
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
        }
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
    let isWiFiOnly: Bool
    let isConnectedElsewhere: Bool
    let signalTier: Int?

    private var isUnreachable: Bool {
        !isWiFiOnly && signalTier == nil
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
        if let method = device.primaryConnectionMethod {
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

            if signalTier != nil {
                Image(systemName: "cellularbars", variableValue: signalLevel)
                    .foregroundStyle(signalColor)
                    .font(.body)
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
        .accessibilityHint(isConnectedElsewhere
            ? L10n.Settings.DeviceSelection.Accessibility.connectedElsewhereHint
            : isUnreachable
            ? L10n.Settings.DeviceSelection.Accessibility.outOfRangeHint
            : L10n.Settings.DeviceSelection.Accessibility.selectHint)
    }

    // MARK: - Signal Tier Helpers

    private var signalLevel: Double {
        switch signalTier { case 2: 1.0; case 1: 0.66; default: 0.33 }
    }

    private var signalColor: Color {
        switch signalTier { case 2: .green; case 1: .yellow; default: .red }
    }
}
