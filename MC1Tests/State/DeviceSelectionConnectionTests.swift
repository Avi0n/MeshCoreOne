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
    let harness = try makeHarness()
    defer { harness.cleanup() }
    harness.appState.connectionManager.testWiFiConnectHandler = { _, _ in
      throw BLEError.connectionTimeout
    }
    harness.appState.connectionUI.queueSavedDeviceConnection(
      makeDevice(),
      target: .wifi(host: "radio.local", port: 4242)
    )
    #expect(harness.appState.connectionManager.testWiFiConnectAttempts.isEmpty)

    harness.appState.handleDeviceSelectionSheetDismissed()
    try await waitUntil("wifi should be attempted after dismissal") {
      harness.appState.connectionUI.showingConnectionFailedAlert
    }

    let attempts = harness.appState.connectionManager.testWiFiConnectAttempts
    #expect(attempts.count == 1)
    #expect(attempts.first?.host == "radio.local")
    #expect(attempts.first?.port == 4242)
  }

  @Test
  func `choosing bluetooth while the same radio is connected over wifi starts a transport switch`() async throws {
    let transport = MockMeshTransport()
    await transport.setConnectError(BLEError.authenticationFailed)
    let harness = try makeHarness(transport: transport)
    defer { harness.cleanup() }
    var device = makeDevice()
    let peripheralID = try #require(device.bluetoothPeripheralID)
    device.id = peripheralID
    device.connectionMethods.reverse()
    harness.appState.connectionManager.setTestState(
      connectionState: .ready,
      connectedDevice: device,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )
    harness.appState.connectionManager.wifiTransport = WiFiTransport()
    harness.appState.connectionUI.queueSavedDeviceConnection(device, target: .bluetooth(peripheralID: peripheralID))
    #expect(await transport.connectInvocations.isEmpty)
    #expect(!harness.appState.connectionUI.showingConnectionFailedAlert)

    harness.appState.handleDeviceSelectionSheetDismissed()
    try await waitUntil(timeout: .seconds(2), "the selected Bluetooth transport should be dialed") {
      harness.appState.connectionUI.showingConnectionFailedAlert
    }
    harness.appState.handleDeviceSelectionSheetDismissed()

    #expect(await transport.connectInvocations.compactMap(\.deviceID) == [peripheralID])
    #expect(harness.appState.connectionUI.queuedSavedDeviceConnection == nil)
    #expect(harness.appState.connectionManager.wifiTransport == nil)
    #expect(harness.appState.connectedDevice == nil)
    #expect(harness.appState.connectionManager.connectionIntent == .wantsConnection(forceFullSync: true))
    #expect(harness.appState.connectionUI.failedPairingDeviceID == device.id)
    #expect(harness.appState.connectionUI.pairingFailureKind == .authentication)
  }

  private struct Harness {
    let appState: AppState
    let cleanup: () -> Void
  }

  private func makeHarness(transport: MockMeshTransport? = nil) throws -> Harness {
    let suite = "test.deviceselection.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let container = try PersistenceStore.createContainer(inMemory: true)
    let mockASK = MockAccessorySetupKitService()
    mockASK.isSessionActive = false
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      transport: transport,
      pairing: AccessorySetupPairingService(accessorySetupKit: mockASK)
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
