import Foundation
@testable import MeshCore
import Testing

@Suite("Channel send scope serialization")
struct ChannelSendSerializationTests {
  private static let timestamp = Date(timeIntervalSince1970: 1_700_000_000)
  private static let firstChannel: UInt8 = 1
  private static let secondChannel: UInt8 = 2
  private static let firstScope = FloodScope.region("Alpha")
  private static let secondScope = FloodScope.region("Beta")
  private static let selectedScope = FloodScope.region("Gamma")

  @Test(arguments: [false, true])
  func `concurrent channel sends and scope writes keep each send together`(binary: Bool) async throws {
    let (session, transport) = try await startSession()
    let first = Task {
      try await send(session: session, binary: binary,
        channel: Self.firstChannel, text: "first", timestamp: Self.timestamp,
        floodScope: .scope(Self.firstScope)
      )
    }
    try await waitForFrameCount(2, transport: transport)
    #expect(await transport.sentData[1] == PacketBuilder.setFloodScope(Self.firstScope.scopeKey()))

    let second = Task {
      try await send(session: session, binary: binary,
        channel: Self.secondChannel, text: "second", timestamp: Self.timestamp,
        floodScope: .scope(Self.secondScope)
      )
    }
    let selection = Task { try await session.setFloodScope(Self.selectedScope) }
    try await waitForSerializerWaiters(2, session: session)
    #expect(await transport.sentData.count == 2)

    await transport.simulateOK()
    try await waitForFrameCount(3, transport: transport)
    #expect(await transport.sentData[2] == (try command(binary: binary,
      channel: Self.firstChannel, text: "first", timestamp: Self.timestamp
    )))
    await transport.simulateOK()
    try await waitForFrameCount(4, transport: transport)
    #expect(await transport.sentData[3] == PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey()))
    let framesBeforeRest = await transport.sentData.count
    await transport.simulateOK()
    try await first.value

    // Waiters can arrive in either order. The second channel send still owns its setup and reset.
    for offset in 1...4 {
      try await waitForFrameCount(framesBeforeRest + offset, transport: transport)
      await transport.simulateOK()
    }
    try await second.value
    try await selection.value

    let frames = await Array(transport.sentData.dropFirst())
    let secondScopeFrame = PacketBuilder.setFloodScope(Self.secondScope.scopeKey())
    let secondScopeIndex = try #require(frames.firstIndex(of: secondScopeFrame))
    #expect(frames[secondScopeIndex + 1] == (try command(binary: binary,
      channel: Self.secondChannel, text: "second", timestamp: Self.timestamp
    )))
    #expect(frames[secondScopeIndex + 2] == PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey()))
    let commandCode = binary ? CommandCode.sendChannelData : CommandCode.sendChannelMessage
    #expect(frames.filter { $0.first == commandCode.rawValue }.count == 2)
    await session.stop()
  }

  @Test
  func `scope rejection prevents the message and releases the next command`() async throws {
    let (session, transport) = try await startSession()
    let send = Task {
      try await session.sendChannelMessage(
        channel: Self.firstChannel, text: "hello", timestamp: Self.timestamp,
        floodScope: .scope(Self.firstScope)
      )
    }
    try await waitForFrameCount(2, transport: transport)
    let selection = Task { try await session.setFloodScope(Self.selectedScope) }
    try await waitForSerializerWaiters(1, session: session)
    let rejectedCode: UInt8 = 42
    await transport.simulateError(code: rejectedCode)
    let error = await #expect(throws: MeshCoreError.self) {
      try await send.value
    }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected scope rejection, got \(String(describing: error))")
      await session.stop()
      return
    }
    #expect(code == rejectedCode)

    try await waitForFrameCount(3, transport: transport)
    #expect(await transport.sentData[2] == PacketBuilder.setFloodScope(Self.selectedScope.scopeKey()))
    await transport.simulateOK()
    try await selection.value
    #expect(await transport.sentData.allSatisfy { $0.first != CommandCode.sendChannelMessage.rawValue })
    await session.stop()
  }

  @Test
  func `cancelling while queued writes neither scope nor channel message`() async throws {
    let (session, transport) = try await startSession()
    let selection = Task { try await session.setFloodScope(Self.selectedScope) }
    try await waitForFrameCount(2, transport: transport)
    let send = Task {
      try await session.sendChannelMessage(
        channel: Self.firstChannel, text: "cancelled", timestamp: Self.timestamp,
        floodScope: .scope(Self.firstScope)
      )
    }
    try await waitForSerializerWaiters(1, session: session)
    send.cancel()
    await transport.simulateOK()
    try await selection.value
    await #expect(throws: CancellationError.self) {
      try await send.value
    }
    #expect(await transport.sentData.count == 2)

    let following = Task { try await session.setFloodScope(Self.secondScope) }
    try await waitForFrameCount(3, transport: transport)
    #expect(await transport.sentData[2] == PacketBuilder.setFloodScope(Self.secondScope.scopeKey()))
    await transport.simulateOK()
    try await following.value
    await session.stop()
  }

  @Test(arguments: [false, true])
  func `cancellation drains its outstanding ACK before releasing another command`(afterScopeACK: Bool) async throws {
    let (session, transport) = try await startSession()
    let send = Task {
      try await session.sendChannelMessage(
        channel: Self.firstChannel, text: "cancelled", timestamp: Self.timestamp,
        floodScope: .scope(Self.firstScope)
      )
    }
    try await waitForFrameCount(2, transport: transport)
    if afterScopeACK {
      await transport.simulateOK()
      try await waitForFrameCount(3, transport: transport)
    }
    let outstandingCount = afterScopeACK ? 3 : 2
    send.cancel()
    let following = Task { try await session.setFloodScope(Self.selectedScope) }
    try await waitForSerializerWaiters(1, session: session)
    #expect(await transport.sentData.count == outstandingCount)

    await transport.simulateOK()
    let resetCount = outstandingCount + 1
    try await waitForFrameCount(resetCount, transport: transport)
    #expect(await transport.sentData[outstandingCount] == PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey()))
    await transport.simulateOK()
    if afterScopeACK {
      try await send.value
    } else {
      await #expect(throws: CancellationError.self) { try await send.value }
    }

    try await waitForFrameCount(resetCount + 1, transport: transport)
    #expect(await transport.sentData[resetCount] == PacketBuilder.setFloodScope(Self.selectedScope.scopeKey()))
    let advertisement = Task { try await session.sendAdvertisement(flood: true) }
    try await waitForSerializerWaiters(1, session: session)
    #expect(await transport.sentData.count == resetCount + 1,
            "The scope change must still wait for its own ACK")

    await transport.simulateOK()
    try await waitForFrameCount(resetCount + 2, transport: transport)
    #expect(await transport.sentData.last?.first == CommandCode.sendAdvertisement.rawValue)
    await transport.simulateOK()
    try await following.value
    try await advertisement.value
    let messageCount = await transport.sentData.filter { $0.first == CommandCode.sendChannelMessage.rawValue }.count
    #expect(messageCount == (afterScopeACK ? 1 : 0))
    #expect(await session.floodScopeRecoveryRequired == false)
    await session.stop()
  }

  private func command(binary: Bool, channel: UInt8, text: String, timestamp: Date) throws -> Data {
    if binary {
      return try PacketBuilder.sendChannelData(channelIndex: channel, dataType: 0x0120, payload: Data(text.utf8))
    }
    return PacketBuilder.sendChannelMessage(channel: channel, text: text, timestamp: timestamp)
  }

  private func send(
    session: MeshCoreSession, binary: Bool, channel: UInt8, text: String,
    timestamp: Date, floodScope: ResolvedFloodScope
  ) async throws {
    if binary {
      try await session.sendChannelData(
        channelIndex: channel, dataType: 0x0120, payload: Data(text.utf8), floodScope: floodScope
      )
    } else {
      try await session.sendChannelMessage(channel: channel, text: text, timestamp: timestamp, floodScope: floodScope)
    }
  }

  private func waitForFrameCount(_ count: Int, transport: MockTransport) async throws {
    try await waitUntil("Expected at least \(count) frames") {
      await transport.sentData.count >= count
    }
  }

  private func waitForSerializerWaiters(_ count: Int, session: MeshCoreSession) async throws {
    try await waitUntil("Expected \(count) waiting commands") {
      await session.requestResponseSerializer.waiterCountForTest == count
    }
  }

  private func startSession() async throws -> (MeshCoreSession, MockTransport) {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    let start = Task { try await session.start() }
    try await waitForFrameCount(1, transport: transport)
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 1, count: 32))
    packet.append(Data(repeating: 0, count: 12))
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    await transport.simulateReceive(packet)
    try await start.value
    return (session, transport)
  }
}
