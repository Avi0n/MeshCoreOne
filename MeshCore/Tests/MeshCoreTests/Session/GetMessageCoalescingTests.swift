import Foundation
import MeshCoreTestSupport
import Testing
@testable import MeshCore

/// A `getMessage` call that arrives while another is in flight must coalesce onto the
/// in-flight exchange and share its outcome. Every path out of the leader (result,
/// device error, timeout) must release the coalesced caller; a coalesced caller must
/// never be parked beyond the leader's own timeout, even when its task is cancelled.
@Suite("MeshCoreSession getMessage coalescing")
struct GetMessageCoalescingTests {

    @Test("a coalesced caller shares the leader's frame and result")
    func coalescedCallerSharesLeaderResult() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 2.0, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let leader = Task { try await session.getMessage() }
        try await waitUntil("leader should send the getMessage frame") {
            await transport.sentData.count == 2
        }

        let follower = Task { try await session.getMessage() }
        try? await Task.sleep(for: .milliseconds(100))
        #expect(await transport.sentData.count == 2, "a coalesced caller must not send its own frame")

        await transport.simulateReceive(makeNoMoreMessagesPacket())

        let leaderResult = try await leader.value
        let followerResult = try await follower.value
        guard case .noMoreMessages = leaderResult, case .noMoreMessages = followerResult else {
            Issue.record("Expected both callers to see .noMoreMessages, got \(leaderResult) and \(followerResult)")
            await session.stop()
            return
        }
        #expect(await transport.sentData.count == 2, "the shared exchange must produce exactly one frame")

        await session.stop()
    }

    @Test("a coalesced caller is released when the leader times out")
    func coalescedCallerReleasedOnLeaderTimeout() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 2.0, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let leader = Task { try await session.getMessage(timeout: 0.15) }
        try await waitUntil("leader should send the getMessage frame") {
            await transport.sentData.count == 2
        }

        let follower = Task { try await session.getMessage() }
        try? await Task.sleep(for: .milliseconds(50))

        // No response ever arrives; the leader's timeout must release both callers.
        await #expect(throws: MeshCoreError.self) { try await leader.value }
        await #expect(throws: MeshCoreError.self) { try await follower.value }

        await session.stop()
    }

    @Test("a coalesced caller is released when the leader fails with a device error")
    func coalescedCallerReleasedOnLeaderError() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 2.0, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let leader = Task { try await session.getMessage() }
        try await waitUntil("leader should send the getMessage frame") {
            await transport.sentData.count == 2
        }

        let follower = Task { try await session.getMessage() }
        try? await Task.sleep(for: .milliseconds(50))

        await transport.simulateError(code: 9)

        await #expect(throws: MeshCoreError.self) { try await leader.value }
        await #expect(throws: MeshCoreError.self) { try await follower.value }

        await session.stop()
    }

    @Test("a cancelled coalesced caller does not hang")
    func cancelledCoalescedCallerDoesNotHang() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 2.0, clientIdentifier: "MCTst")
        )
        try await startSession(session, transport: transport)

        let leader = Task { try await session.getMessage(timeout: 0.3) }
        try await waitUntil("leader should send the getMessage frame") {
            await transport.sentData.count == 2
        }

        let follower = Task { try await session.getMessage() }
        try? await Task.sleep(for: .milliseconds(50))
        follower.cancel()

        // The cancelled caller's release is bounded by the leader's timeout: it must
        // complete (with any error) rather than stay parked forever.
        let completed = CallTracker()
        Task {
            _ = await follower.result
            completed.markCalled()
        }
        try await waitUntil(timeout: .seconds(2), "cancelled coalesced caller should be released") {
            completed.wasCalled
        }

        await #expect(throws: (any Error).self) { try await follower.value }
        await #expect(throws: MeshCoreError.self) { try await leader.value }

        await session.stop()
    }
}

private func startSession(
    _ session: MeshCoreSession,
    transport: MockTransport
) async throws {
    let startTask = Task { try await session.start() }
    try await waitUntil("transport should send appStart before session starts") {
        await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
}

private func makeNoMoreMessagesPacket() -> Data {
    Data([ResponseCode.noMoreMessages.rawValue])
}

private func makeSelfInfoPacket() -> Data {
    var payload = Data([ResponseCode.selfInfo.rawValue])
    payload.append(1)                                         // adv type
    payload.append(UInt8(bitPattern: 22))                     // tx power
    payload.append(UInt8(bitPattern: 22))                     // max tx power
    payload.append(Data(repeating: 0x01, count: 32))          // pubkey
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })  // lat
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })  // lon
    payload.append(0)                                          // multi acks
    payload.append(0)                                          // adv loc policy
    payload.append(0)                                          // telemetry mode
    payload.append(0)                                          // manual add
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(869525).littleEndian) { Array($0) })  // freq
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) }) // bw
    payload.append(11)                                         // sf
    payload.append(5)                                          // cr
    return payload
}
