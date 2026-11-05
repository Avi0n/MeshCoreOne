import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Plain-text send admission")
@MainActor
struct PlainTextSendGateTests {
  let senderKey = Data(repeating: 0xAB, count: 32)

  @Test
  func `invalidate cancels a waiter and a monitoring pause keeps the lease`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let container = try await ServiceContainer.forTesting(session: session)
    let owner = try await container.plainTextSendGate.acquire()
    let waiter = Task { try await container.plainTextSendGate.acquire() }
    try await waitUntil("second send is waiting") {
      await container.plainTextSendGate.waiterCountForTesting == 1
    }
    await container.stopEventMonitoring()
    #expect(await container.plainTextSendGate.waiterCountForTesting == 1)
    #expect(await container.plainTextSendGate.ownerLeaseIDForTesting == owner.id)
    await container.tearDown()
    await #expect(throws: CancellationError.self) { try await waiter.value }
  }

  @Test
  func `an ack during a suspended command does not admit the next send until the owner returns`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    harness.script.holdSendMessages(true)
    await harness.service.startEventMonitoring()
    await harness.service.waitForSubscriberCount(1)

    let contactA = try await harness.contact(byte: 0x21)
    let contactB = try await harness.contact(byte: 0x22)
    let first = Task { try await harness.service.sendDirectMessage(text: "held", to: contactA) }
    try await waitUntil("the command is on the wire") { await harness.sendCount() == 1 }
    let packet = try #require(await harness.sendPackets().first)
    await harness.session.dispatchForTesting(.acknowledgement(
      code: expectedAck(packet: packet, text: "held", sender: senderKey),
      tripTime: 40
    ))

    let second = Task { try await harness.service.sendDirectMessage(text: "next", to: contactB) }
    try await waitUntil("the competing send is waiting") {
      await harness.gate.waiterCountForTesting == 1
    }
    #expect(await harness.sendCount() == 1)

    harness.script.holdSendMessages(false)
    let accepted = try await first.value
    #expect(accepted.status == .sent || accepted.status == .delivered)
    try await waitUntil("the competing send starts after the owner returns") {
      await harness.sendCount() == 2
    }
    harness.script.holdSendMessages(false)
    _ = try await second.value
    let stored = try await harness.store.fetchMessages(contactID: contactA.id, limit: 1, offset: 0)
    #expect(stored.first?.status == .delivered)
  }

  @Test
  func `a single-attempt send returns sent and holds admission until its ack`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    await harness.service.startEventMonitoring()
    await harness.service.waitForSubscriberCount(1)
    let contactA = try await harness.contact(byte: 0x31)
    let contactB = try await harness.contact(byte: 0x32)
    let accepted = try await harness.service.sendDirectMessage(text: "once", to: contactA)
    #expect(accepted.status == .sent)
    #expect(await harness.service.pendingAckCount == 1)

    let second = Task { try await harness.service.sendDirectMessage(text: "later", to: contactB) }
    try await waitUntil("the next single-attempt send waits") {
      await harness.gate.waiterCountForTesting == 1
    }
    #expect(await harness.sendCount() == 1)

    let code = try #require(harness.script.codes().first)
    await harness.session.dispatchForTesting(.acknowledgement(code: code, tripTime: 15))
    try await waitUntil("the held ack admits the next send") { await harness.sendCount() == 2 }
    _ = try await second.value
    let delivered = try await harness.store.fetchMessage(id: accepted.id)
    #expect(delivered?.status == .delivered)
    let other = try await harness.store.fetchMessages(contactID: contactB.id, limit: 1, offset: 0)
    #expect(other.first?.status != .delivered || other.first?.id == accepted.id)
  }

  func makeRestartedQueue(_ harness: RadioHarness) -> ChatSendQueueService {
    let gate = PlainTextSendGate()
    let service = MessageService(
      session: harness.session,
      dataStore: harness.store,
      contactService: nil,
      config: MessageServiceConfig(minTimeout: 0),
      dmSendBudget: { .default },
      plainTextSendGate: gate
    )
    return ChatSendQueueService(
      radioID: harness.radioID,
      dataStore: harness.store,
      messageService: service,
      channelService: harness.channelService,
      reactionService: ReactionService(),
      plainTextSendGate: gate
    )
  }

  func quickReplyHandler(_ harness: RadioHarness) throws -> (handler: NotificationActionHandler, notifications: NotificationService) {
    let defaults = try #require(UserDefaults(suiteName: "plain-text-gate.\(UUID().uuidString)"))
    let notifications = NotificationService(
      notificationDefaults: defaults,
      systemOperations: .init(
        setBadgeCount: { _ in },
        deliveredRequests: { [] },
        removeDeliveredNotifications: { _ in }
      )
    )
    let handler = NotificationActionHandler(
      dataStore: harness.store,
      messageService: harness.service,
      notificationService: notifications,
      roomServerService: harness.roomService,
      syncCoordinator: SyncCoordinator()
    )
    handler.configure(isConnectionReady: { _ in true }, localNodeName: { "Test" })
    return (handler: handler, notifications: notifications)
  }

  func expectedAck(packet: Data, text: String, sender: Data) -> Data {
    let attempt = packet.count > 2 ? packet[2] : 0
    return AckCodeBuilder.expectedAck(
      timestamp: wireTimestamp(packet), attempt: attempt, text: text, senderPublicKey: sender
    )
  }

  func wireTimestamp(_ packet: Data) -> UInt32 {
    let raw = [UInt8](packet.subdata(in: 3..<7))
    return UInt32(raw[0])
      | (UInt32(raw[1]) << 8)
      | (UInt32(raw[2]) << 16)
      | (UInt32(raw[3]) << 24)
  }
}
