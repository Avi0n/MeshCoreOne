import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("MCOimg send integration")
struct MCOImageSendIntegrationTests {
  private let fixture = "im3:g/5WjAL`NA"

  @Test
  func `pending DM admission survives contact bookkeeping failure`() async throws {
    let store = MockPersistenceStore()
    await store.setStubbedUpdateContactLastMessageError(MessageServiceError.contactNotFound)
    let service = MessageService(session: MockMeshCoreSession(), dataStore: store, contactService: nil)
    let contact = ContactDTO.testContact(radioID: UUID())
    let message = try await service.createPendingMessage(text: fixture, to: contact)
    #expect(try await store.fetchMessage(id: message.id)?.text == fixture)
    #expect(message.status == .pending)
  }

  @Test
  func `channel drain revalidates sender name budget before issuing a radio command`() async throws {
    let radioID = UUID()
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = MessageService(session: session, dataStore: store, contactService: nil)
    try await store.saveDevice(.testDevice(id: radioID, nodeName: "A"))
    let text = String(repeating: "a", count: ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: 1))
    let message = try await service.createPendingChannelMessage(text: text, channelIndex: 0, radioID: radioID)
    try await store.saveDevice(.testDevice(id: radioID, nodeName: "Longer"))
    await #expect {
      try await service.sendPendingChannelMessage(messageID: message.id)
    } throws: { error in
      guard let serviceError = error as? MessageServiceError, case .messageTooLong = serviceError else { return false }
      return true
    }
    #expect(await session.sendChannelMessageInvocations.isEmpty)
    #expect(try await store.fetchMessage(id: message.id)?.status == .failed)
  }

  @Test
  func `image resend persists exactly the text that queue envelopes and ACK prediction use`() async throws {
    let store = MockPersistenceStore()
    let service = MessageService(session: MockMeshCoreSession(), dataStore: store, contactService: nil)
    let contact = ContactDTO.testContact(radioID: UUID())
    let original = try await service.createPendingMessage(text: fixture, to: contact)
    let refreshed = try await service.prepareImageForResend(original)
    let originalPayload = try MCOImageCodec.appPayload(fromText: original.text)
    let refreshedPayload = try MCOImageCodec.appPayload(fromText: refreshed.text)
    #expect(originalPayload.dropFirst(2) == refreshedPayload.dropFirst(2))
    #expect(refreshedPayload.count == originalPayload.count)
    #expect(try await store.fetchMessage(id: original.id)?.text == refreshed.text)
    #expect(refreshed.id == original.id)
  }

  @Test
  func `nonimage resend preparation preserves the original wire text`() async throws {
    let store = MockPersistenceStore()
    let service = MessageService(session: MockMeshCoreSession(), dataStore: store, contactService: nil)
    let contact = ContactDTO.testContact(radioID: UUID())
    let original = try await service.createPendingMessage(text: "hello", to: contact)
    #expect(try await service.prepareImageForResend(original) == original)
  }
}
