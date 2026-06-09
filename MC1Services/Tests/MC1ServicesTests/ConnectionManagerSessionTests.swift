import Foundation
import Testing
@testable import MC1Services

@Suite("ConnectionManager Session Tests")
@MainActor
struct ConnectionManagerSessionTests {

    // MARK: - setConnectionState Tests

    @Test("setConnectionState updates to connected")
    func setConnectionStateConnected() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        manager.setTestState(connectionIntent: .wantsConnection())

        manager.setConnectionState(.connected)

        #expect(manager.connectionState == .connected)
    }

    @Test("setConnectionState updates to disconnected")
    func setConnectionStateDisconnected() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        manager.setTestState(connectionState: .connected, connectionIntent: .wantsConnection())

        manager.setConnectionState(.disconnected)

        #expect(manager.connectionState == .disconnected)
    }

    // MARK: - setConnectedDevice Tests

    @Test("setConnectedDevice sets device")
    func setConnectedDeviceSets() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        let device = DeviceDTO.testDevice(nodeName: "TestDevice")

        manager.setConnectedDevice(device)

        #expect(manager.connectedDevice?.nodeName == "TestDevice")
    }

    @Test("setConnectedDevice sets nil")
    func setConnectedDeviceSetsNil() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        let device = DeviceDTO.testDevice()
        manager.setConnectedDevice(device)
        #expect(manager.connectedDevice != nil)

        manager.setConnectedDevice(nil)

        #expect(manager.connectedDevice == nil)
    }

    // MARK: - isTransportAutoReconnecting Tests

    @Test("isTransportAutoReconnecting delegates to stateMachine")
    func isTransportAutoReconnectingDelegates() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        await mock.setStubbedIsAutoReconnecting(false)
        var result = await manager.isTransportAutoReconnecting()
        #expect(!result)

        await mock.setStubbedIsAutoReconnecting(true)
        result = await manager.isTransportAutoReconnecting()
        #expect(result)
    }

    @Test("activeConnectionAttemptDeviceID prefers session rebuild device")
    func activeConnectionAttemptDeviceIDPrefersSessionRebuildDevice() throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        manager.setTestState(sessionRebuildDeviceID: deviceID)

        #expect(manager.activeConnectionAttemptDeviceID == deviceID)
        #expect(manager.activeReconnectDeviceID == deviceID)
    }

    @Test("connect to same device returns early while session rebuild is in progress")
    func connectToSameDeviceReturnsEarlyDuringSessionRebuild() async throws {
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

    @Test("handleReconnectionFailure clears state and sets disconnected")
    func handleReconnectionFailureClearsState() async throws {
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

    @Test("checkWiFiConnectionHealth returns early when reconnect in progress")
    func wifiHealthCheckReturnsEarlyWhenReconnecting() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .wifi,
            connectionIntent: .wantsConnection()
        )

        manager.wifiReconnectTask = Task { }

        await manager.checkWiFiConnectionHealth()

        #expect(manager.connectionState == .ready)

        manager.wifiReconnectTask?.cancel()
        manager.wifiReconnectTask = nil
    }

    @Test("checkWiFiConnectionHealth returns early when disconnected without intent")
    func wifiHealthCheckReturnsEarlyWhenNoIntent() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: nil,
            connectionIntent: ConnectionIntent.none
        )

        await manager.checkWiFiConnectionHealth()

        #expect(manager.connectionState == .disconnected)
    }

    @Test("checkWiFiConnectionHealth returns early when transport is bluetooth")
    func wifiHealthCheckReturnsEarlyWhenBluetooth() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()
        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        await manager.checkWiFiConnectionHealth()

        #expect(manager.connectionState == .ready)
    }

    @Test("handleReconnectionFailure notifies onConnectionLost so UI can react")
    func handleReconnectionFailureNotifiesConnectionLost() async throws {
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
