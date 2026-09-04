import MC1Services
import os
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "DeviceSelectionSheet")

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

  @State private var list = DeviceSelectionListBuilder.Result(connectable: [], needsSetup: [])
  @State private var showingWiFiConnection = false
  @State private var editingWiFiDevice: DeviceDTO?
  @State private var devicesConnectedElsewhere: Set<UUID> = []
  @State private var tracker = RSSIScanTracker()
  @State private var errorMessage: String?

  private var isListEmpty: Bool {
    list.connectable.isEmpty && list.needsSetup.isEmpty
  }

  var body: some View {
    NavigationStack {
      Group {
        if isListEmpty {
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
      .errorAlert($errorMessage)
    }
  }

  // MARK: - Subviews

  private func makeDeviceListView() -> some View {
    DeviceListView(
      connectable: list.connectable,
      needsSetup: list.needsSetup,
      devicesConnectedElsewhere: devicesConnectedElsewhere,
      tracker: tracker,
      showingWiFiConnection: $showingWiFiConnection,
      editingWiFiDevice: $editingWiFiDevice,
      onConnect: { connectToDevice($0) },
      onDelete: { deleteDevice($0) },
      onSetup: { setupAccessory($0) },
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
    let hasSystemPairingRegistry = appState.connectionManager.hasSystemPairingRegistry
    var needsSetup: [SystemPairedAccessory] = []
    if hasSystemPairingRegistry {
      do {
        let pending = try await appState.connectionManager.systemAccessoriesMissingDeviceRecord()
        needsSetup = pending.map { SystemPairedAccessory(id: $0.id, name: $0.name) }
      } catch {
        logger.error("Failed to query system pairing accessories: \(error.localizedDescription)")
      }
    }

    let accessories = appState.connectionManager.pairedAccessoryInfos
    let savedDevices: [DeviceDTO]
    do {
      savedDevices = try await appState.connectionManager.fetchSavedDevices()
    } catch {
      logger.error("Failed to load devices: \(error.localizedDescription)")
      savedDevices = []
    }

    let result = DeviceSelectionListBuilder.make(
      saved: savedDevices,
      accessories: accessories,
      needsSetup: needsSetup,
      hasSystemPairingRegistry: hasSystemPairingRegistry
    )
    list = result

    var connectedElsewhere: Set<UUID> = []
    for device in result.connectable {
      let hasBluetooth = device.connectionMethods.isEmpty ||
        device.connectionMethods.contains { !$0.isWiFi }
      if hasBluetooth {
        if await appState.connectionManager.isDeviceConnectedToOtherApp(device.id) {
          connectedElsewhere.insert(device.id)
        }
      }
    }
    devicesConnectedElsewhere = connectedElsewhere
  }

  private func scanForNewDevice() {
    appState.connectionUI.queuedSystemPairingSetup = nil
    appState.connectionUI.queuedDeviceScanAfterSelectionDismiss = true
    appState.connectionManager.isPairingFlowActive = true
    dismiss()
  }

  private func setupAccessory(_ accessory: SystemPairedAccessory) {
    appState.connectionUI.queuedDeviceScanAfterSelectionDismiss = false
    appState.connectionUI.queuedSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [accessory]
    )
    appState.connectionManager.isPairingFlowActive = true
    dismiss()
  }

  private func connectToDevice(_ device: DeviceDTO) {
    dismiss()
    Task {
      logger.info("[UI] User tapped Connect for device: \(device.id.uuidString.prefix(8)), name: \(device.nodeName)")
      do {
        if case let .wifi(host, port, _) = device.primaryConnectionMethod {
          try await appState.connectViaWiFi(host: host, port: port, forceFullSync: true)
        } else {
          try await appState.connectionManager.connect(to: device.id, forceFullSync: true, forceReconnect: true)
        }
      } catch {
        appState.connectionUI.presentSavedDeviceConnectFailure(deviceID: device.id, error: error)
      }
    }
  }

  private func deleteDevice(_ device: DeviceDTO) {
    Task {
      do {
        try await appState.connectionManager.deleteDevice(id: device.id)
        await loadDevices()
      } catch DevicePairingError.cancelled {
      } catch {
        errorMessage = error.userFacingMessage
      }
    }
  }
}

// MARK: - Device List View

private struct DeviceListView: View {
  @Environment(\.appTheme) private var theme
  let connectable: [DeviceDTO]
  let needsSetup: [SystemPairedAccessory]
  let devicesConnectedElsewhere: Set<UUID>
  let tracker: RSSIScanTracker
  @Binding var showingWiFiConnection: Bool
  @Binding var editingWiFiDevice: DeviceDTO?
  let onConnect: (DeviceDTO) -> Void
  let onDelete: (DeviceDTO) -> Void
  let onSetup: (SystemPairedAccessory) -> Void
  let onScanForNew: () -> Void

  var body: some View {
    List {
      Section {
        ForEach(connectable) { device in
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

        ForEach(needsSetup) { accessory in
          Button {
            onSetup(accessory)
          } label: {
            NeedsSetupDeviceRow(accessory: accessory)
              .contentShape(.rect)
          }
          .buttonStyle(.plain)
        }
      } header: {
        Text(L10n.Settings.DeviceSelection.previouslyPaired)
      } footer: {
        if !needsSetup.isEmpty {
          Text(needsSetup.count == 1
            ? L10n.Settings.DeviceSelection.setupFooter
            : L10n.Settings.DeviceSelection.setupFooterPlural)
        }
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
      if case let .wifi(host, port, _) = device.primaryConnectionMethod {
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

// MARK: - Needs Setup Row

private struct NeedsSetupDeviceRow: View {
  let accessory: SystemPairedAccessory

  private var presentation: DeviceSelectionListBuilder.NeedsSetupRowPresentation {
    DeviceSelectionListBuilder.needsSetupPresentation(name: accessory.name)
  }

  var body: some View {
    HStack(spacing: 12) {
      Image(systemName: "antenna.radiowaves.left.and.right")
        .font(.title2)
        .foregroundStyle(.green)
        .frame(width: 40, height: 40)
        .background(Color.green.opacity(0.1), in: .circle)

      Text(accessory.name)
        .font(.headline)

      Spacer()

      Text(presentation.trailingTitle)
        .font(.body)
        .foregroundStyle(.blue)
    }
    .padding(.vertical, 4)
    .contentShape(.rect)
    .accessibilityElement(children: .combine)
    .accessibilityAddTraits(.isButton)
    .accessibilityLabel(presentation.accessibilityLabel)
    .accessibilityHint(presentation.accessibilityHint)
  }
}

// MARK: - Device Row

private struct DeviceRow: View {
  let device: DeviceDTO
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
        Text(device.nodeName)
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
      ? L10n.Settings.DeviceSelection.Accessibility.connectedElsewhereLabel(device.nodeName)
      : L10n.Settings.DeviceSelection.Accessibility.deviceLabel(device.nodeName, connectionDescription))
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

private extension DeviceDTO {
  var primaryConnectionMethod: ConnectionMethod? {
    connectionMethods.first { $0.isWiFi } ?? connectionMethods.first
  }

  var connectsViaWiFi: Bool {
    primaryConnectionMethod?.isWiFi == true
  }
}
