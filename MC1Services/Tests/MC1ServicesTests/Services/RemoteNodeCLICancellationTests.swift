import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("RemoteNodeService CLI cancellation")
struct RemoteNodeCLICancellationTests {
  private static let publicKey = Data(repeating: 0xCC, count: 32)
  private static let requestTimeout: Duration = .seconds(5)
  private static let commandPayloadOffset = 13

  private struct Harness {
    let service: RemoteNodeService
    let session: MeshCoreSession
    let transport: MockTransport

    func stop() async {
      await service.stopEventMonitoring()
      await session.stop()
    }
  }

  @Test
  func `cancelling a radio command behind a live exchange prevents its write`() async throws {
    let harness = try await makeHarness()
    do {
      let blocker = Task { try await harness.session.getMessage() }
      try await waitUntil("message fetch should hold the request-response serializer") {
        await harness.transport.sentData.last == PacketBuilder.getMessage()
      }
      let blockedFrameCount = await harness.transport.sentData.count
      let destinationPrefix = Data(Self.publicKey.prefix(6))

      let cancelled = Task {
        try await harness.service.sendRadioCLICommand(
          to: Self.publicKey,
          command: "shutdown",
          timeout: Self.requestTimeout
        )
      }
      try await waitUntil("CLI request should be admitted while the message fetch is outstanding") {
        await harness.service.pendingCLIRequests[destinationPrefix]?.command == "shutdown"
      }
      #expect(await harness.transport.sentData.count == blockedFrameCount)

      cancelled.cancel()
      await #expect(throws: CancellationError.self) {
        try await cancelled.value
      }

      await harness.transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
      _ = try await blocker.value

      let next = Task {
        try await harness.service.sendRadioCLICommand(
          to: Self.publicKey,
          command: "board",
          timeout: Self.requestTimeout
        )
      }
      try await waitUntil("a later CLI command should write after the blocker completes") {
        await harness.transport.sentData.count > blockedFrameCount
      }
      let firstFrame = await harness.transport.sentData[blockedFrameCount]
      let firstCommand = try #require(command(in: firstFrame))
      #expect(
        firstCommand.body != "shutdown",
        "a Remote CLI command cancelled before its exchange starts must never be written"
      )
      if firstCommand.body == "shutdown" {
        await harness.transport.simulateReceive(makeMessageSentPacket())
      }

      try await waitUntil("the later CLI command should acquire the released slot") {
        await harness.transport.sentData.contains { command(in: $0)?.body == "board" }
      }
      let nextFrame = try #require(await harness.transport.sentData.first { command(in: $0)?.body == "board" })
      let nextCommand = try #require(command(in: nextFrame))
      await harness.transport.simulateReceive(makeMessageSentPacket())
      await harness.transport.simulateReceive(makeCLIReply(nextCommand.prefix + "Heltec"))
      #expect(try await next.value == "Heltec")
      #expect(await harness.transport.sentData.allSatisfy { command(in: $0)?.body != "shutdown" })
    } catch {
      await harness.stop()
      throw error
    }
    await harness.stop()
  }

  private func makeHarness() async throws -> Harness {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 5, clientIdentifier: "MCTst")
    )
    let start = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await start.value

    let dataStore = try await PersistenceStore.createTestDataStore(radioID: UUID())
    let service = RemoteNodeService(
      session: session,
      dataStore: dataStore,
      keychainService: KeychainService()
    )
    await service.startEventMonitoring()
    try await waitUntil("CLI response monitor should subscribe") {
      await session.dispatcher.subscriberCountForTest == 1
    }
    return Harness(service: service, session: session, transport: transport)
  }

  private func command(in frame: Data) -> (prefix: String, body: String)? {
    guard frame.first == CommandCode.sendMessage.rawValue,
          frame.count > Self.commandPayloadOffset else { return nil }
    return CLIResponse.splitEchoedPrefix(String(decoding: frame.dropFirst(Self.commandPayloadOffset), as: UTF8.self))
  }

  private func makeMessageSentPacket() -> Data {
    var packet = Data([ResponseCode.messageSent.rawValue, 0])
    packet.append(Data(repeating: 0xAA, count: 4))
    packet.append(withUnsafeBytes(of: UInt32(100).littleEndian) { Data($0) })
    return packet
  }

  private func makeCLIReply(_ text: String) -> Data {
    var packet = Data([ResponseCode.contactMessageReceived.rawValue])
    packet.append(Self.publicKey.prefix(6))
    packet.append(contentsOf: [0, MeshCore.TextType.cliData.rawValue])
    packet.append(withUnsafeBytes(of: UInt32(0).littleEndian) { Data($0) })
    packet.append(contentsOf: text.utf8)
    return packet
  }

  private func makeSelfInfoPacket() -> Data {
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 0x01, count: 32))
    packet.append(withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
    packet.append(withUnsafeBytes(of: Int32(0).littleEndian) { Data($0) })
    packet.append(contentsOf: [0, 0, 0, 0])
    packet.append(withUnsafeBytes(of: UInt32(915_000).littleEndian) { Data($0) })
    packet.append(withUnsafeBytes(of: UInt32(125_000).littleEndian) { Data($0) })
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    return packet
  }
}
