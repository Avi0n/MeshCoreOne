import Foundation
@testable import MeshCore
import Testing

@Suite("Verified channel send serialization")
struct VerifiedChannelSendSerializationTests {
  private static let index: UInt8 = 1
  private static let timestamp = Date(timeIntervalSince1970: 1_700_000_000)
  private static let secretA = Data(repeating: 0xAA, count: 16)
  private static let secretB = Data(repeating: 0xBB, count: 16)
  private static let scope = ResolvedFloodScope.scope(.region("Test"))

  @Test(arguments: [false, true])
  func `a queued channel replacement is read before deciding whether to send`(scoped: Bool) async throws {
    let (session, transport) = try await startSession()
    defer { Task { await session.stop() } }
    let replacement = Task {
      try await session.setChannel(index: Self.index, name: "B", secret: Self.secretB)
    }
    try await waitForFrames(2, transport: transport)
    let send = verifiedSend(session, scoped: scoped)
    try await waitForWaiters(1, session: session)
    #expect(await transport.sentData.count == 2)
    await transport.simulateOK()
    try await replacement.value
    try await waitForFrames(3, transport: transport)
    #expect(await transport.sentData[2] == PacketBuilder.getChannel(index: Self.index))
    await transport.simulateReceive(channelPacket(secret: Self.secretB))
    await expectOccupancyMismatch(send)
    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.sendChannelMessage.rawValue })
    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.setFloodScope.rawValue })
    await session.stop()
  }

  @Test(arguments: [false, true])
  func `a replacement queued during validation waits until the verified send finishes`(scoped: Bool) async throws {
    let (session, transport) = try await startSession()
    defer { Task { await session.stop() } }
    let send = verifiedSend(session, scoped: scoped)
    try await waitForFrames(2, transport: transport)
    #expect(await transport.sentData[1] == PacketBuilder.getChannel(index: Self.index))
    let replacement = Task {
      try await session.setChannel(index: Self.index, name: "B", secret: Self.secretB)
    }
    try await waitForWaiters(1, session: session)
    await transport.simulateReceive(channelPacket(secret: Self.secretA))
    if scoped {
      try await waitForFrames(3, transport: transport)
      #expect(await transport.sentData[2].first == CommandCode.setFloodScope.rawValue)
      await transport.simulateOK()
    }
    let sendFrameCount = scoped ? 4 : 3
    try await waitForFrames(sendFrameCount, transport: transport)
    #expect(await transport.sentData[sendFrameCount - 1] == PacketBuilder.sendChannelMessage(
      channel: Self.index, text: "Only for A", timestamp: Self.timestamp
    ))
    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.setChannel.rawValue })
    await transport.simulateOK()
    try await send.value
    try await waitForFrames(sendFrameCount + 1, transport: transport)
    #expect(await transport.sentData[sendFrameCount] == PacketBuilder.setChannel(
      index: Self.index, name: "B", secret: Self.secretB
    ))
    await transport.simulateOK()
    try await replacement.value
    await session.stop()
  }

  @Test(arguments: [false, true])
  func `cancellation drains channel validation without writing scope or message`(scoped: Bool) async throws {
    let (session, transport) = try await startSession()
    defer { Task { await session.stop() } }
    let send = verifiedSend(session, scoped: scoped)
    try await waitForFrames(2, transport: transport)
    send.cancel()
    await #expect(throws: CancellationError.self) { try await send.value }
    let following = Task { try await session.setChannel(index: Self.index, name: "B", secret: Self.secretB) }
    try await waitForWaiters(1, session: session)
    #expect(await transport.sentData.count == 2)
    await transport.simulateReceive(channelPacket(secret: Self.secretA))
    try await waitForFrames(3, transport: transport)
    #expect(await transport.sentData[2].first == CommandCode.setChannel.rawValue)
    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.sendChannelMessage.rawValue })
    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.setFloodScope.rawValue })
    await transport.simulateOK()
    try await following.value
    await session.stop()
  }

  @Test
  func `a missing slot is an occupancy mismatch rather than channel send pool exhaustion`() async throws {
    let (session, transport) = try await startSession()
    defer { Task { await session.stop() } }
    let send = verifiedSend(session, scoped: false)
    try await waitForFrames(2, transport: transport)
    await transport.simulateError(code: ErrorCode.notFound.rawValue)
    await expectOccupancyMismatch(send)
    #expect(await transport.sentData.count == 2)
    await session.stop()
  }

  private func verifiedSend(_ session: MeshCoreSession, scoped: Bool) -> Task<Void, Error> {
    Task {
      try await session.sendVerifiedChannelMessage(
        channel: Self.index, text: "Only for A", timestamp: Self.timestamp,
        floodScope: scoped ? Self.scope : nil, expectedSecret: Self.secretA
      )
    }
  }

  private func expectOccupancyMismatch(_ send: Task<Void, Error>) async {
    let error = await #expect(throws: MeshCoreError.self) { try await send.value }
    guard case .invalidResponse? = error else {
      Issue.record("Expected an occupancy mismatch, got \(String(describing: error))")
      return
    }
  }

  private func channelPacket(secret: Data) -> Data {
    Data([ResponseCode.channelInfo.rawValue, Self.index]) + Data("Channel".utf8)
      + Data(repeating: 0, count: 25) + secret
  }

  private func waitForFrames(_ count: Int, transport: MockTransport) async throws {
    try await waitUntil("Expected \(count) command frames") { await transport.sentData.count >= count }
  }

  private func waitForWaiters(_ count: Int, session: MeshCoreSession) async throws {
    try await waitUntil("Expected \(count) serialized command waiters") {
      await session.requestResponseSerializer.waiterCountForTest == count
    }
  }

  private func startSession() async throws -> (MeshCoreSession, MockTransport) {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport, configuration: SessionConfiguration(defaultTimeout: 10))
    let start = Task { try await session.start() }
    try await waitForFrames(1, transport: transport)
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 1, count: 32))
    packet.append(Data(repeating: 0, count: 12))
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    packet.append(contentsOf: [7, 5])
    await transport.simulateReceive(packet)
    try await start.value
    return (session, transport)
  }
}
