import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("SenderContactMatcher")
struct SenderContactMatcherTests {
  @Test
  func `exact name match returns the contact`() {
    let alice = makeContact(name: "Alice")
    let result = SenderContactMatcher.filter(contacts: [alice], senderName: "Alice")
    #expect(result.map(\.id) == [alice.id])
  }

  @Test
  func `case-insensitive match returns the contact`() {
    let alice = makeContact(name: "Alice")
    let result = SenderContactMatcher.filter(contacts: [alice], senderName: "ALICE")
    #expect(result.map(\.id) == [alice.id])
  }

  @Test
  func `non-matching name returns empty`() {
    let alice = makeContact(name: "Alice")
    let result = SenderContactMatcher.filter(contacts: [alice], senderName: "Bob")
    #expect(result.isEmpty)
  }

  @Test
  func `multiple contacts sharing a name all match`() {
    let alice1 = makeContact(name: "Alice")
    let alice2 = makeContact(name: "alice")
    let bob = makeContact(name: "Bob")
    let result = SenderContactMatcher.filter(contacts: [alice1, alice2, bob], senderName: "Alice")
    #expect(Set(result.map(\.id)) == Set([alice1.id, alice2.id]))
  }

  @Test
  func `leading or trailing whitespace does not match (no trimming)`() {
    let alice = makeContact(name: "Alice")
    let result = SenderContactMatcher.filter(contacts: [alice], senderName: " Alice ")
    #expect(result.isEmpty)
  }

  @Test
  func `excludeBlocked drops blocked contacts`() {
    let unblocked = makeContact(name: "Alice", isBlocked: false)
    let blocked = makeContact(name: "Alice", isBlocked: true)
    let result = SenderContactMatcher.filter(
      contacts: [unblocked, blocked],
      senderName: "Alice",
      excludeBlocked: true
    )
    #expect(result.map(\.id) == [unblocked.id])
  }

  @Test
  func `excludeBlocked=false keeps blocked contacts`() {
    let unblocked = makeContact(name: "Alice", isBlocked: false)
    let blocked = makeContact(name: "Alice", isBlocked: true)
    let result = SenderContactMatcher.filter(contacts: [unblocked, blocked], senderName: "Alice")
    #expect(Set(result.map(\.id)) == Set([unblocked.id, blocked.id]))
  }

  @Test
  func `empty contact list returns empty`() {
    let result = SenderContactMatcher.filter(contacts: [], senderName: "Alice")
    #expect(result.isEmpty)
  }

  // MARK: - Helper

  private func makeContact(name: String, isBlocked: Bool = false) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0, count: 32),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: isBlocked,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }
}
