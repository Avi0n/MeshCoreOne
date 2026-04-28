import Foundation
import Testing
@testable import MC1Services

@Suite("PairingCancellation")
@MainActor
struct PairingCancellationTests {

    /// When pairNewDevice is cancelled before the picker resolves, ASK has not
    /// added the device — there's nothing to clean up.
    @Test("cancellation before showPicker completes does not call removeAccessory")
    func cancellationBeforePickerDoesNotRemove() async throws {
        let (manager, _, _, mockASK) = try ConnectionManager.createForPairingTesting()

        let pickerGate = AsyncStream<Void>.makeStream()
        mockASK.pickerGate = pickerGate.stream
        mockASK.setPickerResult(.success(UUID()))

        let pairTask = Task { try? await manager.pairNewDevice() }

        // Yield so the pair task starts and reaches showPicker.
        await Task.yield()
        await Task.yield()

        pairTask.cancel()
        pickerGate.continuation.finish()

        _ = await pairTask.value

        #expect(mockASK.removeAccessoryCallCount == 0)
    }

    /// When pairNewDevice is cancelled after ASK adds the device but before
    /// connect(to:) finishes, cleanupPartialPairing must remove the bond from
    /// ASK so iOS doesn't retain a paired accessory with no app-level state.
    @Test("cancellation in connect(to:) phase removes accessory from ASK")
    func cancellationInConnectPhaseRemoves() async throws {
        let (manager, _, _, mockASK) = try ConnectionManager.createForPairingTesting()
        let deviceID = UUID()
        mockASK.setPickerResult(.success(deviceID))
        mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "test")])

        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        // Override the wait strategy with a cancellation-aware sleep so the
        // wait actually surfaces a CancellationError-style exit.
        manager.otherAppWaitStrategyOverride = { _ in
            try? await Task.sleep(for: .seconds(60))
            return false
        }

        let pairTask = Task { try? await manager.pairNewDevice() }

        // Yield to let pairTask reach the wait, then cancel before any of the
        // connect path runs against the mocks.
        try await Task.sleep(for: .milliseconds(20))

        pairTask.cancel()
        _ = await pairTask.value

        #expect(mockASK.removeAccessoryCallCount == 1)
        #expect(mockASK.lastRemovedDeviceID == deviceID)
    }
}
