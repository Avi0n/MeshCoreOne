import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

@Suite("Channel image reactions")
@MainActor
struct SyncCoordinatorChannelImageReactionTests {
  /// Matches `ReactionService`'s pending cap. One more entry evicts the oldest.
  private static let pendingReactionCap = 100

  @Test
  func `Reactions that match a stored channel image do not evict a pending text reaction`() async throws {
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
      senderName: "Ada",
      text: "Plain channel text",
      timestamp: textTimestamp
    )
    #expect(matches.count == 1)
    #expect(matches.first?.parsed.emoji == "🔥")
    #expect(try await services.dataStore.fetchReactions(for: image.id).isEmpty)
  }

  @Test
  func `A channel image arrival drops a reaction queued for that image`() async throws {
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
    let expectedText = try MCOImageCodec.text(fromAppPayload: appPayload)
    try await queueReaction(
      on: services.reactionService,
      emoji: "🔥",
      targetSender: sender,
      targetText: "Plain channel text",
      targetTimestamp: 1_700_000_100,
      channelIndex: channel.index,
      radioID: radioID
    )
    try await queueReaction(
      on: services.reactionService,
      emoji: "👍",
      targetSender: sender,
      targetText: expectedText,
      targetTimestamp: 0,
      channelIndex: channel.index,
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
      data: Data([UInt8(sender.utf8.count)]) + Data(sender.utf8) + appPayload,
      snr: 6.25
    )
    let handler = try #require(await poller.capturedChannelDatagramHandler)
    let scope = try #require(try await store.captureReceiveScope(radioID: radioID, link: ReceiveScope.Lifetime()))
    await handler(packet, channel, .live, scope, nil, Date())

    let imageReaction = services.reactionService.buildReactionText(
      emoji: "👍",
      targetSender: sender,
      targetText: expectedText,
      targetTimestamp: 0
    )
    let imageParsed = try #require(ReactionParser.parse(imageReaction))
    #expect(await services.reactionService.findTargetMessage(parsed: imageParsed, channelIndex: channel.index) == nil)

    // Filling back to the cap evicts the text reaction only if the image key is still queued.
    for index in 0..<(Self.pendingReactionCap - 1) {
      try await queueReaction(
        on: services.reactionService,
        emoji: "👀",
        targetSender: "Filler",
        targetText: "filler \(index)",
        targetTimestamp: 1_700_000_200,
        channelIndex: channel.index,
        radioID: radioID
      )
    }

    let matches = await services.reactionService.indexMessage(
      id: UUID(),
      channelIndex: channel.index,
      senderName: sender,
      text: "Plain channel text",
      timestamp: 1_700_000_100
    )
    #expect(matches.count == 1)
    let messages = try await store.fetchMessages(radioID: radioID, channelIndex: channel.index)
    let image = try #require(messages.first)
    #expect(try await store.fetchReactions(for: image.id).isEmpty)
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
