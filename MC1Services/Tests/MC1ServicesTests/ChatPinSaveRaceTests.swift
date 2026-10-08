import Foundation
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("Chat pin save race", .serialized)
struct ChatPinSaveRaceTests {
  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func makeContactDTO(
    radioID: UUID,
    isFavorite: Bool,
    isPinned: Bool = false,
    name: String = "Ada"
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: isFavorite ? ContactFlags.favorite.rawValue : 0,
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
      isFavorite: isFavorite,
      isPinned: isPinned,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  @Test
  func `pin saved during setContactFavorite survives the later save`() async throws {
    let store = try await createTestStore()
    let session = MockMeshCoreSession()
    let contact = makeContactDTO(radioID: UUID(), isFavorite: false, isPinned: false)
    try await store.saveContact(contact)
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    let contactID = contact.id
    await session.setChangeContactFlagsHold { @Sendable in
      try await store.setContactPinned(contactID, isPinned: true)
    }

    try await service.setContactFavorite(contact.id, isFavorite: true)

    let stored = try await store.fetchContact(id: contact.id)
    #expect(stored?.isPinned == true)
    #expect(stored?.isFavorite == true)
    #expect((stored?.flags ?? 0) & 0x01 == 0x01)
  }

  @Test
  func `pin saved during setTelemetryPermissions survives the later save`() async throws {
    let store = try await createTestStore()
    let session = MockMeshCoreSession()
    let contact = makeContactDTO(radioID: UUID(), isFavorite: false, isPinned: false)
    try await store.saveContact(contact)
    let service = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    let contactID = contact.id
    await session.setChangeContactFlagsHold { @Sendable in
      try await store.setContactPinned(contactID, isPinned: true)
    }

    try await service.setTelemetryPermissions(contact.id, granted: true)

    let stored = try await store.fetchContact(id: contact.id)
    #expect(stored?.isPinned == true)
    #expect(stored?.isFavorite == false)
    #expect((stored?.flags ?? 0) & 0x0E == 0x0E)
    #expect((stored?.flags ?? 0) & 0x01 == 0)
  }

  @Test
  func `setContactPinned false leaves favorite unchanged`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let store = try await createTestStore()
    let favorite = makeContactDTO(radioID: UUID(), isFavorite: true, name: "Ada")
    try await store.saveContact(favorite)
    try await store.performChatPinMigration(defaults: defaults)

    try await store.setContactPinned(favorite.id, isPinned: false)

    let stored = try await store.fetchContact(id: favorite.id)
    #expect(stored?.isPinned == false)
    #expect(stored?.isFavorite == true)
  }
}
