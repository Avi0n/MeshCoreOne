import CommonCrypto
import CryptoKit
import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

@Suite("MCOimg receive persistence")
@MainActor
struct MCOImageReceiveIntegrationTests {
  @Test(arguments: [
    (initialSync: true, v4: false),
    (initialSync: false, v4: true)
  ])
  func `channel datagram is persisted with unknown sender time and conversation callbacks`(
    initialSync: Bool, v4: Bool
  ) async throws {
    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    try await store.saveDevice(.testDevice(id: radioID, firmwareVersion: 11))
    let channel = ChannelDTO.testChannel(radioID: radioID, index: 2)
    try await store.saveChannel(channel)
    let services = ServiceContainer(
      session: MeshCoreSession(transport: SimulatorMockTransport()),
      dataStore: store,
      radioID: radioID
    )
    services.notificationService.isSuppressingNotifications = true
    defer { Task { await services.tearDown() } }
    let poller = MockMessagePollingService()
    let coordinator = SyncCoordinator()
    await coordinator.wireMessageHandlers(
      dependencies: services.syncDependencies.with(dataStore: store, messagePollingService: poller),
      radioID: radioID
    )
    let sender = "Peer:名"
    let v3Payload = try #require(Data(base64Encoded: "E3oBjQBCvgE="))
    let appPayload = v4 ? Data([0x14, 0, 0, 0, 0]) : v3Payload
    let expectedText = try MCOImageCodec.text(fromAppPayload: appPayload)
    let packet = ChannelDatagram(
      channelIndex: channel.index,
      pathLength: 2,
      dataType: MCOImageTransport.applicationDataType,
      data: Data([UInt8(sender.utf8.count)]) + Data(sender.utf8) + appPayload,
      snr: 6.25
    )
    let anchor = Date().addingTimeInterval(-60)
    let before = Date()
    let stream = coordinator.dataEvents()
    let handler = try #require(await poller.capturedChannelDatagramHandler)
    let scope = try #require(try await store.captureReceiveScope(radioID: radioID, link: ReceiveScope.Lifetime()))
    await handler(packet, channel, initialSync ? .initialSync(anchor: anchor) : .live, scope, nil, before)
    coordinator.finishDataEvents()
    let messages = try await store.fetchMessages(radioID: radioID, channelIndex: channel.index)
    let message = try #require(messages.first)
    #expect(messages.count == 1)
    #expect(message.text == expectedText)
    #expect(message.senderNodeName == sender)
    #expect(message.timestampCorrected)
    #expect(message.senderTimestamp == 0)
    #expect(!message.hasWireSendTime)
    #expect(message.createdAt >= before)
    #expect(message.snr == packet.snr)
    #expect(message.pathLength == packet.pathLength)
    #expect(message.sortDate == (initialSync ? anchor : message.createdAt))
    #expect(try await store.fetchChannel(id: channel.id)?.unreadCount == 1)
    var sawAdmission = false
    for await event in stream {
      if case let .channelMessageReceived(received, _, _) = event {
        sawAdmission = received.id == message.id && received.text == message.text
      }
    }
    #expect(sawAdmission)
    if v4 {
      guard case .document = MCOImageCodec.content(from: message.text) else {
        Issue.record("Persisted v4 text must still decode as a document")
        return
      }
    } else {
      guard case .raster = MCOImageCodec.content(from: message.text) else {
        Issue.record("Persisted v3 text must still decode as a raster")
        return
      }
    }
  }

  @Test
  func `a recently blocked MCOimg is suppressed and an unblocked MCOimg is saved`() async throws {
    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    try await store.saveDevice(.testDevice(id: radioID, firmwareVersion: 11))
    let channel = ChannelDTO.testChannel(radioID: radioID, index: 2)
    try await store.saveChannel(channel)
    let savedChannel = try #require(try await store.fetchChannel(radioID: radioID, index: channel.index))
    let conversationID = try #require(savedChannel.textRulesID)
    let services = ServiceContainer(
      session: MeshCoreSession(transport: SimulatorMockTransport()),
      dataStore: store,
      radioID: radioID
    )
    services.notificationService.isSuppressingNotifications = true
    defer { Task { await services.tearDown() } }
    let poller = MockMessagePollingService()
    let coordinator = SyncCoordinator()
    await coordinator.wireMessageHandlers(
      dependencies: services.syncDependencies.with(dataStore: store, messagePollingService: poller),
      radioID: radioID
    )

    let sender = "Peer"
    let blockedPayload = Data([0x14, 0, 0, 0, 0])
    let allowedPayload = try #require(Data(base64Encoded: "E3oBjQBCvgE="))
    let blockedText = try MCOImageCodec.text(fromAppPayload: blockedPayload)
    let allowedText = try MCOImageCodec.text(fromAppPayload: allowedPayload)
    var preference = TextRulesPreference()
    preference.append(.init(phrase: "blocked", lookIn: .message, match: .contains))
    try await store.recordRecentlyBlockedMessage(RecentlyBlockedMessageDTO(
      identity: .init(
        radioID: radioID,
        conversationID: conversationID,
        senderPrefix: nil,
        senderTimestamp: 0,
        textType: MeshTextType.plain.rawValue,
        wireText: Data("\(sender): \(blockedText)".utf8)
      ),
      arrivedAt: Date(),
      body: "blocked",
      sender: nil,
      conversationName: savedChannel.name,
      radioName: nil,
      match: #require(preference.firstMatch(body: "blocked", sender: nil))
    ))

    let handler = try #require(await poller.capturedChannelDatagramHandler)
    let scope = try #require(try await store.captureReceiveScope(radioID: radioID, link: ReceiveScope.Lifetime()))
    await handler(
      datagram(channel: savedChannel, sender: sender, payload: blockedPayload),
      savedChannel, .live, scope, nil, Date()
    )
    await handler(
      datagram(channel: savedChannel, sender: sender, payload: allowedPayload),
      savedChannel, .live, scope, nil, Date()
    )

    let messages = try await store.fetchMessages(radioID: radioID, channelIndex: savedChannel.index)
    #expect(messages.map(\.text) == [allowedText])
    #expect(messages.first?.senderNodeName == sender)
  }

  @Test
  func `a sender Text Rule blocks a datagram MCOimg from that sender`() async throws {
    let opened = try await openChannel()
    defer { Task { await opened.services.tearDown() } }
    let channel = try #require(try await opened.store.fetchChannel(radioID: opened.radioID, index: opened.channel.index))
    var preference = TextRulesPreference()
    preference.setSelection(.all, for: TextRulesPreference.channelKind(isPublic: channel.isPublicChannel, name: channel.name))
    preference.append(.init(phrase: "Peer", lookIn: .sender, match: .wholeField))

    for sender in ["Peer", "Other"] {
      let packet = datagram(channel: channel, sender: sender, payload: Data([0x14, 0, 0, 0, 0]))
      await opened.handler(packet, channel, .live, opened.scope, preference, Date())
    }

    let messages = try await opened.store.fetchMessages(radioID: opened.radioID, channelIndex: channel.index)
    #expect(messages.map(\.senderNodeName) == ["Other"])
    #expect(try await opened.store.recentlyBlockedMessages().map(\.sender) == ["Peer"])
  }

  @Test
  func `two MCOimg pushes more than a minute apart stay one row and take the later RX path`() async throws {
    let opened = try await openChannel()
    defer { Task { await opened.services.tearDown() } }
    let payload = try v3Payload()
    let first = Date() // Planted RX rows use the current receive time.
    try await push(opened, payload: payload, at: first)
    try await plantRX(opened, payload: payload, path: [0x21])
    try await push(opened, payload: payload, at: first.addingTimeInterval(61))

    let messages = try await opened.store.fetchMessages(radioID: opened.radioID, channelIndex: opened.channel.index)
    let message = try #require(messages.first)
    #expect(messages.count == 1)
    #expect(message.senderTimestamp == 0)
    #expect(message.pathNodes == Data([0x21]))
    #expect(message.pathLength == 1)
    #expect(message.snr == 6.25)
    #expect(message.heardRepeats == 0)
    #expect(try await opened.store.fetchMessageRepeats(messageID: message.id).isEmpty)
  }

  @Test
  func `two identical MCOimg pushes outside the match window stay two rows`() async throws {
    let opened = try await openChannel()
    defer { Task { await opened.services.tearDown() } }
    let payload = try v3Payload()
    let first = Date(timeIntervalSince1970: 1_700_000_000)
    let outside = TimeInterval(MCOImageTransport.datagramMatchWindowSeconds + 1)
    try await push(opened, payload: payload, at: first)
    try await push(opened, payload: payload, at: first.addingTimeInterval(outside))

    let messages = try await opened.store.fetchMessages(radioID: opened.radioID, channelIndex: opened.channel.index)
    #expect(messages.count == 2)
  }

  @Test
  func `two MCOimg pushes with no RX row keep the push path length`() async throws {
    let opened = try await openChannel()
    defer { Task { await opened.services.tearDown() } }
    let payload = try v3Payload()
    let first = Date(timeIntervalSince1970: 1_700_000_000)
    try await push(opened, payload: payload, at: first)
    try await push(opened, payload: payload, at: first.addingTimeInterval(61))

    let messages = try await opened.store.fetchMessages(radioID: opened.radioID, channelIndex: opened.channel.index)
    let message = try #require(messages.first)
    #expect(messages.count == 1)
    #expect(message.senderNodeName == "Ada")
    #expect(message.senderTimestamp == 0)
    #expect(message.pathNodes == nil)
    #expect(message.pathLength == 2)
    #expect(message.snr == 6.25)
  }

  @Test
  func `a different MCOimg data field saves a second message`() async throws {
    let opened = try await openChannel()
    defer { Task { await opened.services.tearDown() } }
    let when = Date(timeIntervalSince1970: 1_700_000_000)
    try await push(opened, payload: v3Payload(), at: when)
    try await push(opened, payload: Data([0x14, 0, 0, 0, 0]), at: when.addingTimeInterval(61))

    let messages = try await opened.store.fetchMessages(radioID: opened.radioID, channelIndex: opened.channel.index)
    #expect(messages.count == 2)
    #expect(messages.allSatisfy { $0.senderTimestamp == 0 })
  }

  @Test
  func `an older receive-second MCOimg key absorbs the later push`() async throws {
    let opened = try await openChannel()
    defer { Task { await opened.services.tearDown() } }
    let payload = try v3Payload()
    let text = try MCOImageCodec.text(fromAppPayload: payload)
    let receiveSecond: UInt32 = 1_700_000_100
    var stored = MessageDTO.testChannelMessage(
      radioID: opened.radioID,
      channelIndex: opened.channel.index,
      text: text,
      timestamp: receiveSecond,
      direction: .incoming,
      pathLength: 1,
      senderNodeName: "Ada"
    )
    stored.senderTimestamp = 0
    stored.deduplicationKey = DeduplicationKey.contentBased(
      contactID: nil,
      channelIndex: opened.channel.index,
      senderNodeName: "Ada",
      timestamp: receiveSecond,
      content: text
    )
    stored.pathNodes = Data([0x11])
    try await opened.store.saveMessage(stored)

    try await push(opened, payload: payload, at: Date(timeIntervalSince1970: 1_700_000_200))

    let messages = try await opened.store.fetchMessages(radioID: opened.radioID, channelIndex: opened.channel.index)
    let message = try #require(messages.first)
    #expect(messages.count == 1)
    #expect(message.id == stored.id)
    #expect(message.senderTimestamp == 0)
    #expect(message.deduplicationKey?.contains("1700000100") == true)
    #expect(message.pathNodes == Data([0x11]))
    #expect(message.pathLength == 1)
  }

  @Test
  func `group text at wire time 0 with an im4 body stays beside the MCOimg`() async throws {
    let opened = try await openChannel()
    defer { Task { await opened.services.tearDown() } }
    let payload = Data([0x14, 0, 0, 0, 0])
    let text = try MCOImageCodec.text(fromAppPayload: payload)
    #expect(text.hasPrefix("im4:"))
    var textRow = MessageDTO.testChannelMessage(
      radioID: opened.radioID,
      channelIndex: opened.channel.index,
      text: text,
      timestamp: 0,
      direction: .incoming,
      pathLength: 1,
      senderNodeName: "Ada"
    )
    textRow.senderTimestamp = 0
    textRow.deduplicationKey = DeduplicationKey.contentBased(
      contactID: nil,
      channelIndex: opened.channel.index,
      senderNodeName: "Ada",
      timestamp: 0,
      content: text
    )
    textRow.pathNodes = Data([0x31])
    try await opened.store.saveMessage(textRow)

    try await push(opened, payload: payload, at: Date(timeIntervalSince1970: 1_700_000_300))

    let messages = try await opened.store.fetchMessages(radioID: opened.radioID, channelIndex: opened.channel.index)
    #expect(messages.count == 2)
    let kept = try #require(messages.first { $0.id == textRow.id })
    let datagram = try #require(messages.first { $0.id != textRow.id })
    #expect(kept.pathNodes == Data([0x31]))
    #expect(kept.pathLength == 1)
    #expect(kept.isDatagramMCOImage == false)
    #expect(datagram.senderTimestamp == 0)
    #expect(datagram.isDatagramMCOImage)
  }

  @Test
  func `an outgoing MCOimg saved after its RX row records that repeat`() async throws {
    let opened = try await openChannel()
    defer { Task { await opened.services.tearDown() } }
    let text = try outgoingChannelText()
    let payload = try MCOImageCodec.appPayload(fromText: text)
    try await plantRX(opened, payload: payload, path: [0x21], sender: "TestDevice")
    _ = try await opened.services.messageService.createPendingChannelMessage(
      text: text,
      channelIndex: opened.channel.index,
      radioID: opened.radioID
    )

    let messages = try await opened.store.fetchMessages(radioID: opened.radioID, channelIndex: opened.channel.index)
    let message = try #require(messages.first)
    let rxID = try #require(try await opened.store.fetchRxLogEntries(radioID: opened.radioID, limit: 10).first?.id)
    #expect(messages.count == 1)
    #expect(message.isOutgoing)
    #expect(message.heardRepeats == 1)
    #expect(try await opened.store.fetchMessageRepeats(messageID: message.id).map(\.rxLogEntryID) == [rxID])
  }

  private struct OpenedChannel {
    let radioID: UUID
    let store: PersistenceStore
    let channel: ChannelDTO
    let services: ServiceContainer
    /// The datagram handler captures the coordinator weakly.
    let coordinator: SyncCoordinator
    let handler: DatagramHandler
    let scope: ReceiveScope
    let secret: Data
  }

  private typealias DatagramHandler = @Sendable (
    ChannelDatagram, ChannelDTO?, DeliveryContext, ReceiveScope, TextRulesPreference?, Date
  ) async -> Void

  private func openChannel(secret: Data = Data(repeating: 0x42, count: 16)) async throws -> OpenedChannel {
    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    try await store.saveDevice(.testDevice(id: radioID, firmwareVersion: 11))
    let channel = ChannelDTO.testChannel(radioID: radioID, index: 2, secret: secret)
    try await store.saveChannel(channel)
    let services = ServiceContainer(
      session: MeshCoreSession(transport: SimulatorMockTransport()),
      dataStore: store,
      radioID: radioID
    )
    services.notificationService.isSuppressingNotifications = true
    let poller = MockMessagePollingService()
    let coordinator = SyncCoordinator()
    await coordinator.wireMessageHandlers(
      dependencies: services.syncDependencies.with(dataStore: store, messagePollingService: poller),
      radioID: radioID
    )
    let handler = try #require(await poller.capturedChannelDatagramHandler)
    let scope = try #require(try await store.captureReceiveScope(radioID: radioID, link: ReceiveScope.Lifetime()))
    return OpenedChannel(
      radioID: radioID,
      store: store,
      channel: channel,
      services: services,
      coordinator: coordinator,
      handler: handler,
      scope: scope,
      secret: secret
    )
  }

  private func push(_ opened: OpenedChannel, payload: Data, at date: Date) async throws {
    let text = try MCOImageCodec.text(fromAppPayload: payload)
    let field = try MCOImageTransport.dataField(text: text, senderName: "Ada")
    await opened.handler(
      ChannelDatagram(
        channelIndex: opened.channel.index,
        pathLength: 2,
        dataType: MCOImageTransport.applicationDataType,
        data: field,
        snr: 6.25
      ),
      opened.channel,
      .live,
      opened.scope,
      nil,
      date
    )
  }

  private func plantRX(
    _ opened: OpenedChannel,
    payload: Data,
    path: [UInt8],
    sender: String = "Ada"
  ) async throws {
    let text = try MCOImageCodec.text(fromAppPayload: payload)
    let field = try MCOImageTransport.dataField(text: text, senderName: sender)
    await opened.services.heardRepeatsService.configure(radioID: opened.radioID)
    await opened.services.rxLogService.startEventMonitoring(radioID: opened.radioID)
    await opened.services.rxLogService.updateChannels(
      secrets: [opened.channel.index: opened.secret],
      names: [opened.channel.index: opened.channel.name]
    )
    let wire = Self.encryptedGroupDatagram(
      dataType: MCOImageTransport.applicationDataType,
      data: field,
      secret: opened.secret
    )
    await opened.services.rxLogService.process(ParsedRxLogData(
      snr: 1,
      rssi: -80,
      rawPayload: wire,
      routeType: .flood,
      payloadType: .groupData,
      payloadVersion: 0,
      payloadTypeBits: 6,
      transportCode: nil,
      pathLength: UInt8(path.count),
      pathNodes: path,
      packetPayload: wire
    ))
  }

  private func v3Payload() throws -> Data {
    try #require(Data(base64Encoded: "E3oBjQBCvgE="))
  }

  /// Text `prepareChannelMessage` stores. The send path upgrades a v3 payload before the row is saved.
  private func outgoingChannelText() throws -> String {
    let source = try MCOImageCodec.text(fromAppPayload: v3Payload())
    let payload = try MCOImageCodec.channelAppPayload(fromText: source)
    return try MCOImageCodec.text(fromAppPayload: payload)
  }

  private func datagram(channel: ChannelDTO, sender: String, payload: Data) -> ChannelDatagram {
    ChannelDatagram(
      channelIndex: channel.index,
      pathLength: 2,
      dataType: MCOImageTransport.applicationDataType,
      data: Data([UInt8(sender.utf8.count)]) + Data(sender.utf8) + payload,
      snr: 6.25
    )
  }

  private static func encryptedGroupDatagram(dataType: UInt16, data: Data, secret: Data) -> Data {
    var plaintext = withUnsafeBytes(of: dataType.littleEndian) { Data($0) }
    plaintext.append(UInt8(data.count))
    plaintext.append(data)
    return encryptedRawPlaintext(plaintext, secret: secret)
  }

  private static func encryptedRawPlaintext(_ plaintext: Data, secret: Data) -> Data {
    var padded = plaintext
    let blockSize = kCCBlockSizeAES128
    let paddedCount = (padded.count + blockSize - 1) / blockSize * blockSize
    if padded.count < paddedCount {
      padded.append(Data(count: paddedCount - padded.count))
    }
    var ciphertext = Data(count: padded.count)
    var encryptedCount: size_t = 0
    let keyBytes = secret.prefix(kCCKeySizeAES128)
    let status = ciphertext.withUnsafeMutableBytes { outPtr in
      padded.withUnsafeBytes { inPtr in
        keyBytes.withUnsafeBytes { keyPtr in
          CCCrypt(
            CCOperation(kCCEncrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionECBMode),
            keyPtr.baseAddress, kCCKeySizeAES128,
            nil,
            inPtr.baseAddress, padded.count,
            outPtr.baseAddress, padded.count,
            &encryptedCount
          )
        }
      }
    }
    precondition(status == kCCSuccess, "test fixture encryption failed")
    let mac = Data(
      HMAC<SHA256>.authenticationCode(for: ciphertext, using: SymmetricKey(data: secret))
        .prefix(ChannelCrypto.macSize)
    )
    return Data([0x00]) + mac + ciphertext.prefix(encryptedCount)
  }
}
