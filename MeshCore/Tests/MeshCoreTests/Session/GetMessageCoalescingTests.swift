import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

/// A `getMessage` call that arrives while another is in flight must coalesce onto the
/// in-flight exchange and share its outcome. Every path out of the leader (result,
/// device error, timeout) must release the coalesced caller; a coalesced caller must
/// never be parked beyond the leader's own timeout, even when its task is cancelled.
@Suite("MeshCoreSession getMessage coalescing", .timeLimit(.minutes(1)))
struct GetMessageCoalescingTests {
  private static let fetchTimeout: TimeInterval = 5

  @Test
  func `a coalesced caller shares the leader's frame and result`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)

    let leader = Task { try await session.getMessage() }
    try await waitUntil("leader should send the getMessage frame") {
      await transport.sentData.count == 2
    }

    let follower = Task { try await session.getMessage() }
    try await waitForMessageCallers(2, session: session)
    #expect(await transport.sentData.count == 2, "a coalesced caller must not send its own frame")

    await transport.simulateReceive(makeNoMoreMessagesPacket())

    try await waitForMessageCallers(0, session: session)
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

  @Test
  func `a coalesced caller is released when the leader times out`() async throws {
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst"),
      clock: clock
    )
    try await startSession(session, transport: transport)

    let leader = Task { try await session.getMessage(timeout: Self.fetchTimeout) }
    try await waitUntil("leader should send the getMessage frame") {
      await transport.sentData.count == 2
    }

    let follower = Task { try await session.getMessage() }
    try await waitForMessageCallers(2, session: session)

    try await waitUntil("leader timeout should park on the injected clock") {
      clock.sleeperCount == 1
    }
    clock.advance(by: .seconds(Self.fetchTimeout))
    try await waitForMessageCallers(0, session: session)

    // No response ever arrives; the leader's timeout must release both callers.
    await #expect(throws: MeshCoreError.self) { try await leader.value }
    await #expect(throws: MeshCoreError.self) { try await follower.value }

    await session.stop()
  }

  @Test
  func `a coalesced caller is released when the leader fails with a device error`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)

    let leader = Task { try await session.getMessage() }
    try await waitUntil("leader should send the getMessage frame") {
      await transport.sentData.count == 2
    }

    let follower = Task { try await session.getMessage() }
    try await waitForMessageCallers(2, session: session)

    await transport.simulateError(code: 9)
    try await waitForMessageCallers(0, session: session)

    await #expect(throws: MeshCoreError.self) { try await leader.value }
    await #expect(throws: MeshCoreError.self) { try await follower.value }

    await session.stop()
  }

  @Test
  func `a cancelled coalesced caller does not hang`() async throws {
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst"),
      clock: clock
    )
    try await startSession(session, transport: transport)

    let leader = Task { try await session.getMessage(timeout: Self.fetchTimeout) }
    try await waitUntil("leader should send the getMessage frame") {
      await transport.sentData.count == 2
    }

    let follower = Task { try await session.getMessage() }
    try await waitForMessageCallers(2, session: session)
    follower.cancel()

    try await waitUntil("leader timeout should park on the injected clock") {
      clock.sleeperCount == 1
    }
    clock.advance(by: .seconds(Self.fetchTimeout))
    try await waitForMessageCallers(0, session: session)

    await #expect(throws: (any Error).self) { try await follower.value }
    await #expect(throws: MeshCoreError.self) { try await leader.value }

    await session.stop()
  }
}

private func waitForMessageCallers(_ expected: Int, session: MeshCoreSession) async throws {
  do {
    try await waitUntil("getMessage should have \(expected) active callers") {
      await session.getMessageCallerCountForTest == expected
    }
  } catch {
    await session.stop()
    throw error
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
  payload.append(1) // adv type
  payload.append(UInt8(bitPattern: 22)) // tx power
  payload.append(UInt8(bitPattern: 22)) // max tx power
  payload.append(Data(repeating: 0x01, count: 32)) // pubkey
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lat
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lon
  payload.append(0) // multi acks
  payload.append(0) // adv loc policy
  payload.append(0) // telemetry mode
  payload.append(0) // manual add
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) }) // freq
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) }) // bw
  payload.append(11) // sf
  payload.append(5) // cr
  return payload
}
