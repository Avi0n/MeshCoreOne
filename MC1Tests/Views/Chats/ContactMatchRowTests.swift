import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Contact recency semantics")
struct ContactMatchRowTests {
  /// `recencyTimestamp` follows `lastModified`, which a path update or a
  /// favorite toggle bumps with no on-air advert. Anything that must mean
  /// "when did we last hear this node" reads `lastAdvertTimestamp` instead.
  @Test
  func `recencyTimestamp tracks lastModified not lastAdvertTimestamp`() {
    let lastAdvert: UInt32 = 1000
    let lastModified: UInt32 = 10000
    let contact = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xAB, count: 32),
      name: "Relay",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: lastAdvert,
      latitude: 0,
      longitude: 0,
      lastModified: lastModified,
      lastHeardTimestamp: lastAdvert,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )

    #expect(contact.recencyTimestamp == lastModified)
    #expect(contact.recencyTimestamp != contact.lastAdvertTimestamp)
  }
}
