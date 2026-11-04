import Foundation
@testable import MC1Services
import Testing

@Suite("ConnectionManager Disconnect Diagnostics Tests")
@MainActor
struct ConnectionManagerDisconnectDiagnosticsTests {
  private let defaults: UserDefaults

  init() {
    defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }

  @Test
  func `auto-reconnect entry persists disconnect diagnostic with error info`() async throws {
    let env = try ConnectionManager.createForPairingTesting(defaults: defaults)
    defer { env.cleanup() }
    let manager = env.manager
    let deviceID = UUID()
    manager.setTestState(
      connectionState: .ready,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    _ = manager.seedBLERecoveryRequest(deviceID: deviceID)
    try await waitUntil("auto-reconnect handler should be installed") {
      await env.transport.hasLifecycleHandler
    }

    await env.transport.simulateAutoReconnecting(
      deviceID: deviceID,
      errorInfo: "domain=CBErrorDomain, code=15, desc=Failed to encrypt"
    )

    try await waitUntil("connectionState should transition to .connecting and diagnostic should be persisted") {
      manager.connectionState == .connecting
        && (manager.lastDisconnectDiagnostic ?? "")
        .localizedStandardContains("source=ble.autoReconnecting")
    }

    let diagnostic = manager.lastDisconnectDiagnostic ?? ""
    #expect(diagnostic.localizedStandardContains("code=15"))
    #expect(manager.connectionState == .connecting)
  }

  @Test
  func `auto-reconnect entry skips the claim but tears down the stale OLD session during pairing`() async throws {
    let env = try ConnectionManager.createForPairingTesting(defaults: defaults)
    defer { env.cleanup() }
    let manager = env.manager
    let oldDeviceID = UUID()

    manager.setTestState(
      connectionState: .ready,
      connectedDevice: DeviceDTO.testDevice(id: oldDeviceID),
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection(),
      isPairingInProgress: true
    )

    _ = manager.seedBLERecoveryRequest(deviceID: oldDeviceID)
    try await waitUntil("auto-reconnect handler should be installed") {
      await env.transport.hasLifecycleHandler
    }

    await env.transport.simulateAutoReconnecting(deviceID: oldDeviceID)

    try await waitUntil("suppression branch should tear down OLD session") {
      manager.connectionState == .disconnected && manager.connectedDevice == nil
    }

    #expect(manager.reconnectionCoordinator.reconnectingDeviceID == nil, "Claim must remain unset so pairing's connect(to:) is not preempted")
    #expect(manager.connectionState == .disconnected, "OLD session is dead — UI must not stay on .ready")
    #expect(manager.connectedDevice == nil, "Stale connectedDevice must be cleared so message sends fail loudly instead of silently")

    let diagnostic = manager.lastDisconnectDiagnostic ?? ""
    #expect(diagnostic.localizedStandardContains("source=ble.autoReconnecting"))
  }

  @Test
  func `health check preserves intent and persists diagnostic when other app is connected`() async throws {
    let (manager, mock) = try ConnectionManager.createForTesting(defaults: defaults)
    let deviceID = UUID()

    await mock.setStubbedIsConnected(false)
    await mock.setStubbedIsAutoReconnecting(false)
    await mock.setStubbedIsDeviceConnectedToSystem(true)

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    manager.testLastConnectedDeviceID = deviceID

    await manager.checkBLEConnectionHealth()

    let diagnostic = manager.lastDisconnectDiagnostic ?? ""
    #expect(
      diagnostic.localizedStandardContains("source=ble.otherAppConnected")
    )
    #expect(manager.connectionIntent.wantsConnection)
    #expect(manager.isReconnectionWatchdogRunning)

    await manager.appDidEnterBackground()
  }

  @Test
  func `health check adopts system-connected last device when adoption can start`() async throws {
    let (manager, mock) = try ConnectionManager.createForTesting(defaults: defaults)
    let deviceID = UUID()

    await mock.setStubbedIsConnected(false)
    await mock.setStubbedIsAutoReconnecting(false)
    await mock.setStubbedIsBluetoothPoweredOff(false)
    await mock.setStubbedIsDeviceConnectedToSystem(true)
    await mock.setStubbedDidStartAdoptingSystemConnectedPeripheral(true)

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    manager.testLastConnectedDeviceID = deviceID

    await manager.checkBLEConnectionHealth()

    let calls = await mock.startAdoptingSystemConnectedPeripheralCalls
    #expect(calls == [deviceID])

    let diagnostic = manager.lastDisconnectDiagnostic ?? ""
    #expect(
      diagnostic.localizedStandardContains("source=ble.adoptSystemConnectedPeripheral")
    )
    #expect(manager.connectionState == .connecting)
    #expect(manager.connectionIntent.wantsConnection)
  }

  @Test
  func `manual connect adopts system-connected last device instead of throwing deviceConnectedToOtherApp`() async throws {
    let (manager, mock) = try ConnectionManager.createForTesting(defaults: defaults)
    let deviceID = UUID()

    await mock.setStubbedIsConnected(false)
    await mock.setStubbedIsAutoReconnecting(false)
    await mock.setStubbedIsBluetoothPoweredOff(false)
    await mock.setStubbedIsDeviceConnectedToSystem(true)
    await mock.setStubbedDidStartAdoptingSystemConnectedPeripheral(true)

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: ConnectionIntent.none
    )
    manager.testLastConnectedDeviceID = deviceID

    try await manager.connect(to: deviceID, forceFullSync: true, forceReconnect: true)

    let calls = await mock.startAdoptingSystemConnectedPeripheralCalls
    #expect(calls == [deviceID])

    let diagnostic = manager.lastDisconnectDiagnostic ?? ""
    #expect(diagnostic.localizedStandardContains("source=ble.adoptSystemConnectedPeripheral"))
    #expect(manager.connectionState == .connecting)
    #expect(manager.connectionIntent.wantsConnection)
  }
}
