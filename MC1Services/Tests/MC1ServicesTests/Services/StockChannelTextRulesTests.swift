import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Stock channel Text Rules", .timeLimit(.minutes(1)))
@MainActor
struct StockChannelTextRulesTests {
  enum Delivery: CaseIterable {
    case catchUp
    case live
  }

  @Test(arguments: Delivery.allCases)
  func `stock arrivals use the rules of the ordinary delivery channel`(delivery: Delivery) async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let filteredChannel = try await store.saveChannel(
      radioID: radioID,
      from: .init(index: 1, name: "Filtered", secret: Data(repeating: 0x11, count: ProtocolLimits.channelSecretSize))
    )
    let unfilteredChannel = try await store.saveChannel(
      radioID: radioID,
      from: .init(index: 2, name: "Unfiltered", secret: Data(repeating: 0x22, count: ProtocolLimits.channelSecretSize))
    )
    let filtered = try #require(try await store.fetchChannel(id: filteredChannel))
    let unfiltered = try #require(try await store.fetchChannel(id: unfilteredChannel))
    let filteredID = try #require(filtered.textRulesID)
    let unfilteredID = try #require(unfiltered.textRulesID)
    try await store.setTextRulesOverride(
      .on, kind: .privateChannel, key: .init(radioID: radioID, conversationID: filteredID)
    )
    try await store.setTextRulesOverride(
      .off, kind: .privateChannel, key: .init(radioID: radioID, conversationID: unfilteredID)
    )

    let matchingText = "repeat promotion"
    var preference = TextRulesPreference()
    preference.append(.init(phrase: matchingText, lookIn: .message, match: .wholeField))
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let services = ServiceContainer(
      session: session, dataStore: store, radioID: radioID, textRulesPreference: { [preference] in preference }
    )
    defer {
      Task {
        await services.tearDown()
        await session.stop()
      }
    }
    services.notificationService.isSuppressingNotifications = true
    let starting = Task { try await session.start() }
    try await waitUntil("radio handshake command was not sent") {
      await transport.sentData.last?.first == CommandCode.appStart.rawValue
    }
    await transport.simulateReceive(Self.selfInfoFrame())
    try await starting.value

    let coordinator = services.syncCoordinator
    let poller = services.messagePollingService
    await coordinator.wireMessageHandlers(dependencies: services.syncDependencies, radioID: radioID)
    let subscriptions = await session.subscriberCountForTest
    await poller.startMessageEventMonitoring(radioID: radioID)
    try await waitUntil("message polling did not subscribe") {
      await session.subscriberCountForTest > subscriptions
    }

    let timestamp = UInt32(Date.now.timeIntervalSince1970)
    let blocked = Self.channelFrame(slot: filtered.index, timestamp: timestamp, body: matchingText)
    let allowed = Self.channelFrame(slot: unfiltered.index, timestamp: timestamp, body: matchingText)

    switch delivery {
    case .catchUp:
      let poll = Task { try await poller.pollAllMessages() }
      for (offset, frame) in [blocked, allowed, Data([ResponseCode.noMoreMessages.rawValue])].enumerated() {
        try await waitUntil("stock get-message command was not sent") {
          await transport.sentData.count == offset + 2
        }
        #expect(await transport.sentData.last == Data([CommandCode.getMessage.rawValue]))
        await transport.simulateReceive(frame)
      }
      #expect(try await poll.value == 2)
    case .live:
      await transport.simulateReceive(blocked)
      await transport.simulateReceive(allowed)
      try await waitUntil("unfiltered channel arrival did not finish") {
        coordinator.conversationsVersion >= 1
      }
      #expect(await poller.waitForPendingHandlers(timeout: .seconds(2)))
    }

    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: filtered.index).isEmpty)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: unfiltered.index).map(\.text) == [matchingText])
    let drops = try await store.recentlyBlockedMessages()
    #expect(drops.map(\.body) == [matchingText])
    #expect(drops.first?.identity.conversationID == filteredID)
    #expect(drops.first?.conversationName == filtered.name)

    let unchanged = try #require(try await store.fetchChannel(radioID: radioID, index: filtered.index))
    #expect(unchanged.lastMessageDate == filtered.lastMessageDate)
    #expect(unchanged.unreadCount == filtered.unreadCount)
    #expect(unchanged.unreadMentionCount == filtered.unreadMentionCount)
    #expect(coordinator.conversationsVersion == 1)
  }

  private static func channelFrame(slot: UInt8, timestamp: UInt32, body: String) -> Data {
    let snrQuarters: UInt8 = 32
    let reserved: UInt8 = 0
    let pathLength: UInt8 = 0
    let textType = MeshTextType.plain.rawValue
    var frame = Data([ResponseCode.channelMessageReceivedV3.rawValue, snrQuarters, reserved, reserved, slot, pathLength, textType])
    frame.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Array($0) })
    frame.append(contentsOf: "Sender: \(body)".utf8)
    return frame
  }

  private static func selfInfoFrame() -> Data {
    let deviceType: UInt8 = 1
    let power: UInt8 = 22
    let publicKey = Data(repeating: 0xAB, count: 32)
    let locationAndFlags = Data(repeating: 0, count: 12)
    let frequency: UInt32 = 915_000
    let bandwidth: UInt32 = 125_000
    let spreadingFactor: UInt8 = 7
    let codingRate: UInt8 = 5
    var frame = Data([ResponseCode.selfInfo.rawValue, deviceType, power, power])
    frame.append(publicKey)
    frame.append(locationAndFlags)
    frame.append(contentsOf: withUnsafeBytes(of: frequency.littleEndian) { Array($0) })
    frame.append(contentsOf: withUnsafeBytes(of: bandwidth.littleEndian) { Array($0) })
    frame.append(contentsOf: [spreadingFactor, codingRate])
    frame.append(contentsOf: "Test".utf8)
    return frame
  }
}
