import Foundation
@testable import MC1Services
import Testing

@Suite("IncomingAvatarIdentity")
struct IncomingAvatarIdentityTests {
  @Test
  func `revision of nil or empty is nil`() {
    #expect(IncomingAvatarIdentity.revision(of: nil) == nil)
    #expect(IncomingAvatarIdentity.revision(of: Data()) == nil)
  }

  @Test
  func `resolve trims senderNodeName before table lookup`() {
    let photo = IncomingAvatarIdentity(
      name: "Alice",
      matchedContactID: UUID(),
      imageRevision: 1
    )
    let resolved = IncomingAvatarIdentity.resolve(
      senderNodeName: " Alice ",
      displayName: " Alice ",
      table: ["alice": photo]
    )
    #expect(resolved == photo)
  }

  @Test
  func `resolve does not use displayName as a table key`() {
    let photo = IncomingAvatarIdentity(
      name: "Alice",
      matchedContactID: UUID(),
      imageRevision: 1
    )
    let resolved = IncomingAvatarIdentity.resolve(
      senderNodeName: nil,
      displayName: "Alice",
      table: ["alice": photo]
    )
    #expect(resolved == IncomingAvatarIdentity.initials(name: "Alice"))
  }

  @Test
  func `resolve does not steal another contact photo`() {
    let alice = IncomingAvatarIdentity(
      name: "Alice",
      matchedContactID: UUID(),
      imageRevision: 1
    )
    let resolved = IncomingAvatarIdentity.resolve(
      senderNodeName: "Bob",
      displayName: "Bob",
      table: ["alice": alice]
    )
    #expect(resolved == IncomingAvatarIdentity.initials(name: "Bob"))
  }

  @Test
  func `resolve empty senderNodeName is initials`() {
    let photo = IncomingAvatarIdentity(
      name: "Alice",
      matchedContactID: UUID(),
      imageRevision: 1
    )
    let resolved = IncomingAvatarIdentity.resolve(
      senderNodeName: "",
      displayName: "Alice",
      table: ["alice": photo]
    )
    #expect(resolved == IncomingAvatarIdentity.initials(name: "Alice"))
  }
}
