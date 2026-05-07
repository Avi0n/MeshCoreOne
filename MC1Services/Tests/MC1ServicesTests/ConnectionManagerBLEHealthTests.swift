import Foundation
import Testing
@testable import MC1Services

/// Tests for ConnectionManager.checkBLEConnectionHealth() - stale connection state detection.
/// Verifies the fix from commit b2ab8f17 that detects when connectionState is stale after
/// iOS terminates BLE connection while app is suspended.
@Suite("ConnectionManager BLE Health Check Tests")
@MainActor
struct ConnectionManagerBLEHealthTests {

    // MARK: - Test Helpers

    private func makeServices(
        deviceID: UUID,
        startEventMonitoring: Bool = false
    ) async throws -> (MeshCoreSession, ServiceContainer, DeviceDTO) {
        let session = MeshCoreSession(transport: SimulatorMockTransport())
        let services = try await ServiceContainer.forTesting(session: session)
        let device = DeviceDTO.testDevice(id: deviceID, radioID: deviceID)
        try await services.dataStore.saveDevice(device)
        if startEventMonitoring {
            await services.startEventMonitoring(radioID: device.radioID)
        }
        return (session, services, device)
    }

    // MARK: - Early Return Tests

    @Test("returns early when transport type is WiFi")
    func returnsEarlyForWiFiTransport() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .wifi,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = UUID()

        await manager.checkBLEConnectionHealth()

        // Should not change state since WiFi transport is handled elsewhere
        #expect(manager.connectionState == .ready)
    }

    @Test("returns early when shouldBeConnected is false")
    func returnsEarlyWhenNotExpectingConnection() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .bluetooth,
            connectionIntent: .none
        )
        manager.testLastConnectedDeviceID = UUID()

        await manager.checkBLEConnectionHealth()

        // Should not trigger cleanup since user doesn't expect to be connected
        #expect(manager.connectionState == .ready)
    }

    @Test("returns early when no lastConnectedDeviceID")
    func returnsEarlyWhenNoLastDevice() async throws {
        let (manager, _) = try ConnectionManager.createForTesting()

        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        // testLastConnectedDeviceID is nil by default

        await manager.checkBLEConnectionHealth()

        // Should not trigger cleanup without a device to reconnect to
        #expect(manager.connectionState == .ready)
    }

    @Test("returns early when BLE is connected and app stack is healthy")
    func returnsEarlyWhenBLEConnectedAndAppStackHealthy() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()
        let (session, services, device) = try await makeServices(deviceID: deviceID, startEventMonitoring: true)
        let tracker = SessionRebuildTracker()

        await mock.setStubbedIsConnected(true)
        await mock.setStubbedConnectedDeviceID(deviceID)
        manager.rebuildSessionForHealthCheckOverride = { deviceID in
            await tracker.record(deviceID)
        }

        manager.setTestState(
            connectionState: .ready,
            services: services,
            session: session,
            connectedDevice: device,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        #expect(manager.connectionState == .ready)
        #expect(services.isEventMonitoringActive)
        #expect(await services.messagePollingService.isAutoFetching)
        #expect(await tracker.recordedCalls().isEmpty)
    }

    @Test("skips reconnect during iOS auto-reconnect")
    func skipsWhenAutoReconnecting() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(true)

        manager.setTestState(
            connectionState: .connecting,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = UUID()

        await manager.checkBLEConnectionHealth()

        // Should not interfere with iOS auto-reconnect
        #expect(manager.connectionState == .connecting)
    }

    @Test("skips foreground reconnect while pairing is in progress")
    func skipsWhenPairingInProgress() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)

        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection(),
            isPairingInProgress: true
        )
        manager.testLastConnectedDeviceID = UUID()

        await manager.checkBLEConnectionHealth()

        // Gate fires before connect(to:) — connectionState stays .disconnected
        #expect(manager.connectionState == .disconnected)
    }

    @Test("skips reconnect while session rebuild is in progress")
    func skipsWhenSessionRebuildInProgress() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)

        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection(),
            sessionRebuildDeviceID: deviceID
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        #expect(manager.connectionState == .disconnected)
        #expect(manager.sessionRebuildDeviceID == deviceID)
    }

    // MARK: - Connected Transport App-Stack Reconciliation Tests

    @Test("BLE connected with missing session and services rebuilds app stack")
    func bleConnectedMissingAppStackRebuildsSession() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()
        let tracker = SessionRebuildTracker()

        await mock.setStubbedIsConnected(true)
        await mock.setStubbedConnectedDeviceID(deviceID)
        await mock.setStubbedIsAutoReconnecting(false)
        manager.rebuildSessionForHealthCheckOverride = { deviceID in
            await tracker.record(deviceID)
        }

        manager.setTestState(
            connectionState: .disconnected,
            services: nil,
            session: nil,
            connectedDevice: nil,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        #expect(await tracker.recordedCalls() == [deviceID])
    }

    @Test("BLE connected ready state restarts missing event monitoring")
    func bleConnectedReadyRestartsMissingEventMonitoring() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()
        let (session, services, device) = try await makeServices(deviceID: deviceID)

        await mock.setStubbedIsConnected(true)
        await mock.setStubbedConnectedDeviceID(deviceID)
        await mock.setStubbedIsAutoReconnecting(false)
        manager.setTestState(
            connectionState: .ready,
            services: services,
            session: session,
            connectedDevice: device,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        #expect(!services.isEventMonitoringActive)
        #expect(!(await services.messagePollingService.isAutoFetching))

        await manager.checkBLEConnectionHealth()

        #expect(services.isEventMonitoringActive)
        #expect(await services.messagePollingService.isAutoFetching)
    }

    @Test("BLE connected with healthy listeners does not rebuild")
    func bleConnectedHealthyListenersDoesNotRebuild() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()
        let (session, services, device) = try await makeServices(deviceID: deviceID, startEventMonitoring: true)
        let tracker = SessionRebuildTracker()

        await mock.setStubbedIsConnected(true)
        await mock.setStubbedConnectedDeviceID(deviceID)
        await mock.setStubbedIsAutoReconnecting(false)
        manager.rebuildSessionForHealthCheckOverride = { deviceID in
            await tracker.record(deviceID)
        }
        manager.setTestState(
            connectionState: .ready,
            services: services,
            session: session,
            connectedDevice: device,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        #expect(await tracker.recordedCalls().isEmpty)
        #expect(services.isEventMonitoringActive)
        #expect(await services.messagePollingService.isAutoFetching)
    }

    @Test("BLE connected skips duplicate recovery while session rebuild is active")
    func bleConnectedSkipsDuplicateRecoveryDuringSessionRebuild() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()
        let tracker = SessionRebuildTracker()

        await mock.setStubbedIsConnected(true)
        await mock.setStubbedConnectedDeviceID(deviceID)
        await mock.setStubbedIsAutoReconnecting(false)
        manager.rebuildSessionForHealthCheckOverride = { deviceID in
            await tracker.record(deviceID)
        }
        manager.setTestState(
            connectionState: .disconnected,
            services: nil,
            session: nil,
            connectedDevice: nil,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection(),
            sessionRebuildDeviceID: deviceID
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        #expect(await tracker.recordedCalls().isEmpty)
        #expect(manager.sessionRebuildDeviceID == deviceID)
    }

    @Test("health check does not attempt adoption while BLE restoration is already in progress")
    func healthCheckSkipsAdoptionDuringRestoration() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)
        await mock.setStubbedCurrentPhaseName("restoringState")
        await mock.setStubbedIsDeviceConnectedToSystem(true)

        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        let adoptionCalls = await mock.startAdoptingSystemConnectedPeripheralCalls
        #expect(adoptionCalls.isEmpty)
    }

    @Test("health check skips reconnection when Bluetooth is powered off")
    func healthCheckSkipsReconnectionWhenPoweredOff() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        await mock.setStubbedIsBluetoothPoweredOff(true)
        await mock.setStubbedIsAutoReconnecting(false)
        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = UUID()

        await manager.checkBLEConnectionHealth()

        // Should remain disconnected without attempting reconnection when BT is off
        #expect(manager.connectionState == .disconnected)
    }

    // MARK: - Stale State Detection Tests (Key Fix from b2ab8f17)

    @Test("detects stale state when connectionState is .ready but BLE disconnected")
    func detectsStaleReadyState() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        // BLE is actually disconnected
        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)
        await mock.setStubbedIsDeviceConnectedToSystem(false)

        // But connectionState thinks we're ready (stale state)
        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        // After detecting stale state and cleanup, connectionState should be .disconnected
        #expect(manager.connectionState == .disconnected)
    }

    @Test("detects stale state when connectionState is .connected but BLE disconnected")
    func detectsStaleConnectedState() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        // BLE is actually disconnected
        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)
        await mock.setStubbedIsDeviceConnectedToSystem(false)

        // But connectionState thinks we're connected (stale state)
        manager.setTestState(
            connectionState: .connected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        // After detecting stale state and cleanup, connectionState should be .disconnected
        #expect(manager.connectionState == .disconnected)
    }

    @Test("detects stale state when connectionState is .syncing but BLE disconnected")
    func detectsStaleSyncingState() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)
        await mock.setStubbedIsDeviceConnectedToSystem(false)

        manager.setTestState(
            connectionState: .syncing,
            services: try await ServiceContainer.forTesting(
                session: MeshCoreSession(transport: SimulatorMockTransport())
            ),
            session: MeshCoreSession(transport: SimulatorMockTransport()),
            connectedDevice: DeviceDTO.testDevice(id: deviceID),
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        #expect(manager.connectionState == .disconnected)
    }

    @Test("does not trigger cleanup when already disconnected")
    func noCleanupWhenAlreadyDisconnected() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)
        await mock.setStubbedIsDeviceConnectedToSystem(false)

        // Already in disconnected state (not stale, expected state)
        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        await manager.checkBLEConnectionHealth()

        // State should remain disconnected, no double-cleanup needed
        #expect(manager.connectionState == .disconnected)
    }

    // MARK: - Callback Verification Test

    @Test("calls onConnectionLost when stale state detected")
    func callsOnConnectionLostForStaleState() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)
        await mock.setStubbedIsDeviceConnectedToSystem(false)

        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        let tracker = ConnectionLostTracker()
        manager.onConnectionLost = {
            await tracker.markConnectionLost()
        }

        await manager.checkBLEConnectionHealth()

        let wasCalled = await tracker.connectionLostCalled
        #expect(wasCalled, "onConnectionLost should be called when stale state is detected")
    }

    // MARK: - Intent Preservation Tests

    @Test("preserves wantsConnection intent after resyncFailed disconnect")
    func preservesIntentAfterResyncFailed() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let deviceID = UUID()

        await mock.setStubbedIsConnected(false)
        await mock.setStubbedIsAutoReconnecting(false)
        await mock.setStubbedIsDeviceConnectedToSystem(false)

        // Simulate state where we were connected and user wants connection
        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )
        manager.testLastConnectedDeviceID = deviceID

        // Disconnect due to resync failure (internal reason)
        await manager.disconnect(reason: .resyncFailed)

        // Intent should be preserved — user never asked to disconnect
        #expect(manager.connectionIntent.wantsConnection,
                "connectionIntent should remain .wantsConnection after resyncFailed disconnect")

        // Health check should proceed past the guard and attempt reconnection
        // (it won't actually connect since there's no real BLE, but it shouldn't
        // bail out at the intent check)
        await manager.checkBLEConnectionHealth()

        // After health check, state should still reflect wanting connection
        #expect(manager.connectionIntent.wantsConnection,
                "connectionIntent should still be .wantsConnection after health check")
    }

    // MARK: - Lifecycle Tests

    @Test("appDidEnterBackground forwards to state machine")
    func appDidEnterBackgroundForwardsToStateMachine() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        await manager.appDidEnterBackground()

        let callCount = await mock.appDidEnterBackgroundCallCount
        #expect(callCount == 1)
    }

    @Test("appDidBecomeActive forwards to state machine and triggers health check")
    func appDidBecomeActiveForwardsToStateMachine() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        await manager.appDidBecomeActive()

        let callCount = await mock.appDidBecomeActiveCallCount
        #expect(callCount == 1)
    }

    @Test("appDidEnterBackground stops running watchdog")
    func appDidEnterBackgroundStopsWatchdog() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        await mock.setStubbedIsAutoReconnecting(false)
        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        // Start watchdog via foreground
        await manager.appDidBecomeActive()
        #expect(manager.isReconnectionWatchdogRunning)

        // Background should stop it
        await manager.appDidEnterBackground()
        #expect(!manager.isReconnectionWatchdogRunning)
    }

    @Test("appDidBecomeActive re-arms watchdog when disconnected and wants connection")
    func appDidBecomeActiveRearmsWatchdogWhenDisconnected() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        await mock.setStubbedIsAutoReconnecting(false)
        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        await manager.appDidBecomeActive()

        #expect(manager.isReconnectionWatchdogRunning)

        await manager.appDidEnterBackground()
    }

    @Test("appDidBecomeActive does not arm watchdog when user does not want connection")
    func appDidBecomeActiveDoesNotArmWatchdogWhenIntentOff() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        await mock.setStubbedIsAutoReconnecting(false)
        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .none
        )

        await manager.appDidBecomeActive()

        #expect(!manager.isReconnectionWatchdogRunning)
    }

    @Test("appDidBecomeActive does not arm watchdog during auto-reconnect")
    func appDidBecomeActiveDoesNotArmWatchdogDuringAutoReconnect() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        await mock.setStubbedIsAutoReconnecting(true)
        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        await manager.appDidBecomeActive()

        #expect(!manager.isReconnectionWatchdogRunning)
    }
}

// MARK: - Test Helpers

private actor ConnectionLostTracker {
    var connectionLostCalled = false

    func markConnectionLost() {
        connectionLostCalled = true
    }
}

private actor SessionRebuildTracker {
    private var calls: [UUID] = []

    func record(_ deviceID: UUID) {
        calls.append(deviceID)
    }

    func recordedCalls() -> [UUID] {
        calls
    }
}
