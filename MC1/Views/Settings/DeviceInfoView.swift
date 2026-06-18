import SwiftUI
import MC1Services
import MeshCore
import OSLog

private let deviceInfoLogger = Logger(subsystem: "com.mc1", category: "DeviceInfoView")

/// Detailed device information screen
struct DeviceInfoView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var showShareSheet = false

    @State private var nodeName: String = ""
    @State private var isEditingName = false
    @State private var errorMessage: String?
    @State private var retryAlert = RetryAlertState()
    @State private var isSaving = false

    var body: some View {
        List {
            if let device = appState.connectedDevice {
                // Device identity
                Section {
                    DeviceIdentityHeader(device: device)
                } header: {
                    Text(L10n.Settings.Device.header)
                }
                .themedRowBackground(theme)

                // Node settings (name, public key, share)
                Section {
                    HStack {
                        TintedLabel(L10n.Settings.Node.name, systemImage: "person.text.rectangle")
                        Spacer()
                        Button(device.nodeName) {
                            nodeName = device.nodeName
                            isEditingName = true
                        }
                        .foregroundStyle(.secondary)
                    }
                    .radioDisabled(for: appState.connectionState, or: isSaving)

                    NavigationLink(value: SettingsSubpage.publicKey(device.publicKey)) {
                        Label(L10n.Settings.DeviceInfo.publicKey, systemImage: "key")
                    }

                    Button {
                        showShareSheet = true
                    } label: {
                        Label(L10n.Settings.DeviceInfo.shareContact, systemImage: "square.and.arrow.up")
                    }
                } header: {
                    Text(L10n.Settings.Node.header)
                } footer: {
                    Text(L10n.Settings.Node.footer)
                }
                .alert(L10n.Settings.Node.Alert.EditName.title, isPresented: $isEditingName) {
                    TextField(L10n.Settings.Node.name, text: $nodeName)
                        .onChange(of: nodeName) { _, newValue in
                            if newValue.utf8.count > ProtocolLimits.maxUsableNameBytes {
                                nodeName = newValue.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)
                            }
                        }
                    Button(L10n.Localizable.Common.cancel, role: .cancel) { }
                    Button(L10n.Localizable.Common.save) {
                        saveNodeName()
                    }
                }
                .themedRowBackground(theme)

                // Connection status
                Section {
                    HStack {
                        Label(
                            L10n.Settings.DeviceInfo.Connection.status,
                            systemImage: "antenna.radiowaves.left.and.right"
                        )
                        Spacer()
                        HStack(spacing: 4) {
                            Circle()
                                .fill(.green)
                                .frame(width: 8, height: 8)
                            Text(L10n.Settings.Device.connected)
                                .foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text(L10n.Settings.DeviceInfo.Connection.header)
                }
                .themedRowBackground(theme)

                // Battery and storage
                Section {
                    if let battery = appState.batteryMonitor.deviceBattery {
                        let ocvArray = appState.batteryMonitor.activeBatteryOCVArray(for: appState.connectedDevice)
                        HStack {
                            Label(
                            L10n.Settings.DeviceInfo.battery,
                            systemImage: battery.iconName(using: ocvArray)
                        )
                                .symbolRenderingMode(.multicolor)
                            Spacer()
                            Text(battery.percentage(using: ocvArray), format: .percent)
                                .foregroundStyle(battery.levelColor(using: ocvArray))
                            Text("(\(formatVoltage(battery.voltage)))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        HStack {
                            Label(L10n.Settings.DeviceInfo.storageUsed, systemImage: "internaldrive")
                            Spacer()
                            Text(formatStorage(used: battery.usedStorageKB ?? 0, total: battery.totalStorageKB ?? 0))
                                .foregroundStyle(.secondary)
                        }

                        StorageBar(used: battery.usedStorageKB ?? 0, total: battery.totalStorageKB ?? 0)
                    } else {
                        HStack {
                            Label(L10n.Settings.DeviceInfo.batteryAndStorage, systemImage: "battery.100")
                            Spacer()
                            ProgressView()
                        }
                    }
                } header: {
                    Text(L10n.Settings.DeviceInfo.PowerStorage.header)
                }
                .themedRowBackground(theme)

                // Firmware info
                Section {
                    HStack {
                        Label(L10n.Settings.DeviceInfo.firmwareVersion, systemImage: "memorychip")
                        Spacer()
                        Text(
                            device.firmwareVersionString.isEmpty
                                ? L10n.Settings.DeviceInfo.firmwareVersionFormat(device.firmwareVersion)
                                : device.firmwareVersionString
                        )
                            .foregroundStyle(.secondary)
                    }

                    HStack {
                        Label(L10n.Settings.DeviceInfo.buildDate, systemImage: "calendar")
                        Spacer()
                        Text(
                            device.buildDate.isEmpty
                                ? L10n.Settings.DeviceInfo.unknown
                                : device.buildDate
                        )
                            .foregroundStyle(.secondary)
                    }

                    HStack {
                        Label(L10n.Settings.DeviceInfo.manufacturer, systemImage: "building.2")
                        Spacer()
                        Text(
                            device.manufacturerName.isEmpty
                                ? L10n.Settings.DeviceInfo.unknown
                                : device.manufacturerName
                        )
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(L10n.Settings.DeviceInfo.Firmware.header)
                }
                .themedRowBackground(theme)

                // Capabilities
                Section {
                    HStack {
                        Label(L10n.Settings.DeviceInfo.maxNodes, systemImage: "person.2")
                        Spacer()
                        Text("\(device.maxContacts)")
                            .foregroundStyle(.secondary)
                    }

                    HStack {
                        Label(L10n.Settings.DeviceInfo.maxChannels, systemImage: "person.3")
                        Spacer()
                        Text("\(device.maxChannels)")
                            .foregroundStyle(.secondary)
                    }

                    HStack {
                        Label(L10n.Settings.DeviceInfo.maxTxPower, systemImage: "bolt")
                        Spacer()
                        Text(L10n.Settings.DeviceInfo.txPowerFormat(device.maxTxPower))
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(L10n.Settings.DeviceInfo.Capabilities.header)
                }
                .themedRowBackground(theme)

            } else {
                ContentUnavailableView(
                    L10n.Settings.DeviceInfo.NoDevice.title,
                    systemImage: "antenna.radiowaves.left.and.right.slash",
                    description: Text(L10n.Settings.DeviceInfo.NoDevice.description)
                )
            }
        }
        .themedCanvas(theme)
        .settingsSubpageDestinations()
        .navigationTitle(L10n.Settings.DeviceInfo.title)
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
        .refreshable {
            await appState.batteryMonitor.fetchDeviceBattery(services: appState.services, device: appState.connectedDevice)
        }
        .task {
            deviceInfoLogger.info("DeviceInfoView: appeared, connectedDevice=\(appState.connectedDevice != nil)")
            await appState.batteryMonitor.fetchDeviceBattery(services: appState.services, device: appState.connectedDevice)
        }
        .sheet(isPresented: $showShareSheet) {
            if let device = appState.connectedDevice {
                ContactQRShareSheet(
                    contactName: device.nodeName,
                    publicKey: device.publicKey,
                    contactType: .chat
                )
            }
        }
    }

    /// Firmware reports storage in binary kilobytes.
    private static let bytesPerKilobyte = 1024

    private func formatStorage(used: Int, total: Int) -> String {
        let style = ByteCountFormatStyle(style: .memory)
        let usedBytes = Int64(used) * Int64(Self.bytesPerKilobyte)
        let totalBytes = Int64(total) * Int64(Self.bytesPerKilobyte)
        return "\(usedBytes.formatted(style)) / \(totalBytes.formatted(style))"
    }

    private func formatVoltage(_ volts: Double) -> String {
        Measurement(value: volts, unit: UnitElectricPotentialDifference.volts)
            .formatted(.measurement(
                width: .abbreviated,
                usage: .asProvided,
                numberFormatStyle: .number.precision(.fractionLength(2))
            ))
    }

    private func saveNodeName() {
        let name = nodeName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty,
              let settingsService = appState.services?.settingsService else { return }

        isSaving = true
        Task {
            do {
                _ = try await settingsService.setNodeNameVerified(name)
                retryAlert.reset()
            } catch let error as SettingsServiceError where error.isRetryable {
                retryAlert.show(
                    message: error.userFacingMessage,
                    onRetry: { saveNodeName() },
                    onMaxRetriesExceeded: { dismiss() }
                )
            } catch {
                errorMessage = error.userFacingMessage
            }
            isSaving = false
        }
    }
}

// MARK: - Device Identity Header

private struct DeviceIdentityHeader: View {
    let device: DeviceDTO

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: "cpu.fill")
                .font(.system(size: 40))
                .foregroundStyle(.tint)
                .frame(width: 60, height: 60)
                .background(.tint.opacity(0.1), in: .circle)

            VStack(alignment: .leading, spacing: 4) {
                Text(device.nodeName)
                    .font(.title2)
                    .bold()

                Text(
                    device.manufacturerName.isEmpty
                        ? L10n.Settings.DeviceInfo.defaultManufacturer
                        : device.manufacturerName
                )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding(.vertical, 8)
    }
}

// MARK: - Storage Bar

private struct StorageBar: View {
    let used: Int
    let total: Int

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(.secondary.opacity(0.2))

                RoundedRectangle(cornerRadius: 4)
                    .fill(usageColor)
                    .frame(width: geometry.size.width * usageRatio)
            }
        }
        .frame(height: 8)
    }

    private var usageRatio: CGFloat {
        guard total > 0 else { return 0 }
        return CGFloat(used) / CGFloat(total)
    }

    private var usageColor: Color {
        switch usageRatio {
        case 0..<0.7: return .green
        case 0.7..<0.9: return .orange
        default: return .red
        }
    }
}

#Preview {
    NavigationStack {
        DeviceInfoView()
            .environment(\.appState, AppState())
    }
}
