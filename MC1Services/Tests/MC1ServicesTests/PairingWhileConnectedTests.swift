import Foundation
import Testing
@testable import MC1Services

@Suite("PairingWhileConnected")
@MainActor
struct PairingWhileConnectedTests {

    /// iOS auto-reconnect for the previously-connected radio can fire 0–~3 seconds
    /// after ASK severance, landing during pairNewDevice's waitForOtherAppReconnection
    /// poll. Without the auto-reconnect-handler gate (A7), that handler would claim
    /// the state machine via `reconnectionCoordinator.handleEnteringAutoReconnect`
    /// and starve the pairing's subsequent `connect(to: newDeviceID)`.
    ///
    /// This test starts a real pairNewDevice flow, pins it in the wait override,
    /// then simulates iOS auto-reconnect for the old device firing. The handler
    /// must observe shouldDeferOpportunisticReconnect, write the diagnostic, and
    /// return without claiming the coordinator.
    @Test("auto-reconnect during waitForOtherAppReconnection does not claim coordinator")
    func autoReconnectDuringWaitDoesNotClaimCoordinator() async throws {
        let (manager, stateMachine, _, mockASK) = try ConnectionManager.createForPairingTesting()
        let oldDeviceID = UUID()
        let newDeviceID = UUID()

        mockASK.setPickerResult(.success(newDeviceID))

        manager.testLastConnectedDeviceID = oldDeviceID
        manager.setTestState(
            connectionState: .ready,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        let waitStarted = AsyncStream<Void>.makeStream()
        let releaseWait = AsyncStream<Void>.makeStream()
        manager.otherAppWaitStrategyOverride = { _ in
            waitStarted.continuation.yield()
            for await _ in releaseWait.stream { break }
            return false
        }

        try await waitUntil("auto-reconnect handler should be installed") {
            await stateMachine.hasAutoReconnectingHandler
        }

        let pairTask = Task { try? await manager.pairNewDevice() }

        await Task.yield()
        for await _ in waitStarted.stream { break }

        await stateMachine.simulateAutoReconnecting(deviceID: oldDeviceID)

        try await waitUntil("handler should write diagnostic before suppressing") {
            manager.lastDisconnectDiagnostic?.localizedStandardContains(
                "source=bleStateMachine.autoReconnectingHandler"
            ) ?? false
        }

        #expect(manager.activeReconnectDeviceID == nil)

        releaseWait.continuation.finish()
        pairTask.cancel()
        _ = await pairTask.value
    }

    /// Companion to the entry-suppression test above. The entry handler being
    /// suppressed leaves `reconnectingDeviceID` as nil. A late completion for the
    /// old device must be rejected by the coordinator's claim guard — otherwise
    /// `rebuildSession(OLD)` would race the new pairing's `connect(to: NEW)`,
    /// reading NEW's traffic against OLD's identity.
    @Test("auto-reconnect completion during pair-wait does not run rebuildSession")
    func autoReconnectCompletionDuringWaitDoesNotRebuild() async throws {
        let (manager, _, mockTransport, mockASK) = try ConnectionManager.createForPairingTesting()
        let oldDeviceID = UUID()
        let newDeviceID = UUID()

        mockASK.setPickerResult(.success(newDeviceID))

        // Pre-pairing: previously connected to OLD; entry was suppressed during
        // ASK pairing severance, so connectionState is .disconnected and no claim
        // exists in the coordinator.
        manager.testLastConnectedDeviceID = oldDeviceID
        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        let waitStarted = AsyncStream<Void>.makeStream()
        let releaseWait = AsyncStream<Void>.makeStream()
        manager.otherAppWaitStrategyOverride = { _ in
            waitStarted.continuation.yield()
            for await _ in releaseWait.stream { break }
            return false
        }

        try await waitUntil("reconnection handler should be installed") {
            await mockTransport.hasReconnectionHandler
        }

        let pairTask = Task { try? await manager.pairNewDevice() }

        await Task.yield()
        for await _ in waitStarted.stream { break }

        // iOS auto-reconnect for OLD completes during the wait. Without the claim
        // guard this would set state to .connecting and call rebuildSession(OLD).
        await mockTransport.simulateReconnection(deviceID: oldDeviceID)

        // Allow the dispatched @MainActor Task to run and the guard to reject.
        try await Task.sleep(for: .milliseconds(50))

        #expect(manager.connectionState == .disconnected, "Late completion must not transition state")

        releaseWait.continuation.finish()
        pairTask.cancel()
        _ = await pairTask.value
    }
}
