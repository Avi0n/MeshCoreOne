import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Channel resend repeats", .timeLimit(.minutes(1)))
struct ChannelResendRepeatTests {
  private static let originalTimestamp: UInt32 = 1_700_000_000
  private static let previousPaths: [Data] = [Data([0x11]), Data([0x22])]
  private static let earlyEchoPath = Data([0x33])
  private static let initialSendCount = 1

  @Test(arguments: [false, true])
  func `early echoes survive fresh resends and recovery`(preserveTimestamp: Bool) async throws {
    let fixture = try await makeFixture()
    defer { Task { await fixture.session.stop() } }
    let sendTask = Task {
      try await fixture.service.resendChannelMessage(
        messageID: fixture.message.id, preserveTimestamp: preserveTimestamp
      )
    }
    try await reachChannelSend(fixture)

    let beforeAcceptance = try #require(try await fixture.store.fetchMessage(id: fixture.message.id))
    let previousCount = preserveTimestamp ? Self.previousPaths.count : 0
    #expect(beforeAcceptance.heardRepeats == previousCount)
    #expect(try await fixture.store.fetchMessageRepeats(messageID: fixture.message.id).count == previousCount)
    if preserveTimestamp {
      #expect(beforeAcceptance.timestamp == Self.originalTimestamp)
    } else {
      #expect(beforeAcceptance.timestamp != Self.originalTimestamp)
    }

    _ = await fixture.repeats.processForRepeats(echo(
      message: beforeAcceptance, path: Self.earlyEchoPath
    ))
    await fixture.transport.simulateOK()
    _ = try await sendTask.value

    let stored = try #require(try await fixture.store.fetchMessage(id: fixture.message.id))
    let repeats = try await fixture.store.fetchMessageRepeats(messageID: fixture.message.id)
    let expectedPaths = preserveTimestamp ? Self.previousPaths + [Self.earlyEchoPath] : [Self.earlyEchoPath]
    #expect(repeats.map(\.pathNodes) == expectedPaths)
    #expect(stored.heardRepeats == repeats.count)
  }

  @Test
  func `a rejected new resend leaves its prepared repeat state and prior send count`() async throws {
    let fixture = try await makeFixture()
    defer { Task { await fixture.session.stop() } }
    let sendTask = Task { try await fixture.service.resendChannelMessage(messageID: fixture.message.id) }
    try await reachChannelSend(fixture)
    await fixture.transport.simulateError(code: ErrorCode.unsupportedCommand.rawValue)
    await #expect(throws: MessageServiceError.self) { try await sendTask.value }

    let stored = try #require(try await fixture.store.fetchMessage(id: fixture.message.id))
    #expect(stored.timestamp != Self.originalTimestamp)
    #expect(stored.status == .failed)
    #expect(stored.sendCount == Self.initialSendCount)
    #expect(stored.heardRepeats == 0)
    #expect(try await fixture.store.fetchMessageRepeats(messageID: fixture.message.id).isEmpty)
  }

  @Test
  func `failed preparation prevents radio commands`() async throws {
    let fixture = try await makeFixture()
    defer { Task { await fixture.session.stop() } }
    await fixture.store.setPrepareChannelMessageResendSaveFaultInjection { throw CocoaError(.fileWriteUnknown) }

    await #expect(throws: CocoaError.self) {
      try await fixture.service.resendChannelMessage(messageID: fixture.message.id)
    }

    #expect(await fixture.transport.sentData.count == 1)
    let stored = try #require(try await fixture.store.fetchMessage(id: fixture.message.id))
    #expect(stored.status == .failed)
  }

  @Test
  func `pool retries retain earlier echoes of the same wire packet`() async throws {
    let config = MessageServiceConfig(poolBackoff: PoolBackoffConfig(
      attemptCap: 1, baseDelay: 0, exponentBase: 1, jitterRange: 1...1
    ))
    let fixture = try await makeFixture(config: config)
    defer { Task { await fixture.session.stop() } }
    let sendTask = Task {
      try await fixture.service.resendChannelMessage(messageID: fixture.message.id, preserveTimestamp: true)
    }
    try await reachChannelSend(fixture)
    let prepared = try #require(try await fixture.store.fetchMessage(id: fixture.message.id))
    _ = await fixture.repeats.processForRepeats(echo(message: prepared, path: Self.earlyEchoPath))
    await fixture.transport.simulateError(code: FirmwareDeviceErrorCode.channelMessageNotFound)
    try await waitUntil("pool retry should reapply the channel scope") { await fixture.transport.sentData.count == 4 }
    await fixture.transport.simulateOK()
    try await waitUntil("pool retry should resend the same channel packet") { await fixture.transport.sentData.count == 5 }
    let packets = await fixture.transport.sentData
    #expect(packets[2] == packets[4])
    await fixture.transport.simulateOK()
    _ = try await sendTask.value

    let stored = try #require(try await fixture.store.fetchMessage(id: fixture.message.id))
    let expectedPaths = Self.previousPaths + [Self.earlyEchoPath]
    #expect(stored.timestamp == prepared.timestamp)
    #expect(stored.heardRepeats == expectedPaths.count)
    #expect(try await fixture.store.fetchMessageRepeats(messageID: fixture.message.id).map(\.pathNodes) == expectedPaths)
    #expect(stored.sendCount == Self.initialSendCount + 1)
  }

  private struct Fixture {
    let transport: MockTransport
    let session: MeshCoreSession
    let store: PersistenceStore
    let service: MessageService
    let repeats: HeardRepeatsService
    let message: MessageDTO
  }

  private func makeFixture(config: MessageServiceConfig = .default) async throws -> Fixture {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let startTask = Task { try await session.start() }
    try await waitUntil("session should request self info") { await transport.sentData.count == 1 }
    await transport.simulateReceive(selfInfoPacket())
    try await startTask.value
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let service = MessageService(session: session, dataStore: store, contactService: nil, config: config)
    let repeats = HeardRepeatsService(dataStore: store)
    let message = MessageDTO.testChannelMessage(
      timestamp: Self.originalTimestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(Self.originalTimestamp)),
      sendCount: Self.initialSendCount
    )
    try await store.saveMessage(message)
    await repeats.configure(radioID: message.radioID)
    for path in Self.previousPaths {
      _ = await repeats.processForRepeats(echo(message: message, path: path))
    }
    return Fixture(transport: transport, session: session, store: store, service: service, repeats: repeats, message: message)
  }

  private func reachChannelSend(_ fixture: Fixture) async throws {
    try await waitUntil("resend should set the channel flood scope") { await fixture.transport.sentData.count == 2 }
    #expect(await fixture.transport.sentData.last == PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey()))
    await fixture.transport.simulateOK()
    try await waitUntil("resend should send the channel packet") { await fixture.transport.sentData.count == 3 }
    #expect(await fixture.transport.sentData.last?.first == CommandCode.sendChannelMessage.rawValue)
  }

  private func echo(message: MessageDTO, path: Data) -> RxLogEntryDTO {
    RxLogEntryDTO(
      radioID: message.radioID,
      from: ParsedRxLogData(
        snr: 8, rssi: -70, rawPayload: Data([0x01]), routeType: .flood,
        payloadType: .groupText, payloadVersion: 0, payloadTypeBits: 5, transportCode: nil,
        pathLength: UInt8(path.count), pathNodes: Array(path), packetPayload: Data([0xAB])
      ),
      channelIndex: message.channelIndex,
      decryptStatus: .success,
      senderTimestamp: message.timestamp,
      decodedText: "Test: \(message.text)"
    )
  }

  private func selfInfoPacket() -> Data {
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 0x01, count: ProtocolLimits.publicKeySize))
    packet.append(Data(repeating: 0, count: MemoryLayout<Int32>.size * 2))
    packet.append(contentsOf: [0, 0, 0])
    for value: UInt32 in [915_000, 125_000] {
      packet.append(contentsOf: withUnsafeBytes(of: value.littleEndian) { Array($0) })
    }
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    return packet
  }
}
