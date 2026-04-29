import Foundation
import Testing
@testable import MC1Services

@Suite("PairingWhileConnected")
@MainActor
struct PairingWhileConnectedTests {

    /// iOS auto-reconnect for the previously-connected radio can fire 0–~3 seconds
    /// after ASK severance, landing during pairNewDevice's waitForOtherAppReconnection
    /// poll. Without the auto-reconnect-handler gate, that handler would claim the
    /// state machine via `reconnectionCoordinator.handleEnteringAutoReconnect` and
    /// starve the pairing's subsequent `connect(to: newDeviceID)`.
    ///
    /// This test starts a real pairNewDevice flow, pins it in the wait override,
    /// then simulates iOS auto-reconnect for the old device firing. The handler
    /// must observe `shouldDeferOpportunisticReconnect`, run the old-device
    /// session teardown via `handleConnectionLoss`, and return without claiming
    /// the coordinator.
    @Test("auto-reconnect during waitForOtherAppReconnection tears down old-device session without claiming coordinator")
    func autoReconnectDuringWaitDoesNotClaimCoordinator() async throws {
        let env = try ConnectionManager.createForPairingTesting()
        defer { env.cleanup() }
        let manager = env.manager
        let stateMachine = env.stateMachine
        let mockASK = env.accessorySetupKit
        let oldDeviceID = UUID()
        let newDeviceID = UUID()

        mockASK.setPickerResult(.success(newDeviceID))

        manager.testLastConnectedDeviceID = oldDeviceID
        manager.setTestState(
            connectionState: .ready,
            connectedDevice: DeviceDTO.testDevice(id: oldDeviceID),
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

        // The suppression branch routes through handleConnectionLoss to clear the
        // stale old-device session. Observing the state transition and connectedDevice
        // clear is the proof the handler ran without claiming the coordinator.
        try await waitUntil("suppression branch should tear down old-device session") {
            manager.connectionState == .disconnected && manager.connectedDevice == nil
        }

        #expect(manager.activeReconnectDeviceID == nil)
        #expect(manager.connectedDevice == nil)

        releaseWait.continuation.finish()
        pairTask.cancel()
        _ = await pairTask.value
    }

    /// Companion to the entry-suppression test above. The entry handler being
    /// suppressed leaves `reconnectingDeviceID` as nil. A late completion for the
    /// old device must be rejected by the coordinator's claim guard — otherwise
    /// `rebuildSession(oldDeviceID)` would race the new pairing's `connect(to: newDeviceID)`,
    /// reading the new device's traffic against the old device's identity.
    @Test("auto-reconnect completion during pair-wait does not run rebuildSession")
    func autoReconnectCompletionDuringWaitDoesNotRebuild() async throws {
        let env = try ConnectionManager.createForPairingTesting()
        defer { env.cleanup() }
        let manager = env.manager
        let mockTransport = env.transport
        let mockASK = env.accessorySetupKit
        let oldDeviceID = UUID()
        let newDeviceID = UUID()

        mockASK.setPickerResult(.success(newDeviceID))

        // Pre-pairing: previously connected to the old device; entry was suppressed
        // during ASK pairing severance, so connectionState is .disconnected and no
        // claim exists in the coordinator.
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

        // iOS auto-reconnect for the old device completes during the wait. Without
        // the claim guard this would set state to .connecting and call
        // rebuildSession(oldDeviceID).
        await mockTransport.simulateReconnection(deviceID: oldDeviceID)

        // Drain the dispatched @MainActor Task. With the claim guard the path returns
        // synchronously after the guard check, so a few yields are sufficient.
        // Without the guard the buggy path sets state to .connecting before its first
        // await, which is also visible after the same yields.
        for _ in 0..<5 { await Task.yield() }

        #expect(manager.connectionState == .disconnected, "Late completion must not transition state")

        releaseWait.continuation.finish()
        pairTask.cancel()
        _ = await pairTask.value
    }
}
