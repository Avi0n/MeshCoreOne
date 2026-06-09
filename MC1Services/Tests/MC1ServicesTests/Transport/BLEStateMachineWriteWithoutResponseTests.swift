@preconcurrency import CoreBluetooth
import Foundation
import Testing
@testable import MC1Services

/// Covers the `.withoutResponse` write path's backpressure continuation lifecycle and
/// capability capture. The full `sendWithoutResponse` round-trip needs a real `CBPeripheral`
/// (no public initializer) so it is verified on-device; these tests lock the concurrency-
/// critical parts that can hang or corrupt state if they regress.
@Suite("BLEStateMachine write-without-response path")
struct BLEStateMachineWriteWithoutResponseTests {

    // MARK: - Helpers

    /// Polls until the state machine has installed its readiness continuation, so the test
    /// can deterministically signal readiness without racing the suspending task.
    private func waitUntilAwaiting(_ sm: BLEStateMachine) async -> Bool {
        for _ in 0..<200 {
            if await sm.isAwaitingWriteWithoutResponseReady { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return false
    }

    /// Returns true if `work` finishes within `duration`; false if it is still running
    /// (i.e. the awaited continuation never resumed — a hang). Converts a hang into a
    /// fast assertion failure instead of stalling the whole suite.
    private func completesWithin(_ duration: Duration, _ work: @escaping @Sendable () async -> Void) async -> Bool {
        await withTaskGroup(of: Bool.self) { group in
            group.addTask { await work(); return true }
            group.addTask { try? await Task.sleep(for: duration); return false }
            defer { group.cancelAll() }
            return await group.next() ?? false
        }
    }

    // MARK: - Readiness gating

    @Test("readiness wait returns immediately when peripheral is already ready")
    func waitReturnsImmediatelyWhenReady() async {
        let sm = BLEStateMachine()

        let finished = await completesWithin(.seconds(1)) {
            _ = try? await sm.awaitWriteWithoutResponseReadiness(alreadyReady: true)
        }

        #expect(finished)
        #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
    }

    @Test("readiness wait resumes when the peripheral signals it can write again")
    func waitResumesOnPeripheralReadyCallback() async {
        let sm = BLEStateMachine()

        let waiter = Task { _ = try? await sm.awaitWriteWithoutResponseReadiness(alreadyReady: false) }
        #expect(await waitUntilAwaiting(sm))

        await sm.handlePeripheralReadyForWriteWithoutResponse()

        #expect(await completesWithin(.seconds(3)) { await waiter.value })
        #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
    }

    @Test("readiness wait resumes on disconnect so a sender never hangs across a drop")
    func waitResumesOnDisconnect() async {
        let sm = BLEStateMachine()

        let waiter = Task { _ = try? await sm.awaitWriteWithoutResponseReadiness(alreadyReady: false) }
        #expect(await waitUntilAwaiting(sm))

        await sm.disconnect()

        #expect(await completesWithin(.seconds(3)) { await waiter.value })
        #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
    }

    @Test("readiness wait fails via the timeout backstop when the peripheral stays silent")
    func waitTimesOutWhenPeripheralStaysSilent() async {
        let sm = BLEStateMachine(writeTimeout: 0.5)

        let waiter = Task { () -> Bool in
            do {
                try await sm.awaitWriteWithoutResponseReadiness(alreadyReady: false)
                return false
            } catch {
                return true
            }
        }
        #expect(await waitUntilAwaiting(sm))

        // No ready callback and no disconnect — only the backstop timeout can release it.
        let finished = await completesWithin(.seconds(3)) { _ = await waiter.value }
        #expect(finished)
        #expect(await waiter.value)
        #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
    }

    @Test("peripheral-ready callback with no pending waiter is a safe no-op")
    func readyCallbackWithoutWaiterIsSafe() async {
        let sm = BLEStateMachine()

        await sm.handlePeripheralReadyForWriteWithoutResponse()

        #expect(await sm.isAwaitingWriteWithoutResponseReady == false)
    }

    // MARK: - Capability capture

    @Test("capability is true when the write characteristic advertises writeWithoutResponse")
    func capabilityTrueForWriteWithoutResponseCharacteristic() async {
        let sm = BLEStateMachine()
        let characteristic = CBMutableCharacteristic(
            type: CBUUID(string: BLEServiceUUID.txCharacteristic),
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )

        await sm.captureWriteWithoutResponseCapability(from: characteristic)

        #expect(await sm.supportsWriteWithoutResponse == true)
    }

    @Test("capability is false when the write characteristic is write-only (ESP32)")
    func capabilityFalseForWriteOnlyCharacteristic() async {
        let sm = BLEStateMachine()
        let characteristic = CBMutableCharacteristic(
            type: CBUUID(string: BLEServiceUUID.txCharacteristic),
            properties: [.write],
            value: nil,
            permissions: [.writeable]
        )

        await sm.captureWriteWithoutResponseCapability(from: characteristic)

        #expect(await sm.supportsWriteWithoutResponse == false)
    }
}
