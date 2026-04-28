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
}
