import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Device selection connection handoff")
@MainActor
struct DeviceSelectionConnectionTests {
  @Test
  func `cancel or swipe dismissal without a queued choice never connects`() throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }

    harness.appState.handleDeviceSelectionSheetDismissed(connect: { _ in
      Issue.record("Dismissal without a choice must not connect")
    })

    #expect(harness.appState.connectionUI.queuedSavedDeviceConnection == nil)
    #expect(harness.appState.connectionState == .disconnected)
    #expect(!harness.appState.connectionUI.showingConnectionFailedAlert)
  }

  @Test
  func `the default dismissal handler dials the captured wifi endpoint`() async throws {
    let wifi = ConnectionTestRadioTransport()
    await wifi.setConnectError(BLEError.connectionTimeout)
    let harness = try makeHarness(wifi: wifi)
    defer { harness.cleanup() }
    harness.appState.connectionUI.queueSavedDeviceConnection(
      makeDevice(),
      target: .wifi(host: "radio.local", port: 4242)
    )
    #expect(await wifi.connectCount == 0)

    harness.appState.handleDeviceSelectionSheetDismissed()
    try await waitUntil("wifi should be attempted after dismissal") {
      harness.appState.connectionUI.showingConnectionFailedAlert
    }

    #expect(await wifi.connectCount == 1)
    let endpoint = await wifi.connectionInfo
    #expect(endpoint?.host == "radio.local")
    #expect(endpoint?.port == 4242)
  }

  @Test
  func `choosing bluetooth while the same radio is connected over wifi starts a transport switch`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(live: .wifi, wifiHost: "radio.local", wifiPort: 4242) { harness in
      await harness.bluetooth.setConnectError(BLEError.authenticationFailed)
      let device = harness.device
      let peripheralID = try #require(device.bluetoothPeripheralID)
      harness.appState.connectionUI.queueSavedDeviceConnection(device, target: .bluetooth(peripheralID: peripheralID))
      #expect(await harness.bluetooth.connectCount == harness.initialBluetoothAttempts)
      #expect(!harness.appState.connectionUI.showingConnectionFailedAlert)

      harness.appState.handleDeviceSelectionSheetDismissed()
      try await waitUntil("the selected Bluetooth transport should be dialed") {
        harness.appState.connectionUI.showingConnectionFailedAlert
      }
      harness.appState.handleDeviceSelectionSheetDismissed()

      #expect(await harness.bluetooth.connectCount == harness.initialBluetoothAttempts + 1)
      #expect(await harness.bluetooth.sessionDeviceIDs.last == peripheralID)
      #expect(harness.appState.connectionUI.queuedSavedDeviceConnection == nil)
      #expect(harness.manager.wifiTransport == nil)
      #expect(harness.appState.connectedDevice == nil)
      #expect(harness.manager.connectionIntent == .wantsConnection(forceFullSync: true))
      #expect(harness.appState.connectionUI.failedPairingDeviceID == device.id)
      #expect(harness.appState.connectionUI.pairingFailureKind == .authentication)
    }
  }

  private struct Harness {
    let appState: AppState
    let cleanup: () -> Void
  }

  private func makeHarness(wifi: ConnectionTestRadioTransport = ConnectionTestRadioTransport()) throws -> Harness {
    let suite = "test.deviceselection.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let container = try PersistenceStore.createContainer(inMemory: true)
    let mockASK = MockAccessorySetupKitService()
    mockASK.isSessionActive = false
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      stateMachine: MockBLEStateMachine(),
      transport: MockMeshTransport(),
      pairing: AccessorySetupPairingService(accessorySetupKit: mockASK),
      makeWiFiTransport: { wifi }
    )
    let appState = AppState(
      modelContainer: container,
      isPlaceholder: true,
      defaults: defaults,
      injectedConnectionManager: manager
    )
    return Harness(appState: appState, cleanup: {
      appState.shutdown()
      defaults.removePersistentDomain(forName: suite)
    })
  }

  private func makeDevice() -> DeviceDTO {
    let device = Device(
      id: UUID(),
      publicKey: Data(repeating: 0x01, count: ProtocolLimits.publicKeySize),
      nodeName: "Radio"
    )
    device.connectionMethods = [
      .wifi(host: "radio.local", port: 4242, displayName: nil),
      .bluetooth(peripheralUUID: UUID(), displayName: nil)
    ]
    return DeviceDTO(from: device)
  }
}
