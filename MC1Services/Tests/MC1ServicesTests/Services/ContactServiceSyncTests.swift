import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

/// Service-level coverage for `ContactService.syncContacts` after it was restructured to
/// batch-persist in a single transaction. Uses the real in-memory `PersistenceStore` so the
/// optimized `batchSaveContacts` override (not the protocol default) is exercised end to end.
@Suite("ContactService Sync Tests")
struct ContactServiceSyncTests {
  private func publicKey(_ byte: UInt8) -> Data {
    Data(repeating: byte, count: ProtocolLimits.publicKeySize)
  }

  private func meshContact(_ keyByte: UInt8, name: String, lastModified: Date = Date(timeIntervalSince1970: 0)) -> MeshContact {
    let key = publicKey(keyByte)
    return MeshContact(
      id: key.uppercaseHexString(),
      publicKey: key,
      type: .chat,
      flags: ContactFlags(rawValue: 0),
      outPathLength: 0,
      outPath: Data(),
      advertisedName: name,
      lastAdvertisement: Date(timeIntervalSince1970: 0),
      latitude: 0,
      longitude: 0,
      lastModified: lastModified
    )
  }

  private func contactFrame(_ keyByte: UInt8, name: String) -> ContactFrame {
    ContactFrame(
      publicKey: publicKey(keyByte),
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: name,
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
  }

  @Test
  func `Full sync persists all device contacts and prunes locals not on device`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    // A stale local contact that the device no longer reports.
    _ = try await store.saveContact(radioID: radioID, from: contactFrame(0xDD, name: "Stale"))

    let session = MockMeshCoreSession()
    let lastModified = Date(timeIntervalSince1970: 1_700_000_000)
    await session.setStubbedContacts([
      meshContact(0xAA, name: "Alice", lastModified: lastModified),
      meshContact(0xBB, name: "Bob")
    ])

    let service = ContactService(session: session, dataStore: store, syncCoordinator: nil, cleanupCoordinator: nil)
    let result = try await service.syncContacts(radioID: radioID, since: nil)

    #expect(result.contactsReceived == 2)
    #expect(result.isIncremental == false)
    #expect(result.lastSyncTimestamp == UInt32(lastModified.timeIntervalSince1970))

    let stored = try await store.fetchContacts(radioID: radioID)
    #expect(Set(stored.map(\.name)) == ["Alice", "Bob"])
    // The stale contact was pruned on full sync.
    #expect(try await store.fetchContact(radioID: radioID, publicKey: publicKey(0xDD)) == nil)
  }

  @Test
  func `Incremental sync upserts without pruning unseen locals`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    // A local contact not present in this incremental batch must survive.
    _ = try await store.saveContact(radioID: radioID, from: contactFrame(0xDD, name: "Existing"))

    let session = MockMeshCoreSession()
    await session.setStubbedContacts([meshContact(0xAA, name: "Alice")])

    let service = ContactService(session: session, dataStore: store, syncCoordinator: nil, cleanupCoordinator: nil)
    let result = try await service.syncContacts(radioID: radioID, since: Date(timeIntervalSince1970: 100))

    #expect(result.contactsReceived == 1)
    #expect(result.isIncremental == true)

    let stored = try await store.fetchContacts(radioID: radioID)
    #expect(Set(stored.map(\.name)) == ["Alice", "Existing"])
  }
}
