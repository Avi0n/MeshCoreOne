import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Direct message command settlement", .timeLimit(.minutes(1)))
struct DirectMessageSendSettlementTests {
  @Test
  func `a rejection before the write is not submitted and leaves attribution unchanged`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport: transport)
    defer { Task { await session.stop() } }
    #expect(await session.requestResponseSerializer.responseAttributionIsUncertain() == false)

    await transport.rejectNextSendBeforeSubmission()
    let error = await #expect(throws: MeshCoreError.self) {
      try await session.sendMessage(to: Data(repeating: 0x11, count: 6), text: "hello", timestamp: Self.timestamp)
    }

    guard case .notConnected? = error else {
      Issue.record("Expected not submitted, got \(String(describing: error))")
      return
    }
    #expect(await session.requestResponseSerializer.responseAttributionIsUncertain() == false)
    #expect(await transport.sentData.count == 1)
  }

  @Test
  func `an accepted write with no frame is no reply and marks attribution uncertain`() async throws {
    let transport = MockTransport()
    let clock = TestClock()
    let session = try await startedSession(transport: transport, clock: clock, timeout: 5)
    defer { Task { await session.stop() } }
    let send = Task {
      try await session.sendMessage(to: Data(repeating: 0x11, count: 6), text: "hello", timestamp: Self.timestamp)
    }
    try await waitUntil("the direct message should be written") {
      await transport.sentData.count == 2
    }
    try await waitUntil("the reply wait should park on the injected clock") {
      clock.sleeperCount == 1
    }

    clock.advance(by: .seconds(5))
    let error = await #expect(throws: MeshCoreError.self) {
      try await send.value
    }

    guard case .timeout? = error else {
      Issue.record("Expected no reply, got \(String(describing: error))")
      return
    }
    #expect(await session.requestResponseSerializer.responseAttributionIsUncertain())
    #expect(await transport.sentData.count == 2)
  }

  @Test
  func `a write that is issued and then fails is no reply`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport: transport)
    defer { Task { await session.stop() } }
    await transport.holdNextSend()
    let send = Task {
      try await session.sendMessage(to: Data(repeating: 0x11, count: 6), text: "hello", timestamp: Self.timestamp)
    }
    try await waitUntil("the issued write should park") {
      await transport.sendIsWaiting
    }
    #expect(await transport.sentData.count == 2)
    await transport.failHeldSend()
    await transport.releaseHeldSend()

    let error = await #expect(throws: MeshCoreError.self) {
      try await send.value
    }
    guard case .timeout? = error else {
      Issue.record("Expected no reply, got \(String(describing: error))")
      return
    }
    #expect(await session.requestResponseSerializer.responseAttributionIsUncertain())
  }

  private static let timestamp = Date(timeIntervalSince1970: 1_700_000_000)

  private func startedSession(
    transport: MockTransport,
    clock: TestClock? = nil,
    timeout: TimeInterval = 10
  ) async throws -> MeshCoreSession {
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: timeout, clientIdentifier: "MCTst"),
      clock: clock ?? ContinuousClock()
    )
    let start = Task { try await session.start() }
    try await waitUntil("transport should send appStart") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await start.value
    return session
  }
}

private func makeSelfInfoPacket() -> Data {
  var payload = Data([ResponseCode.selfInfo.rawValue])
  payload.append(1)
  payload.append(UInt8(bitPattern: 22))
  payload.append(UInt8(bitPattern: 22))
  payload.append(Data(repeating: 0x01, count: 32))
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(contentsOf: [0, 0, 0, 0])
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) })
  payload.append(11)
  payload.append(5)
  return payload
}
