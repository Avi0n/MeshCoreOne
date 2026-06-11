import Foundation
import Testing
@testable import MC1Services

@Suite("BLEReconnectionCoordinator Tests")
@MainActor
struct BLEReconnectionCoordinatorTests {

    // MARK: - Test Helpers

    private func createCoordinator(
        delegate: MockReconnectionDelegate? = nil,
        uiTimeoutDuration: TimeInterval = 10,
        maxConnectingUIWindow: TimeInterval = 60
    ) -> (BLEReconnectionCoordinator, MockReconnectionDelegate) {
        let coordinator = BLEReconnectionCoordinator(
            uiTimeoutDuration: uiTimeoutDuration,
            maxConnectingUIWindow: maxConnectingUIWindow
        )
        let mockDelegate = delegate ?? MockReconnectionDelegate()
        coordinator.delegate = mockDelegate
        return (coordinator, mockDelegate)
    }

    // MARK: - handleEnteringAutoReconnect Tests

    @Test("entering auto-reconnect sets state to .connecting when user wants connection")
    func enteringAutoReconnectSetsConnecting() async {
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

        #expect(delegate.connectionState == .connecting)
    }

    @Test("entering auto-reconnect tears down session")
    func enteringAutoReconnectTearsDownSession() async {
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

        #expect(delegate.teardownSessionCallCount == 1)
    }

    @Test("entering auto-reconnect is ignored when intent is .userDisconnected")
    func enteringAutoReconnectIgnoredForUserDisconnected() async {
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .userDisconnected
        delegate.connectionState = .disconnected

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

        #expect(delegate.connectionState == .disconnected, "State should not change when user disconnected")
        #expect(delegate.teardownSessionCallCount == 0, "Session should not be torn down")
        #expect(delegate.disconnectTransportCallCount == 1, "Transport should be disconnected")
    }

    @Test("entering auto-reconnect is ignored when intent is .none")
    func enteringAutoReconnectIgnoredForNone() async {
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .none
        delegate.connectionState = .disconnected

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

        #expect(delegate.connectionState == .disconnected)
        #expect(delegate.disconnectTransportCallCount == 1)
    }

    // MARK: - handleReconnectionComplete Tests
    //
    // The coordinator only accepts a completion for a cycle it explicitly claimed
    // via handleEnteringAutoReconnect. Each happy-path test below claims first to
    // mirror the production wiring (setAutoReconnectingHandler always fires before
    // setReconnectionHandler for the same cycle).

    @Test("reconnection complete keeps state .connecting when claim matches")
    func reconnectionCompleteKeepsConnectingWhenClaimed() async {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready

        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.connectionState == .connecting)
    }

    @Test("reconnection complete calls rebuildSession when claim matches")
    func reconnectionCompleteCallsRebuild() async {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready

        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.rebuildSessionCalls.count == 1)
        #expect(delegate.rebuildSessionCalls.first == deviceID)
    }

    @Test("reconnection complete is ignored when no entry was claimed")
    func reconnectionCompleteIgnoredWithoutClaim() async {
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .disconnected

        // No prior handleEnteringAutoReconnect — claim is nil. This mirrors the
        // pairing race where the entry handler was suppressed but a late completion
        // still arrives.
        await coordinator.handleReconnectionComplete(deviceID: UUID())

        #expect(delegate.connectionState == .disconnected, "Should not transition without claim")
        #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild without claim")
    }

    @Test("reconnection complete is ignored when intent is .userDisconnected")
    func reconnectionCompleteIgnoredForUserDisconnected() async {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        // Now the user disconnects mid-reconnect.
        delegate.connectionIntent = .userDisconnected

        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild when user disconnected")
        #expect(delegate.disconnectTransportCallCount == 1, "Should disconnect transport")
    }

    @Test("reconnection complete is ignored when already .ready")
    func reconnectionCompleteIgnoredWhenReady() async {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        // Force state back to .ready (e.g., a parallel resync promoted the session).
        delegate.connectionState = .ready

        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.connectionState == .ready, "Should not change state when already ready")
        #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild when already ready")
    }

    @Test("reconnection complete is ignored when .syncing (session alive, resync running)")
    func reconnectionCompleteIgnoredWhenSyncing() async {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        // Simulate a parallel resync promoting the session to .syncing.
        delegate.connectionState = .syncing

        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.connectionState == .syncing, "Should not change state when syncing")
        #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild when syncing")
    }

    @Test("reconnection complete handles rebuild failure")
    func reconnectionCompleteHandlesRebuildFailure() async {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.rebuildSessionShouldThrow = true

        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)
        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.handleReconnectionFailureCallCount == 1)
    }

    @Test("stale device completion does not cancel active timeout")
    func staleDeviceDoesNotCancelTimeout() async throws {
        let activeDevice = UUID()
        let staleDevice = UUID()
        let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready

        await coordinator.handleEnteringAutoReconnect(deviceID: activeDevice)
        #expect(delegate.connectionState == .connecting)

        // Stale completion for a different device should be rejected
        await coordinator.handleReconnectionComplete(deviceID: staleDevice)

        // Timeout should still fire because it was not canceled
        try await waitUntil("Timeout should still fire after stale completion") {
            delegate.connectionState == .disconnected
        }

        #expect(delegate.connectionState == .disconnected, "Timeout should still fire after stale completion")
        #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild for stale device")
    }

    // MARK: - UI Timeout Tests

    @Test("UI timeout transitions to disconnected after duration")
    func uiTimeoutTransitionsToDisconnected() async throws {
        let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())
        #expect(delegate.connectionState == .connecting)

        // Wait for timeout to fire and transition state
        try await waitUntil("Timeout should transition to disconnected") {
            delegate.connectionState == .disconnected
        }

        #expect(delegate.connectionState == .disconnected)
        #expect(delegate.connectedDeviceWasCleared == true)
        #expect(delegate.notifyConnectionLostCallCount == 1)
    }

    @Test("UI timeout is cancelled when reconnection completes")
    func uiTimeoutCancelledOnReconnection() async throws {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready

        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)
        #expect(delegate.connectionState == .connecting)

        // Complete reconnection before timeout (same device)
        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        // Fixed sleep: negative assertion — confirm timeout did NOT fire
        try await Task.sleep(for: .milliseconds(250))

        // Should be .connecting from reconnection complete, not .disconnected from timeout
        #expect(delegate.connectionState == .connecting)
        #expect(delegate.notifyConnectionLostCallCount == 0)
    }

    // MARK: - Stale Retry Tests

    @Test("stale rebuild retry is aborted when new reconnect cycle starts during delay")
    func staleRetryAbortedOnNewCycle() async throws {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator()
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.rebuildSessionShouldThrow = true

        // Claim the first cycle so the completion is accepted.
        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        // Start first reconnection — rebuild will fail, triggering 2s retry delay
        let firstReconnectTask = Task {
            await coordinator.handleReconnectionComplete(deviceID: deviceID)
        }

        // Wait for first rebuild to fail and enter the 2s retry delay
        try await waitUntil("First rebuild should have been attempted") {
            delegate.rebuildSessionCalls.count == 1
        }
        #expect(delegate.rebuildSessionCalls.count == 1, "First rebuild should have been attempted")

        // Start a new reconnect cycle during the delay — this bumps the generation counter.
        // Re-claim the cycle since the prior completion cleared reconnectingDeviceID.
        delegate.rebuildSessionShouldThrow = false
        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)
        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        // Wait for the first task's stale retry to wake and be aborted
        await firstReconnectTask.value

        // Should have exactly 2 rebuild calls: first (failed) + new cycle (succeeded).
        // The stale retry should have been aborted by the generation check.
        #expect(delegate.rebuildSessionCalls.count == 2, "Stale retry should have been aborted")
        #expect(delegate.handleReconnectionFailureCallCount == 0, "No failure handler since new cycle succeeded")
    }

    // MARK: - Max Connecting Window Tests

    @Test("UI timeout disconnects when max connecting window exceeded")
    func uiTimeoutDisconnectsAtMaxWindow() async throws {
        let (coordinator, delegate) = createCoordinator(
            uiTimeoutDuration: 0.05,
            maxConnectingUIWindow: 0.15
        )
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.stubbedBLEPhaseIsAutoReconnecting = true

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

        // Wait for max connecting window to expire and disconnect
        try await waitUntil("Max connecting window should trigger disconnect") {
            delegate.connectionState == .disconnected
        }

        #expect(delegate.connectionState == .disconnected)
        #expect(delegate.notifyConnectionLostCallCount == 1)
    }

    @Test("same-device completion is accepted after UI timeout while transport auto-reconnects")
    func sameDeviceCompletionAcceptedAfterUITimeoutWhileAutoReconnecting() async throws {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator(
            uiTimeoutDuration: 0.05,
            maxConnectingUIWindow: 0.15
        )
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.stubbedBLEPhaseIsAutoReconnecting = true

        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        try await waitUntil("UI timeout should transition presentation to disconnected") {
            delegate.connectionState == .disconnected
        }

        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.rebuildSessionCalls == [deviceID])
        #expect(delegate.connectionState == .connecting)
    }

    @Test("UI timeout clears cycle when transport stops auto-reconnecting")
    func uiTimeoutClearsCycleWhenTransportStopsAutoReconnecting() async throws {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.05)
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.stubbedBLEPhaseIsAutoReconnecting = false

        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        try await waitUntil("UI timeout should transition to disconnected") {
            delegate.connectionState == .disconnected
        }

        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.rebuildSessionCalls.isEmpty)
    }

    @Test("different-device completion is rejected after UI timeout")
    func differentDeviceCompletionRejectedAfterUITimeout() async throws {
        let activeDeviceID = UUID()
        let staleDeviceID = UUID()
        let (coordinator, delegate) = createCoordinator(
            uiTimeoutDuration: 0.05,
            maxConnectingUIWindow: 0.15
        )
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.stubbedBLEPhaseIsAutoReconnecting = true

        await coordinator.handleEnteringAutoReconnect(deviceID: activeDeviceID)

        try await waitUntil("UI timeout should transition presentation to disconnected") {
            delegate.connectionState == .disconnected
        }

        await coordinator.handleReconnectionComplete(deviceID: staleDeviceID)

        #expect(delegate.rebuildSessionCalls.isEmpty)
        #expect(delegate.connectionState == .disconnected)
    }

    @Test("user-disconnected completion after UI timeout remains rejected")
    func userDisconnectedCompletionAfterUITimeoutRejected() async throws {
        let deviceID = UUID()
        let (coordinator, delegate) = createCoordinator(
            uiTimeoutDuration: 0.05,
            maxConnectingUIWindow: 0.15
        )
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.stubbedBLEPhaseIsAutoReconnecting = true

        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        try await waitUntil("UI timeout should transition presentation to disconnected") {
            delegate.connectionState == .disconnected
        }

        delegate.connectionIntent = .userDisconnected
        await coordinator.handleReconnectionComplete(deviceID: deviceID)

        #expect(delegate.rebuildSessionCalls.isEmpty)
        #expect(delegate.disconnectTransportCallCount == 1)
    }

    // MARK: - cancelTimeout Tests

    @Test("UI timeout re-arms if BLE is still auto-reconnecting")
    func uiTimeoutRearmsWhenAutoReconnecting() async throws {
        let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.stubbedBLEPhaseIsAutoReconnecting = true

        let deviceID = UUID()
        await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

        // Fixed sleep: negative assertion — confirm re-arm keeps state as .connecting
        try await Task.sleep(for: .milliseconds(200))

        // Should still be .connecting because BLE is auto-reconnecting
        #expect(delegate.connectionState == .connecting)
        #expect(delegate.notifyConnectionLostCallCount == 0)
    }

    @Test("UI timeout eventually disconnects when max window exceeded")
    func uiTimeoutEventuallyDisconnects() async throws {
        // Use a very short maxConnectingUIWindow via a coordinator with short timeout
        let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.05)
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.stubbedBLEPhaseIsAutoReconnecting = true

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

        // Fixed sleep: negative assertion — within the 60s max window, re-arm
        // should keep the state as .connecting. We can't wait 60s in a test, so
        // verify the re-arm mechanism works within a short window.
        try await Task.sleep(for: .milliseconds(200))

        // Within the 60s window, should still be .connecting
        #expect(delegate.connectionState == .connecting)
    }

    @Test("UI timeout fires normally when BLE is not auto-reconnecting")
    func uiTimeoutFiresWhenNotAutoReconnecting() async throws {
        let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready
        delegate.stubbedBLEPhaseIsAutoReconnecting = false

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

        try await waitUntil("Timeout should fire when BLE is not auto-reconnecting") {
            delegate.connectionState == .disconnected
        }

        #expect(delegate.connectionState == .disconnected)
        #expect(delegate.notifyConnectionLostCallCount == 1)
    }

    @Test("cancelTimeout prevents timeout from firing")
    func cancelTimeoutPreventsTimeout() async throws {
        let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
        delegate.connectionIntent = .wantsConnection()
        delegate.connectionState = .ready

        await coordinator.handleEnteringAutoReconnect(deviceID: UUID())
        coordinator.cancelTimeout()

        // Fixed sleep: negative assertion — confirm timeout was cancelled
        try await Task.sleep(for: .milliseconds(250))

        // State should remain .connecting (timeout was cancelled)
        #expect(delegate.connectionState == .connecting)
    }
}

// MARK: - Mock Delegate

@MainActor
private final class MockReconnectionDelegate: BLEReconnectionDelegate {
    var connectionIntent: ConnectionIntent = .none
    var connectionState: DeviceConnectionState = .disconnected

    var teardownSessionCallCount = 0
    var rebuildSessionCalls: [UUID] = []
    var rebuildSessionShouldThrow = false
    var disconnectTransportCallCount = 0
    var notifyConnectionLostCallCount = 0
    var handleReconnectionFailureCallCount = 0
    var connectedDeviceWasCleared = false
    var stubbedBLEPhaseIsAutoReconnecting = false

    func setConnectionState(_ state: DeviceConnectionState) {
        connectionState = state
    }

    func setConnectedDevice(_ device: DeviceDTO?) {
        if device == nil {
            connectedDeviceWasCleared = true
        }
    }

    func teardownSessionForReconnect() async {
        teardownSessionCallCount += 1
    }

    func rebuildSession(deviceID: UUID) async throws {
        rebuildSessionCalls.append(deviceID)
        if rebuildSessionShouldThrow {
            throw ReconnectionTestError.rebuildFailed
        }
    }

    func disconnectTransport() async {
        disconnectTransportCallCount += 1
    }

    func notifyConnectionLost() async {
        notifyConnectionLostCallCount += 1
    }

    func handleReconnectionFailure() async {
        handleReconnectionFailureCallCount += 1
    }

    func isTransportAutoReconnecting() async -> Bool {
        stubbedBLEPhaseIsAutoReconnecting
    }
}

private enum ReconnectionTestError: Error {
    case rebuildFailed
}
