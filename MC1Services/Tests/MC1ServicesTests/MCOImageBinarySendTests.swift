import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("MCOimg binary channel sending")
struct MCOImageBinarySendTests {
  private let fixture = "im3:g/5WjAL`NA"

  enum SendPath: CaseIterable { case inline, pending, resend }

  @Test(arguments: SendPath.allCases)
  func `every channel send path emits v4 GROUP_DATA and never text`(path: SendPath) async throws {
    let radioID = UUID()
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = MessageService(session: session, dataStore: store, contactService: nil)
    try await store.saveDevice(.testDevice(radioID: radioID, nodeName: "救援隊", firmwareVersion: 12))
    let channel = ChannelDTO.testChannel(radioID: radioID, floodScope: .region("Alpha"))
    try await store.saveChannel(channel)
    let legacy = MessageDTO.testChannelMessage(radioID: radioID, channelIndex: channel.index, text: fixture)
    try await store.saveMessage(legacy)
    switch path {
    case .inline:
      _ = try await service.sendChannelMessage(text: fixture, channelIndex: channel.index, radioID: radioID)
    case .pending: try await service.sendPendingChannelMessage(messageID: legacy.id)
    case .resend: try await service.resendChannelMessage(messageID: legacy.id)
    }
    let call = try #require(await session.sendChannelDataInvocations.first)
    #expect(await session.sendChannelDataInvocations.count == 1)
    #expect(await session.sendChannelMessageInvocations.isEmpty)
    #expect(call.channelIndex == channel.index)
    #expect(call.dataType == 0x0120)
    #expect(call.floodScope == .scope(.region("Alpha")))
    let nameBytes = "救援隊".utf8.count
    #expect(call.payload.first == UInt8(nameBytes))
    let payload = Data(call.payload.dropFirst(1 + nameBytes))
    #expect(payload.first == 0x14)
    let original = try MCOImageCodec.appPayload(fromText: fixture)
    #expect(payload[1] == original[1])
    let document = try MCOImageV4Codec.decodeBody(Data(payload.dropFirst()))
    #expect(document.figures == [.raster(origin: .init(x: 0, y: 0), image:
      try MCOImageV3Codec.decodeBody(Data(original.dropFirst())))])
    let frame = try PacketBuilder.sendChannelData(channelIndex: channel.index, dataType: call.dataType, payload: call.payload)
    #expect(frame.prefix(5) == Data([0x3e, channel.index, 0xff, 0x20, 0x01]))
    #expect(frame.count == call.payload.count + 5)
  }

  @Test
  func `automatic attempts retain payload while user resend persists a refreshed v4 nonce`() async throws {
    let radioID = UUID()
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = MessageService(session: session, dataStore: store, contactService: nil)
    try await store.saveDevice(.testDevice(radioID: radioID, nodeName: "Radio"))
    let message = try await service.createPendingChannelMessage(text: fixture, channelIndex: 0, radioID: radioID)
    try await service.sendPendingChannelMessage(messageID: message.id)
    try await service.sendPendingChannelMessage(messageID: message.id)
    let firstCalls = await session.sendChannelDataInvocations
    #expect(firstCalls[0].payload == firstCalls[1].payload)
    let prepared = try await service.prepareImageForResend(message)
    #expect(prepared.text.hasPrefix("im4:"))
    #expect(try await store.fetchMessage(id: message.id)?.text == prepared.text)
    let expected = try MCOImageTransport.encode(text: prepared.text, senderName: "Radio")
    try await service.resendChannelMessage(messageID: message.id)
    try await service.resendChannelMessage(messageID: message.id, preserveTimestamp: true)
    let calls = await session.sendChannelDataInvocations
    #expect(calls[2].payload == expected)
    #expect(calls[3].payload == expected)
    #expect(calls[0].payload.dropFirst(8) == calls[2].payload.dropFirst(8))
    #expect(await session.sendChannelMessageInvocations.isEmpty)
  }

  @Test
  func `complete UTF8 binary budget accepts 165 and rejects 166 before admission or drain`() async throws {
    let radioID = UUID()
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = MessageService(session: session, dataStore: store, contactService: nil)
    let name = "救援隊"
    try await store.saveDevice(.testDevice(radioID: radioID, nodeName: name))
    let exact = try textWithPayloadSize(165, senderName: name)
    #expect(exact.utf8.count > ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: name.utf8.count))
    let message = try await service.createPendingChannelMessage(text: exact, channelIndex: 0, radioID: radioID)
    try await service.sendPendingChannelMessage(messageID: message.id)
    #expect(await session.sendChannelDataInvocations.first?.payload.count == 165)
    let oversized = try textWithPayloadSize(166, senderName: name)
    await #expect(throws: MessageServiceError.self) {
      try await service.createPendingChannelMessage(text: oversized, channelIndex: 0, radioID: radioID)
    }
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 0, limit: 10, offset: 0).count == 1)
    try await store.saveDevice(.testDevice(radioID: radioID, nodeName: name + "A"))
    await #expect(throws: MessageServiceError.self) {
      try await service.sendPendingChannelMessage(messageID: message.id)
    }
    #expect(await session.sendChannelDataInvocations.count == 1)
    #expect(await session.sendChannelMessageInvocations.isEmpty)
    #expect(try await store.fetchMessage(id: message.id)?.status == .failed)
  }

  @Test(arguments: ["im3:!", "im4:!", "im5:AA"])
  func `damaged and unsupported image text never falls through to channel text`(text: String) async throws {
    let radioID = UUID()
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = MessageService(session: session, dataStore: store, contactService: nil)
    try await store.saveDevice(.testDevice(radioID: radioID))
    await #expect(throws: (any Error).self) {
      try await service.sendChannelMessage(text: text, channelIndex: 0, radioID: radioID)
    }
    #expect(await session.sendChannelDataInvocations.isEmpty)
    #expect(await session.sendChannelMessageInvocations.isEmpty)
  }

  @Test
  func `firmware rejection marks failed without a text fallback`() async throws {
    let radioID = UUID()
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = MessageService(session: session, dataStore: store, contactService: nil)
    try await store.saveDevice(.testDevice(radioID: radioID))
    let message = try await service.createPendingChannelMessage(text: fixture, channelIndex: 0, radioID: radioID)
    await session.setSendChannelDataError(MeshCoreError.deviceError(code: 1))
    await #expect(throws: MessageServiceError.self) {
      try await service.sendPendingChannelMessage(messageID: message.id)
    }
    #expect(await session.sendChannelDataInvocations.count == 1)
    #expect(await session.sendChannelMessageInvocations.isEmpty)
    #expect(try await store.fetchMessage(id: message.id)?.status == .failed)
  }

  @Test
  func `ordinary channel text remains a text command`() async throws {
    let session = MockMeshCoreSession()
    let service = MessageService(session: session, dataStore: MockPersistenceStore(), contactService: nil)
    _ = try await service.sendChannelMessage(text: "hello", channelIndex: 0, radioID: UUID())
    #expect(await session.sendChannelMessageInvocations.first?.text == "hello")
    #expect(await session.sendChannelDataInvocations.isEmpty)
  }

  @Test(arguments: [MeshCoreError.notConnected, .deviceError(code: 3)])
  @MainActor
  func `persisted legacy image replays as identical binary after disconnect`(failure: MeshCoreError) async throws {
    let store = PersistenceStore(modelContainer: try PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    try await store.saveDevice(.testDevice(radioID: radioID, nodeName: "Radio"))
    try await store.saveChannel(ChannelDTO.testChannel(radioID: radioID, index: 0))
    let message = MessageDTO.testChannelMessage(radioID: radioID, text: fixture, status: .pending)
    try await store.saveMessage(message)
    let envelope = ChannelMessageEnvelope(messageID: message.id, channelIndex: 0, isResend: false,
                                         messageText: message.text, messageTimestamp: message.timestamp, localNodeName: "Radio")
    _ = try await store.insertPendingSendAssigningSequence(PendingSendDTO(envelope: envelope, radioID: radioID))
    let firstSession = MockMeshCoreSession()
    await firstSession.setSendChannelDataError(failure)
    let firstService = MessageService(session: firstSession, dataStore: store, contactService: nil,
                                      config: .init(poolBackoff: .init(attemptCap: 0)))
    let firstQueue = ChatSendQueueService(
      radioID: radioID, dataStore: store, messageService: firstService,
      channelService: ChannelService(session: firstSession, dataStore: store, rxLogService: nil), reactionService: ReactionService()
    )
    await firstQueue.hydrate()
    try await waitUntil("Binary image should reach the disconnected transport") {
      await !firstSession.sendChannelDataInvocations.isEmpty
    }
    await firstQueue.shutdown()
    await firstQueue.awaitDrainCompletion()
    #expect(try await store.hasPendingSend(messageID: message.id))
    let replaySession = MockMeshCoreSession()
    let replayService = MessageService(session: replaySession, dataStore: store, contactService: nil)
    let replayQueue = ChatSendQueueService(
      radioID: radioID, dataStore: store, messageService: replayService,
      channelService: ChannelService(session: replaySession, dataStore: store, rxLogService: nil), reactionService: ReactionService()
    )
    await replayQueue.hydrate()
    await replayQueue.awaitDrainCompletion()
    await replayQueue.shutdown()
    #expect(await replaySession.sendChannelDataInvocations.first?.payload == firstSession.sendChannelDataInvocations.first?.payload)
    #expect(await firstSession.sendChannelMessageInvocations.isEmpty)
    #expect(await replaySession.sendChannelMessageInvocations.isEmpty)
    #expect(try await store.fetchMessage(id: message.id)?.status == .sent)
    #expect(try await store.hasPendingSend(messageID: message.id) == false)
  }

  @Test(arguments: SendPath.allCases)
  func `binary TABLE_FULL retries the same payload before success`(path: SendPath) async throws {
    let radioID = UUID()
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    try await store.saveDevice(.testDevice(radioID: radioID, nodeName: "Radio"))
    let service = MessageService(session: session, dataStore: store, contactService: nil,
                                 config: .init(poolBackoff: .init(baseDelay: 0, jitterRange: 1...1)))
    await session.setSendChannelDataErrors([.deviceError(code: 3), nil])
    let message = try await service.createPendingChannelMessage(text: fixture, channelIndex: 0, radioID: radioID)
    switch path {
    case .inline: _ = try await service.sendChannelMessage(text: fixture, channelIndex: 0, radioID: radioID)
    case .pending: try await service.sendPendingChannelMessage(messageID: message.id)
    case .resend: try await service.resendChannelMessage(messageID: message.id)
    }
    let calls = await session.sendChannelDataInvocations
    #expect(calls.count == 2)
    #expect(calls[0].payload == calls[1].payload)
    #expect(await session.sendChannelMessageInvocations.isEmpty)
    #expect(ChatSendQueueService.isTransientChannelMessageError(MeshCoreError.deviceError(code: 3), isImage: true))
    #expect(!ChatSendQueueService.isTransientChannelMessageError(MeshCoreError.deviceError(code: 3), isImage: false))
  }

  @Test
  func `transparent v3 pixels remain clear when wrapped in a v4 document`() throws {
    let image = MCOImageRaster(width: 2, height: 2, profile: 0, pixels: [0, 1, 1, 0], transparentColor: 1)
    let body = try MCOImageV3Codec.encode(image, nonce: 42)
    let document = try MCOImageV4Codec.decodeBody(MCOImageV4Codec.wrappingV3Body(body))
    #expect(document.backgroundColor == nil)
    #expect(document.figures == [.raster(origin: .init(x: 0, y: 0), image: image)])
  }

  private func textWithPayloadSize(_ size: Int, senderName: String) throws -> String {
    let payload = try MCOImageCodec.channelAppPayload(fromText: fixture)
    for count in 1...200 {
      var length = MCOImageBitWriter()
      length.varUint(count)
      let tail = Data([1]) + length.data + Data(repeating: 0x74, count: count)
      let text = try MCOImageCodec.text(fromAppPayload: payload + tail)
      if try MCOImageTransport.encode(text: text, senderName: senderName).count == size { return text }
    }
    throw MCOImageCodecError.damaged
  }
}
