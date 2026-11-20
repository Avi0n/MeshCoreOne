import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Verified channel send", .timeLimit(.minutes(1)))
struct VerifiedChannelSendTests {
  private static let timestamp = Date(timeIntervalSince1970: 1_700_000_000)
  private static let channel: UInt8 = 1
  private static let channelName = "Ops"
  private static let expectedSecret = Data(repeating: 0xAA, count: 16)
  private static let otherSecret = Data(repeating: 0xBB, count: 16)
  private static let namedScope = FloodScope.region("Alpha")
  private static let commandTimeout: TimeInterval = 5

  @Test
  func `matching channel occupancy sends unscoped message`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }

    let send = harness.send(floodScope: nil)
    try await harness.expectGetChannelAndReply(
      channelInfoPacket(index: Self.channel, name: Self.channelName, secret: Self.expectedSecret)
    )
    try await harness.ack(Self.channelMessage)
    try await send.value

    let sent = await harness.transport.sentData
    #expect(sent.contains(Self.channelMessage))
    #expect(sent.allSatisfy { $0.first != CommandCode.setFloodScope.rawValue })
  }

  @Test
  func `matching channel occupancy sends scoped message and restores baseline`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }

    let send = harness.send(floodScope: .scope(Self.namedScope))
    try await harness.expectGetChannelAndReply(
      channelInfoPacket(index: Self.channel, name: Self.channelName, secret: Self.expectedSecret)
    )
    try await harness.ack(PacketBuilder.setFloodScope(Self.namedScope.scopeKey()))
    try await harness.ack(Self.channelMessage)
    try await harness.ack(Self.baselineReset)
    try await send.value

    let scopeFrames = await harness.transport.sentData.filter {
      $0.first == CommandCode.setFloodScope.rawValue
    }
    #expect(scopeFrames == [
      PacketBuilder.setFloodScope(Self.namedScope.scopeKey()),
      Self.baselineReset
    ])
  }

  @Test
  func `wrong channel secret fails with occupancy mismatch without sending message`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }

    let send = harness.send(floodScope: nil)
    try await harness.expectGetChannelAndReply(
      channelInfoPacket(index: Self.channel, name: Self.channelName, secret: Self.otherSecret)
    )

    let error = await #expect(throws: MeshCoreError.self) { try await send.value }
    guard case let .invalidResponse(expected, got) = error else {
      Issue.record("Expected invalidResponse, got \(String(describing: error))")
      return
    }
    #expect(expected == "unchanged channel occupancy")
    #expect(got == "different or missing channel occupancy")
    #expect(await harness.transport.sentData.allSatisfy {
      $0.first != CommandCode.sendChannelMessage.rawValue
    })
  }

  @Test
  func `notFound on getChannel fails with occupancy mismatch without sending message`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }

    let send = harness.send(floodScope: nil)
    try await harness.expectGetChannel()
    await harness.transport.simulateError(code: ErrorCode.notFound.rawValue)

    let error = await #expect(throws: MeshCoreError.self) { try await send.value }
    guard case let .invalidResponse(expected, got) = error else {
      Issue.record("Expected invalidResponse, got \(String(describing: error))")
      return
    }
    #expect(expected == "unchanged channel occupancy")
    #expect(got == "different or missing channel occupancy")
    #expect(await harness.transport.sentData.allSatisfy {
      $0.first != CommandCode.sendChannelMessage.rawValue
    })
  }

  @Test
  func `cancellation before send stops before writing channel message`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }

    await harness.transport.holdNextSend()
    let blocker = Task {
      try await harness.session.sendLogin(to: Data(repeating: 0x11, count: 32), password: "room")
    }
    try await waitUntil("blocker login send should park in transport") {
      await harness.transport.sendIsWaiting
    }

    let send = harness.send(floodScope: nil)
    try await waitUntil("verified send should queue behind blocker") {
      await harness.session.requestResponseSerializer.waiterCountForTest == 1
    }
    send.cancel()

    await harness.transport.releaseHeldSend()
    try await waitUntil("login command subscriber should register") {
      await harness.session.subscriberCountForTest >= 1
    }
    await harness.transport.simulateReceive(Self.messageSent)
    _ = try await blocker.value

    await #expect(throws: CancellationError.self) { try await send.value }
    #expect(await harness.transport.sentData.allSatisfy {
      $0.first != CommandCode.getChannel.rawValue
        && $0.first != CommandCode.sendChannelMessage.rawValue
    })
  }

  private static var channelMessage: Data {
    PacketBuilder.sendChannelMessage(channel: channel, text: "hello", timestamp: timestamp)
  }

  private static var baselineReset: Data {
    PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())
  }

  private static var messageSent: Data {
    Data([ResponseCode.messageSent.rawValue, 1, 2, 3, 4, 5, 50, 0, 0, 0])
  }

  private func channelInfoPacket(index: UInt8, name: String, secret: Data) -> Data {
    var packet = Data([ResponseCode.channelInfo.rawValue, index])
    var nameBytes = Array(name.utf8.prefix(32))
    while nameBytes.count < 32 {
      nameBytes.append(0)
    }
    packet.append(contentsOf: nameBytes)
    packet.append(secret)
    return packet
  }

  private struct Harness {
    let session: MeshCoreSession
    let transport: MockTransport

    static func started() async throws -> Harness {
      let transport = MockTransport()
      let session = MeshCoreSession(
        transport: transport,
        configuration: SessionConfiguration(defaultTimeout: commandTimeout, clientIdentifier: "MCTst")
      )
      let harness = Harness(session: session, transport: transport)
      let start = Task { try await session.start() }
      try await waitUntil("appStart frame should send") { await transport.sentData.count >= 1 }
      await transport.simulateReceive(selfInfoPacket)
      try await start.value
      return harness
    }

    func send(floodScope: ResolvedFloodScope?) -> Task<Void, Error> {
      Task {
        try await session.sendVerifiedChannelMessage(
          channel: VerifiedChannelSendTests.channel,
          text: "hello",
          timestamp: VerifiedChannelSendTests.timestamp,
          floodScope: floodScope,
          expectedSecret: VerifiedChannelSendTests.expectedSecret
        )
      }
    }

    func expectGetChannel() async throws {
      let expected = PacketBuilder.getChannel(index: VerifiedChannelSendTests.channel)
      try await waitUntil("getChannel frame should send") {
        await transport.sentData.last == expected
      }
      try await waitUntil("event subscriber should register") {
        await session.subscriberCountForTest >= 1
      }
    }

    func expectGetChannelAndReply(_ packet: Data) async throws {
      try await expectGetChannel()
      await transport.simulateReceive(packet)
    }

    func ack(_ expected: Data) async throws {
      try await waitUntil("Expected frame should send") {
        await transport.sentData.last == expected
      }
      try await waitUntil("event subscriber should register") {
        await session.subscriberCountForTest >= 1
      }
      await transport.simulateOK()
    }
  }
}

private let selfInfoPacket: Data = {
  var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
  packet.append(Data(repeating: 1, count: 32))
  packet.append(Data(repeating: 0, count: 12))
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
  packet.append(contentsOf: [7, 5])
  packet.append(contentsOf: "Test".utf8)
  return packet
}()
