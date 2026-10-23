import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Synced advert recency")
struct SyncedAdvertRecencyTests {
  private static let advert: UInt32 = 1_700_000_000

  @Test(arguments: [1_699_999_999, 1_700_000_001] as [UInt32])
  func `unchanged synced advert advances Last Heard without reducing it`(heard: UInt32) async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    try await store.saveContact(ContactDTO.testContact(
      radioID: radioID,
      lastAdvertTimestamp: Self.advert,
      lastHeardTimestamp: heard
    ))

    _ = try await (store as any ContactPersisting).batchSaveContacts(radioID: radioID, from: [frame(advert: Self.advert)])

    let contact = try #require(try await store.fetchContacts(radioID: radioID).first)
    #expect(contact.lastHeardTimestamp == max(heard, Self.advert))
  }

  @Test(arguments: [0, 1_577_836_799, UInt32.max] as [UInt32])
  func `invalid synced adverts preserve Last Heard and raw radio fields`(advert: UInt32) async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    try await store.saveContact(ContactDTO.testContact(radioID: radioID, lastHeardTimestamp: Self.advert))

    _ = try await (store as any ContactPersisting).batchSaveContacts(radioID: radioID, from: [frame(advert: advert)])

    let contact = try #require(try await store.fetchContacts(radioID: radioID).first)
    #expect(contact.lastHeardTimestamp == Self.advert)
    #expect(contact.lastAdvertTimestamp == advert)
    #expect(contact.lastModified == 42)
  }

  @Test
  func `synced advert preserves Discover reception`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let (discovered, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: frame(advert: 1))

    _ = try await (store as any ContactPersisting).batchSaveContacts(radioID: radioID, from: [frame(advert: Self.advert)])

    let updated = try #require(try await store.fetchContacts(radioID: radioID).first)
    let after = try #require(try await store.fetchDiscoveredNodes(radioID: radioID).first)
    #expect(updated.lastHeardTimestamp == Self.advert)
    #expect(after == discovered)
  }

  private func frame(advert: UInt32) -> ContactFrame {
    ContactFrame(
      publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Contact",
      lastAdvertTimestamp: advert,
      latitude: 0,
      longitude: 0,
      lastModified: 42
    )
  }
}
