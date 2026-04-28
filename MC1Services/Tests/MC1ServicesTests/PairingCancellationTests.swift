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
        let env = try ConnectionManager.createForPairingTesting()
        defer { env.cleanup() }
        let manager = env.manager
        let mockASK = env.accessorySetupKit

        let pickerEntered = AsyncStream<Void>.makeStream()
        let pickerGate = AsyncStream<Void>.makeStream()
        mockASK.pickerEnteredSignal = pickerEntered.continuation
        mockASK.pickerGate = pickerGate.stream
        mockASK.setPickerResult(.success(UUID()))

        let pairTask = Task { try? await manager.pairNewDevice() }

        // Wait for the pair task to deterministically reach showPicker.
        for await _ in pickerEntered.stream { break }

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
        let env = try ConnectionManager.createForPairingTesting()
        defer { env.cleanup() }
        let manager = env.manager
        let mockASK = env.accessorySetupKit
        let deviceID = UUID()
        mockASK.setPickerResult(.success(deviceID))
        mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "test")])

        manager.setTestState(
            connectionState: .disconnected,
            currentTransportType: .bluetooth,
            connectionIntent: .wantsConnection()
        )

        // Pin the wait so we can deterministically cancel after the pair task
        // has entered waitForOtherAppReconnection — i.e., past showPicker.
        let waitEntered = AsyncStream<Void>.makeStream()
        let releaseWait = AsyncStream<Void>.makeStream()
        manager.otherAppWaitStrategyOverride = { _ in
            waitEntered.continuation.yield()
            for await _ in releaseWait.stream { break }
            return false
        }

        let pairTask = Task { try? await manager.pairNewDevice() }

        for await _ in waitEntered.stream { break }

        pairTask.cancel()
        releaseWait.continuation.finish()
        _ = await pairTask.value

        #expect(mockASK.removeAccessoryCallCount == 1)
        #expect(mockASK.lastRemovedDeviceID == deviceID)
    }
}
