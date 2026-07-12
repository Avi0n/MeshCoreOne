import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("MessageService DM post-send bookkeeping")
struct MessageServiceSendDMBookkeepingTests {
  @Test
  @MainActor
  func `sendDirectMessage keeps ACK tracking and does not report failure when post-send bookkeeping fails`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10)
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let service = MessageService(session: session, dataStore: dataStore, contactService: nil)

    let statusEvents = service.statusEvents()

    let contact = ContactDTO.testContact(radioID: UUID())

    let sendTask = Task {
      try await service.sendDirectMessage(text: "hi", to: contact)
    }

    try await waitUntil("send should issue CMD_SEND_TXT_MSG") {
      await transport.sentData.count == 2
    }

    // Delete the saved row mid-send so post-send bookkeeping fails after
    // the radio has already accepted the message.
    let saved = try await dataStore.fetchMessages(contactID: contact.id, limit: 10, offset: 0)
    let messageID = try #require(saved.first?.id)
    try await dataStore.deleteMessage(id: messageID)

    var msgSent = Data([ResponseCode.messageSent.rawValue])
    msgSent.append(0)
    msgSent.append(Data([0xAB, 0xCD, 0xEF, 0x12]))
    msgSent.append(uint32Bytes(5000))
    await transport.simulateReceive(msgSent)

    await #expect(throws: Error.self) {
      _ = try await sendTask.value
    }

    #expect(await service.pendingAckCount == 1,
            "post-send bookkeeping failure must keep the pendingAcks entry alive for the genuine ACK")
    #expect(await service.drainStatusEvents(statusEvents).failedIDs.isEmpty,
            "post-send bookkeeping failure must not report the DM as failed")
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(22)
    payload.append(22)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(int32Bytes(0))
    payload.append(int32Bytes(0))
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(uint32Bytes(915_000))
    payload.append(uint32Bytes(125_000))
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: "Test".utf8)

    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }

  private func int32Bytes(_ value: Double) -> Data {
    withUnsafeBytes(of: Int32(value.rounded()).littleEndian) { Data($0) }
  }

  private func uint32Bytes(_ value: UInt32) -> Data {
    withUnsafeBytes(of: value.littleEndian) { Data($0) }
  }
}
