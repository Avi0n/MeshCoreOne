import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("IncomingAvatarJPEGStore", .isolatedIncomingAvatarJPEGStore)
@MainActor
struct IncomingAvatarJPEGStoreTests {
  @Test
  func `replace stores JPEG only when matchedContactID owns the bytes`() {
    let contact = makeContact(name: "Maya", jpeg: Data("unique-jpeg".utf8))
    replace(contact)

    #expect(IncomingAvatarJPEGStore.data(for: contact.id) == contact.avatarImageData)
    #expect(IncomingAvatarJPEGStore.data(for: UUID()) == nil)
  }

  @Test
  func `nested isolated storage does not clobber the outer session`() {
    let outer = makeContact(name: "Maya", jpeg: Data("outer-jpeg".utf8))
    let inner = makeContact(name: "Rico", jpeg: Data("inner-jpeg".utf8))

    IncomingAvatarJPEGStore.$isolatedStorage.withValue(IncomingAvatarJPEGStore.Storage()) {
      replace(outer)
      #expect(IncomingAvatarJPEGStore.data(for: outer.id) == outer.avatarImageData)

      IncomingAvatarJPEGStore.$isolatedStorage.withValue(IncomingAvatarJPEGStore.Storage()) {
        replace(inner)
        #expect(IncomingAvatarJPEGStore.data(for: inner.id) == inner.avatarImageData)
        #expect(IncomingAvatarJPEGStore.data(for: outer.id) == nil)
      }

      #expect(IncomingAvatarJPEGStore.data(for: outer.id) == outer.avatarImageData)
      #expect(IncomingAvatarJPEGStore.data(for: inner.id) == nil)
    }
  }

  private func replace(_ contact: ContactDTO) {
    let identities = MessageBubbleConfiguration.incomingAvatarIdentities(from: [contact])
    IncomingAvatarJPEGStore.replace(contacts: [contact], identities: Array(identities.values))
  }

  private func makeContact(name: String, jpeg: Data) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xAA, count: ProtocolLimits.publicKeySize),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      avatarImageData: jpeg
    )
  }
}
