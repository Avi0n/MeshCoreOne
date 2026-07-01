import Foundation
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("Repeater unread-count corrective migration", .serialized)
struct RepeaterUnreadMigrationTests {
  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func makeContactDTO(
    radioID: UUID,
    type: ContactType,
    unreadCount: Int,
    unreadMentionCount: Int = 0,
    name: String = "Contact"
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: name,
      typeRawValue: type.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: unreadCount,
      unreadMentionCount: unreadMentionCount
    )
  }

  private func makeSessionDTO(
    radioID: UUID,
    role: RemoteNodeRole,
    unreadCount: Int,
    name: String = "Session"
  ) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: name,
      role: role,
      latitude: 0,
      longitude: 0,
      isConnected: false,
      permissionLevel: .guest,
      unreadCount: unreadCount,
      lastSyncTimestamp: 0
    )
  }

  @Test
  func `Repeater contact unread is zeroed; chat contact untouched`() async throws {
    let store = try await createTestStore()
    await store.resetRepeaterUnreadMigrationFlag()
    let radioID = UUID()

    let chatContact = makeContactDTO(radioID: radioID, type: .chat, unreadCount: 4, name: "Chat")
    try await store.saveContact(chatContact)
    let repeaterContact = makeContactDTO(
      radioID: radioID,
      type: .repeater,
      unreadCount: 42,
      unreadMentionCount: 3,
      name: "Repeater"
    )
    try await store.saveContact(repeaterContact)

    try await store.performRepeaterUnreadCountMigration()

    let (contacts, _, _) = try await store.getTotalUnreadCounts(radioID: radioID)
    #expect(contacts == 4, "chat contact unread must remain")

    let stored = try await store.fetchContact(id: repeaterContact.id)
    #expect(stored?.unreadCount == 0)
    #expect(stored?.unreadMentionCount == 0)
  }

  @Test
  func `Repeater-role session unread is zeroed; room session untouched`() async throws {
    let store = try await createTestStore()
    await store.resetRepeaterUnreadMigrationFlag()
    let radioID = UUID()

    let room = makeSessionDTO(radioID: radioID, role: .roomServer, unreadCount: 5, name: "Room")
    try await store.saveRemoteNodeSessionDTO(room)
    let repeaterSession = makeSessionDTO(radioID: radioID, role: .repeater, unreadCount: 99, name: "RepeaterAdmin")
    try await store.saveRemoteNodeSessionDTO(repeaterSession)

    try await store.performRepeaterUnreadCountMigration()

    let (_, _, rooms) = try await store.getTotalUnreadCounts(radioID: radioID)
    #expect(rooms == 5, "room session unread must remain")

    let stored = try await store.fetchRemoteNodeSession(id: repeaterSession.id)
    #expect(stored?.unreadCount == 0)
  }

  @Test
  func `Migration is idempotent — second run is a no-op`() async throws {
    let store = try await createTestStore()
    await store.resetRepeaterUnreadMigrationFlag()
    let radioID = UUID()

    let repeaterContact = makeContactDTO(radioID: radioID, type: .repeater, unreadCount: 7)
    try await store.saveContact(repeaterContact)

    try await store.performRepeaterUnreadCountMigration()

    // Re-introduce a non-zero unread on a repeater after the first migration. A second
    // run with the flag set must NOT touch it — otherwise we'd be re-running every launch.
    try await store.incrementUnreadCount(contactID: repeaterContact.id)
    try await store.performRepeaterUnreadCountMigration()

    let stored = try await store.fetchContact(id: repeaterContact.id)
    #expect(stored?.unreadCount == 1, "second run must be a no-op")
  }
}
