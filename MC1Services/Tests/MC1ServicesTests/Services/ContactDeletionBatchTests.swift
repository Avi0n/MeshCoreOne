import Foundation
@testable import MC1Services
import MeshCore
import os
import Testing

@Suite("Contact deletion batching")
struct ContactDeletionBatchTests {
  @Test
  func `Full sync groups deletion updates while immediately cleaning each contact`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    var ids: Set<UUID> = []
    for byte: UInt8 in [2, 3, 4] {
      let contact = try await store.saveContact(radioID: radioID, from: frame(byte))
      ids.insert(contact.id)
    }
    let cleanup = RecordingCleanup()
    let service = ContactService(
      session: MockMeshCoreSession(), dataStore: store,
      syncCoordinator: nil, cleanupCoordinator: cleanup
    )

    _ = try await service.syncContacts(radioID: radioID, since: nil)

    #expect(try await store.fetchContacts(radioID: radioID).isEmpty)
    #expect(await cleanup.immediateIDs == ids)
    #expect(await cleanup.updateBatches == [ids])
    #expect(await cleanup.singleCleanupCount == 0)
  }

  @Test
  func `Full sync flushes committed deletions when the next save fails`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    for byte: UInt8 in [2, 3, 4] {
      _ = try await store.saveContact(radioID: radioID, from: frame(byte))
    }
    let saves = OSAllocatedUnfairLock(initialState: 0)
    await store.setDeleteContactsFaultInjection {
      let count = saves.withLock { value in
        value += 1
        return value
      }
      if count == 2 { throw TestError.saveFailed }
    }
    let cleanup = RecordingCleanup()
    let service = ContactService(
      session: MockMeshCoreSession(), dataStore: store,
      syncCoordinator: nil, cleanupCoordinator: cleanup
    )

    await #expect(throws: TestError.self) {
      try await service.syncContacts(radioID: radioID, since: nil)
    }

    #expect(try await store.fetchContacts(radioID: radioID).count == 2)
    let deletedIDs = await cleanup.immediateIDs
    #expect(deletedIDs.count == 1)
    #expect(await cleanup.updateBatches == [deletedIDs])
  }

  @Test
  func `Full sync checkpoints updates and flushes the remainder`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    for byte: UInt8 in 2...22 {
      _ = try await store.saveContact(radioID: radioID, from: frame(byte))
    }
    let cleanup = RecordingCleanup()
    let service = ContactService(
      session: MockMeshCoreSession(), dataStore: store,
      syncCoordinator: nil, cleanupCoordinator: cleanup
    )
    _ = try await service.syncContacts(radioID: radioID, since: nil)

    #expect(await cleanup.updateBatches.map(\.count) == [20, 1])
    #expect(await cleanup.immediateIDs.count == 21)
  }

  private enum TestError: Error { case saveFailed }

  private func frame(_ byte: UInt8) -> ContactFrame {
    ContactFrame(
      publicKey: Data(repeating: byte, count: ProtocolLimits.publicKeySize),
      type: .chat, flags: 0, outPathLength: 0, outPath: Data(),
      name: "Contact \(byte)", lastAdvertTimestamp: 0,
      latitude: 0, longitude: 0, lastModified: 0
    )
  }

  private actor RecordingCleanup: ContactCleanupHandling {
    var immediateIDs: Set<UUID> = []
    var updateBatches: [Set<UUID>] = []
    var singleCleanupCount = 0

    func handleCleanup(contactID: UUID, reason: ContactCleanupReason, publicKey: Data) {
      singleCleanupCount += 1
    }

    func handleDeletion(contactID: UUID, publicKey: Data) {
      immediateIDs.insert(contactID)
    }

    func flushDeletionUpdates(contactIDs: Set<UUID>) {
      updateBatches.append(contactIDs)
    }
  }
}
