import Foundation
@testable import MC1Services
import Testing

@Suite("ConnectionManager Session Tests")
@MainActor
struct ConnectionManagerSessionTests {
  // MARK: - setConnectionState Tests

  @Test
  func `setConnectionState updates to connected`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(connectionIntent: .wantsConnection())

    manager.setConnectionState(.connected)

    #expect(manager.connectionState == .connected)
  }

  @Test
  func `setConnectionState updates to disconnected`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(connectionState: .connected, connectionIntent: .wantsConnection())

    manager.setConnectionState(.disconnected)

    #expect(manager.connectionState == .disconnected)
  }

  // MARK: - setConnectedDevice Tests

  @Test
  func `setConnectedDevice sets device`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice(nodeName: "TestDevice")

    manager.setConnectedDevice(device)

    #expect(manager.connectedDevice?.nodeName == "TestDevice")
  }

  @Test
  func `setConnectedDevice sets nil`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice()
    manager.setConnectedDevice(device)
    #expect(manager.connectedDevice != nil)

    manager.setConnectedDevice(nil)

    #expect(manager.connectedDevice == nil)
  }

  // MARK: - isTransportAutoReconnecting Tests

  @Test
  func `isTransportAutoReconnecting delegates to stateMachine`() async throws {
    let (manager, mock) = try ConnectionManager.createForTesting()

    await mock.setStubbedIsAutoReconnecting(false)
    var result = await manager.isTransportAutoReconnecting()
    #expect(!result)

    await mock.setStubbedIsAutoReconnecting(true)
    result = await manager.isTransportAutoReconnecting()
    #expect(result)
  }

  @Test
  func `activeConnectionAttemptDeviceID prefers session rebuild device`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let deviceID = UUID()

    manager.setTestState(sessionRebuildDeviceID: deviceID)

    #expect(manager.activeConnectionAttemptDeviceID == deviceID)
    #expect(manager.activeReconnectDeviceID == deviceID)
  }

  @Test
  func `connect to same device returns early while session rebuild is in progress`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let deviceID = UUID()

    manager.testLastConnectedDeviceID = deviceID
    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection(),
      sessionRebuildDeviceID: deviceID
    )

    try await manager.connect(to: deviceID)

    #expect(manager.connectionState == .disconnected)
    #expect(manager.sessionRebuildDeviceID == deviceID)
    #expect(manager.connectionIntent == .wantsConnection())
    #expect(manager.reconnectionCoordinator.reconnectingDeviceID == nil)
  }

  // MARK: - handleReconnectionFailure Tests

  @Test
  func `handleReconnectionFailure clears state and sets disconnected`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.updateDevice(with: DeviceDTO.testDevice())
    manager.setTestState(
      connectionState: .connected,
      connectionIntent: ConnectionIntent.none
    )

    await manager.handleReconnectionFailure()

    #expect(manager.connectionState == .disconnected)
    #expect(manager.connectedDevice == nil)
    #expect(manager.allowedRepeatFreqRanges.isEmpty)
  }

  // MARK: - WiFi Health Check Early Returns

  @Test
  func `checkWiFiConnectionHealth returns early when reconnect in progress`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(
      connectionState: .ready,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )

    manager.wifiReconnectTask = Task {}

    await manager.checkWiFiConnectionHealth()

    #expect(manager.connectionState == .ready)

    manager.wifiReconnectTask?.cancel()
    manager.wifiReconnectTask = nil
  }

  @Test
  func `checkWiFiConnectionHealth returns early when disconnected without intent`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: nil,
      connectionIntent: ConnectionIntent.none
    )

    await manager.checkWiFiConnectionHealth()

    #expect(manager.connectionState == .disconnected)
  }

  @Test
  func `checkWiFiConnectionHealth returns early when transport is bluetooth`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.setTestState(
      connectionState: .ready,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await manager.checkWiFiConnectionHealth()

    #expect(manager.connectionState == .ready)
  }

  @Test
  func `handleReconnectionFailure notifies onConnectionLost so UI can react`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.updateDevice(with: DeviceDTO.testDevice())
    manager.setTestState(
      connectionState: .connected,
      connectionIntent: ConnectionIntent.none
    )

    let tracker = ReconnectionFailureLostTracker()
    manager.onConnectionLost = { await tracker.markConnectionLost() }

    await manager.handleReconnectionFailure()

    let wasCalled = await tracker.connectionLostCalled
    #expect(wasCalled, "onConnectionLost must fire so AppState can update the Live Activity to disconnected")
  }
}

// MARK: - Test Helpers

private actor ReconnectionFailureLostTracker {
  var connectionLostCalled = false

  func markConnectionLost() {
    connectionLostCalled = true
  }
}
