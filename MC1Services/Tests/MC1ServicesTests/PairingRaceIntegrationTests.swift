import Foundation
import Testing
@testable import MC1Services

@Suite("PairingRaceIntegration")
@MainActor
struct PairingRaceIntegrationTests {

    /// While `pairNewDevice` is suspended in `waitForOtherAppReconnection`,
    /// `appDidBecomeActive` (or any other foreground transition that would
    /// normally trigger an opportunistic reconnect) must not race the pairing
    /// flow's `connect(to:)`. The fix: `shouldDeferOpportunisticReconnect`
    /// gates `attemptOpportunisticReconnect` for the duration of the pair flow.
    ///
    /// This test pins `pairNewDevice` in the wait via the strategy override,
    /// drives `checkBLEConnectionHealth` (the foreground reconnect path)
    /// concurrently, then asserts the transport never received a connect call
    /// for the previously-connected device.
    @Test("opportunistic reconnect to old device is gated while pairing is suspended in waitForOtherAppReconnection")
    func opportunisticReconnectGatedDuringPairingWait() async throws {
        let env = try ConnectionManager.createForPairingTesting()
        defer { env.cleanup() }
        let manager = env.manager
        let mockTransport = env.transport
        let mockASK = env.accessorySetupKit
        let oldDeviceID = UUID()
        let newDeviceID = UUID()

        mockASK.setPickerResult(.success(newDeviceID))

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

        let pairTask = Task { try? await manager.pairNewDevice() }

        // Yield to ensure pairTask has scheduled and reached the wait strategy
        // before we drive the racer. Without this yield, scheduler ordering
        // determines whether the racer fires before or after the gate engages.
        await Task.yield()
        for await _ in waitStarted.stream { break }

        await manager.checkBLEConnectionHealth()

        let invocations = await mockTransport.connectInvocations
        let invocationsForOld = invocations.filter { $0.deviceID == oldDeviceID }
        #expect(
            invocationsForOld.isEmpty,
            "Opportunistic reconnect to old device should be gated while pairing is in progress"
        )

        releaseWait.continuation.finish()
        // Don't await pairTask — the connect(to:) path needs a fully wired transport
        // to make progress, and the mock intentionally stalls to keep the test focused
        // on the gate behavior. Cancel it so it unwinds promptly.
        pairTask.cancel()
        _ = await pairTask.value
    }
}
