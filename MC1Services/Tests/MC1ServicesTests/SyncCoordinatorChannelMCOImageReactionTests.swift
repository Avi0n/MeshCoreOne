import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

@Suite("Channel MCOimg reactions")
@MainActor
struct SyncCoordinatorChannelMCOImageReactionTests {
  /// Matches `ReactionService`'s pending cap. One more entry evicts the oldest.
  private static let pendingReactionCap = 100

  @Test
  func `Reactions that match a stored channel MCOimg do not evict a pending text reaction`() async throws {
    let radioID = UUID()
    let session = MeshCoreSession(transport: SimulatorMockTransport())
    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    defer { Task { await services.tearDown() } }

    let timestamp: UInt32 = 1_700_000_000
    let channelIndex: UInt8 = 0
    let image = MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: channelIndex,
      text: "im4:pixel",
      timestamp: timestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
      direction: .incoming,
      status: .delivered,
      senderNodeName: "Ada"
    )
    try await services.dataStore.saveMessage(image)

    let textTimestamp: UInt32 = 1_700_000_100
    try await queueReaction(
      on: services.reactionService,
      emoji: "🔥",
      targetSender: "Ada",
      targetText: "Plain channel text",
      targetTimestamp: textTimestamp,
      channelIndex: channelIndex,
      radioID: radioID
    )

    let imageReaction = services.reactionService.buildReactionText(
      emoji: "👍",
      targetSender: "Ada",
      targetText: image.text,
      targetTimestamp: image.reactionTimestamp
    )
    let coordinator = SyncCoordinator()
    let receiveTime = Date(timeIntervalSince1970: TimeInterval(timestamp))
    for _ in 0..<Self.pendingReactionCap {
      let consumed = await coordinator.handleChannelReaction(
        text: imageReaction,
        channelIndex: channelIndex,
        senderNodeName: "Bea",
        selfNodeName: "Local",
        receiveTime: receiveTime,
        radioID: radioID,
        dependencies: services.syncDependencies
      )
      #expect(consumed)
    }

    let matches = await services.reactionService.indexMessage(
      id: UUID(),
      channelIndex: channelIndex,
      target: ChannelReactionTarget(senderName: "Ada", text: "Plain channel text", timestamp: textTimestamp)
    )
    #expect(matches.count == 1)
    #expect(matches.first?.parsed.emoji == "🔥")
    #expect(try await services.dataStore.fetchReactions(for: image.id).count == 1)
  }

  @Test
  func `A reaction queued before an MCOimg attaches when the MCOimg is indexed`() async throws {
    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    try await store.saveDevice(.testDevice(id: radioID))
    let channel = ChannelDTO.testChannel(radioID: radioID, index: 2)
    try await store.saveChannel(channel)
    let services = ServiceContainer(
      session: MeshCoreSession(transport: SimulatorMockTransport()),
      dataStore: store,
      radioID: radioID
    )
    services.notificationService.isSuppressingNotifications = true
    defer { Task { await services.tearDown() } }

    let sender = "Ada"
    let appPayload = Data([0x14, 0, 0, 0, 0])
    try await queueReaction(
      on: services.reactionService,
      emoji: "🔥",
      targetSender: sender,
      targetText: "Plain channel text",
      targetTimestamp: 1_700_000_100,
      channelIndex: channel.index,
      radioID: radioID
    )
    let packetData = Data([UInt8(sender.utf8.count)]) + Data(sender.utf8) + appPayload
    let imageHash = ReactionParser.generateUntimedHash(packetData)
    let imageReaction = "@[\(sender)]👍\n\(imageHash)"
    let imageParsed = try #require(ReactionParser.parse(imageReaction))
    await services.reactionService.queuePendingReaction(
      parsed: imageParsed,
      channelIndex: channel.index,
      senderNodeName: "Bea",
      rawText: imageReaction,
      radioID: radioID
    )

    let poller = MockMessagePollingService()
    let coordinator = SyncCoordinator()
    await coordinator.wireMessageHandlers(
      dependencies: services.syncDependencies.with(dataStore: store, messagePollingService: poller),
      radioID: radioID
    )
    let packet = ChannelDatagram(
      channelIndex: channel.index,
      pathLength: 2,
      dataType: MCOImageTransport.applicationDataType,
      data: packetData,
      snr: 6.25
    )
    let handler = try #require(await poller.capturedChannelDatagramHandler)
    let scope = try #require(try await store.captureReceiveScope(radioID: radioID, link: ReceiveScope.Lifetime()))
    await handler(packet, channel, .live, scope, nil, Date())

    let messages = try await store.fetchMessages(radioID: radioID, channelIndex: channel.index)
    let image = try #require(messages.first)
    #expect(try await store.fetchReactions(for: image.id).count == 1)
    #expect(await services.reactionService.findTargetMessage(parsed: imageParsed, channelIndex: channel.index) == image.id)

    let matches = await services.reactionService.indexMessage(
      id: UUID(),
      channelIndex: channel.index,
      target: ChannelReactionTarget(senderName: sender, text: "Plain channel text", timestamp: 1_700_000_100)
    )
    #expect(matches.count == 1)
  }

  @Test
  func `A reaction that arrives after a stored MCOimg attaches`() async throws {
    let radioID = UUID()
    let session = MeshCoreSession(transport: SimulatorMockTransport())
    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    defer { Task { await services.tearDown() } }

    let sender = "Ada"
    let text = try MCOImageCodec.text(fromAppPayload: Data([0x14, 0, 0, 0, 0]))
    var image = MessageDTO.testChannelMessage(
      radioID: radioID,
      channelIndex: 0,
      text: text,
      timestamp: 1_000_000,
      createdAt: Date(timeIntervalSince1970: 1_000_000),
      direction: .incoming,
      status: .delivered,
      senderNodeName: sender
    )
    image.senderTimestamp = 0
    try await services.dataStore.saveMessage(image)
    let hash = try ReactionParser.generateUntimedHash(MCOImageTransport.dataField(text: text, senderName: sender))
    let reaction = "@[\(sender)]👍\n\(hash)"
    let consumed = await SyncCoordinator().handleChannelReaction(
      text: reaction,
      channelIndex: 0,
      senderNodeName: "Bea",
      selfNodeName: "Local",
      receiveTime: Date(),
      radioID: radioID,
      dependencies: services.syncDependencies
    )
    #expect(consumed)
    #expect(try await services.dataStore.fetchReactions(for: image.id).count == 1)
  }

  private func queueReaction(
    on service: ReactionService,
    emoji: String,
    targetSender: String,
    targetText: String,
    targetTimestamp: UInt32,
    channelIndex: UInt8,
    radioID: UUID
  ) async throws {
    let text = service.buildReactionText(
      emoji: emoji,
      targetSender: targetSender,
      targetText: targetText,
      targetTimestamp: targetTimestamp
    )
    let parsed = try #require(ReactionParser.parse(text))
    await service.queuePendingReaction(
      parsed: parsed,
      channelIndex: channelIndex,
      senderNodeName: "Bea",
      rawText: text,
      radioID: radioID
    )
  }
}
