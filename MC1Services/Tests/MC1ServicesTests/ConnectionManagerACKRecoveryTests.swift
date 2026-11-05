import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("ConnectionManager ACK recovery", .serialized)
@MainActor
struct ConnectionManagerACKRecoveryTests {
  @Test
  func `ACK buffered during app start resolves the restored owner before any retransmission`() async throws {
    let previousLogBuffer = DebugLogBuffer.shared
    defer { DebugLogBuffer.shared = previousLogBuffer }
    let env = try ConnectionManagerRadioTestHarness()
    env.manager.testNotificationSystemOperations = .init(
      setBadgeCount: { _ in }, deliveredRequests: { [] }, removeDeliveredNotifications: { _ in }
    )
    let radioID = UUID()
    try await env.manager.persistenceStore.saveDevice(.testDevice(
      id: env.deviceID, radioID: radioID, publicKey: env.ble.publicKey
    ))
    let contact = ContactDTO.testContact(radioID: radioID)
    try await env.manager.persistenceStore.saveContact(contact)
    await env.ble.setContact(contact.toContactFrame().toMeshContact())
    let message = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id)
    try await env.manager.persistenceStore.saveMessage(message)
    let code = Data([0xDE, 0xAD, 0xBE, 0xEF])
    let sentAt = Date().addingTimeInterval(-1)
    let row = PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id), radioID: radioID,
      ackCodes: [code], ackSentAt: sentAt, ackDeadline: sentAt.addingTimeInterval(60)
    )
    #expect(try await env.manager.persistenceStore.savePendingAcknowledgement(row, replacingPendingSendID: nil))
    var ack = Data([ResponseCode.ack.rawValue])
    ack.append(code)
    ack.append(contentsOf: [123, 0, 0, 0])
    await env.ble.setResponsePackets(for: .appStart, packets: [ack, selfInfoPacket(publicKey: env.ble.publicKey)])
    do {
      _ = try await env.connect(.bluetooth)
      let deadline = ContinuousClock.now.advanced(by: .seconds(5))
      while try await env.manager.persistenceStore.fetchMessage(id: message.id)?.status != .delivered {
        try #require(ContinuousClock.now < deadline, "The pre-start ACK must reach the restored owner")
        try await Task.sleep(for: .milliseconds(1))
      }
      #expect(try await env.manager.persistenceStore.fetchMessage(id: message.id)?.roundTripTime == 123)
      #expect(try await env.manager.persistenceStore.fetchPendingSends(radioID: radioID).isEmpty)
      #expect(await env.ble.sentData.allSatisfy { $0.first != CommandCode.sendMessage.rawValue })
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  private func selfInfoPacket(publicKey: Data) -> Data {
    var reply = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    reply.append(publicKey)
    reply.append(Data(repeating: 0, count: 12))
    for value: UInt32 in [915_000, 125_000] {
      reply.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
    }
    reply.append(contentsOf: [7, 5])
    reply.append(contentsOf: "Test Radio".utf8)
    return reply
  }
}
