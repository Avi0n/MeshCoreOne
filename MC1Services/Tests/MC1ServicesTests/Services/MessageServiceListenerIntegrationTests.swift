import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("MessageService listener integration")
struct MessageServiceListenerIntegrationTests {
  @Test
  func `listener flips message to .delivered after a flood of non-matching events`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    let radioID = UUID()
    let messageID = UUID()
    let contactID = UUID()
    let ackCode = Data([0xAB, 0xCD, 0xEF, 0x12])

    try await dataStore.saveMessage(
      MessageDTO.testDirectMessage(
        id: messageID,
        radioID: radioID,
        contactID: contactID,
        status: .sent
      )
    )
    await service.setPendingAckForTest(
      PendingAck(
        messageID: messageID,
        contactID: contactID,
        ackCodes: [ackCode],
        sentAt: Date(),
        timeout: 30
      )
    )
    await service.startEventMonitoring()
    await service.waitForSubscriberCount(1)

    let session = await service.sessionForTest
    for i in 0..<500 {
      await session.dispatchForTesting(.advertisement(publicKey: Data([UInt8(i % 256)])))
    }
    await session.dispatchForTesting(.acknowledgement(code: ackCode, tripTime: 1234))

    try await waitForStatus(.delivered, messageID: messageID, dataStore: dataStore)

    let stored = try await dataStore.fetchMessage(id: messageID)
    #expect(stored?.status == .delivered)
    await service.stopEventMonitoring()
  }

  @Test
  func `listener restart after disconnect/reconnect still observes ACKs`() async throws {
    let (service, dataStore) = try await MessageService.createForTesting()
    let radioID = UUID()
    let messageID = UUID()
    let contactID = UUID()
    let ackCode = Data([0x11, 0x22, 0x33, 0x44])

    try await dataStore.saveMessage(
      MessageDTO.testDirectMessage(
        id: messageID,
        radioID: radioID,
        contactID: contactID,
        status: .sent
      )
    )
    await service.setPendingAckForTest(
      PendingAck(
        messageID: messageID,
        contactID: contactID,
        ackCodes: [ackCode],
        sentAt: Date(),
        timeout: 30
      )
    )

    await service.startEventMonitoring()
    await service.waitForSubscriberCount(1)
    await service.stopEventMonitoring()
    await service.waitForSubscriberCount(0)
    await service.startEventMonitoring()
    await service.waitForSubscriberCount(1)

    let session = await service.sessionForTest
    await session.dispatchForTesting(.acknowledgement(code: ackCode, tripTime: 200))

    try await waitForStatus(.delivered, messageID: messageID, dataStore: dataStore)

    let stored = try await dataStore.fetchMessage(id: messageID)
    #expect(stored?.status == .delivered)
    await service.stopEventMonitoring()
  }

  private func waitForStatus(
    _ expected: MessageStatus,
    messageID: UUID,
    dataStore: PersistenceStore,
    timeout: Duration = .milliseconds(500)
  ) async throws {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while ContinuousClock.now < deadline {
      if let m = try await dataStore.fetchMessage(id: messageID), m.status == expected {
        return
      }
      try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("Message did not reach \(expected) within \(timeout)")
  }
}
