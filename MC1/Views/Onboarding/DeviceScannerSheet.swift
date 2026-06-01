import SwiftUI
import MC1Services

/// In-app BLE device picker used on macOS "Designed for iPad", where AccessorySetupKit's
/// system picker is unavailable.
///
/// Hosted once from `ContentView` and presented whenever the platform pairing service
/// (`BluetoothScanPairingService`) requests discovery. It scans via
/// `ConnectionManager.startBLEScanning()` and resolves the service's pending discovery when
/// the user taps a device (`select`) or cancels (`cancel`). The selected UUID then flows
/// through the same `connect(to:)` ceremony as the AccessorySetupKit path on iOS.
struct DeviceScannerSheet: View {
    @Environment(\.appState) private var appState
    let picker: BluetoothScanPairingService

    @State private var tracker = RSSIScanTracker()

    /// Stable ordering: by name, then id. Avoids row reshuffling as RSSI fluctuates.
    private var sortedDevices: [DiscoveredDevice] {
        tracker.devices.values.sorted { lhs, rhs in
            let lName = lhs.name ?? ""
            let rName = rhs.name ?? ""
            if lName != rName { return lName.localizedCaseInsensitiveCompare(rName) == .orderedAscending }
            return lhs.id.uuidString < rhs.id.uuidString
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                switch appState.connectionManager.bluetoothAvailability {
                case .poweredOff:
                    bluetoothRemedy(
                        title: L10n.Onboarding.DeviceScanner.BluetoothOff.title,
                        message: L10n.Onboarding.DeviceScanner.BluetoothOff.message
                    )
                case .unauthorized:
                    bluetoothRemedy(
                        title: L10n.Onboarding.DeviceScanner.BluetoothUnauthorized.title,
                        message: L10n.Onboarding.DeviceScanner.BluetoothUnauthorized.message
                    )
                case .ready:
                    if sortedDevices.isEmpty {
                        scanningState
                    } else {
                        deviceList
                    }
                }
            }
            .navigationTitle(L10n.Onboarding.DeviceScanner.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Localizable.Common.cancel) {
                        picker.cancel()
                    }
                }
            }
            .task { await tracker.consume(appState.connectionManager.startBLEScanning()) }
        }
    }

    /// Scanning empty state with an inline spinner, so the picker reads as actively searching
    /// rather than stalled before the first peripheral resolves.
    private var scanningState: some View {
        ContentUnavailableView {
            Label(L10n.Onboarding.DeviceScanner.scanning,
                  systemImage: "antenna.radiowaves.left.and.right")
        } description: {
            ProgressView()
                .controlSize(.small)
        }
    }

    private var deviceList: some View {
        List(sortedDevices) { device in
            Button {
                picker.select(device.id)
            } label: {
                DeviceScannerRow(
                    name: device.name ?? L10n.Onboarding.DeviceScanner.unknownDevice,
                    signalTier: tracker.signalTier(for: device.id) ?? .weak
                )
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
    }

    /// Remedy state for the macOS scanner when Bluetooth is off or unauthorized: scanning cannot
    /// surface any peripheral, so the picker explains what to fix instead of spinning indefinitely.
    private func bluetoothRemedy(title: String, message: String) -> some View {
        ContentUnavailableView {
            Label(title, systemImage: "antenna.radiowaves.left.and.right.slash")
        } description: {
            Text(message)
        }
    }
}

// MARK: - Row

private struct DeviceScannerRow: View {
    let name: String
    let signalTier: RSSITuning.SignalTier

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.title2)
                .foregroundStyle(.green)
                .frame(width: 40, height: 40)
                .background(.green.opacity(0.1), in: .circle)
                .accessibilityHidden(true)

            Text(name)
                .font(.headline)

            Spacer()

            SignalBars(tier: signalTier, accessibilityLabel: signalDescription)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }

    /// VoiceOver descriptor for the signal glyph, so the only in-range/barely-reachable
    /// differentiator is announced rather than silent (the combined element otherwise reads
    /// the device name alone).
    private var signalDescription: String {
        SignalBars.accessibilityDescription(forTier: signalTier)
    }
}
