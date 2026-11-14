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
}
