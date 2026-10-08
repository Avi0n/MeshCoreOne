import Foundation
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("Chat pin migration", .serialized)
struct ChatPinMigrationTests {
  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func makeContactDTO(
    radioID: UUID,
    isFavorite: Bool,
    isPinned: Bool = false,
    name: String,
    type: ContactType = .chat,
    lastMessageDate: Date? = nil
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: name,
      typeRawValue: type.rawValue,
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
      lastMessageDate: lastMessageDate,
      unreadCount: 0
    )
  }

  private func makeFrame(flags: UInt8, name: String) -> ContactFrame {
    ContactFrame(
      publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize),
      type: .chat,
      flags: flags,
      outPathLength: PacketBuilder.floodPathSentinel,
      outPath: Data(),
      name: name,
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
  }

  @Test
  func `favorite contact becomes pinned once`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    // UserDefaults is thread-safe but not marked Sendable, so reusing this value
    // across the performChatPinMigration actor boundary needs the isolation opt-out.
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let store = try await createTestStore()
    let radioID = UUID()
    let favorite = makeContactDTO(radioID: radioID, isFavorite: true, name: "Ada")
    let plain = makeContactDTO(radioID: radioID, isFavorite: false, name: "Bea")
    try await store.saveContact(favorite)
    try await store.saveContact(plain)

    try await store.performChatPinMigration(defaults: defaults)

    let storedFavorite = try await store.fetchContact(id: favorite.id)
    let storedPlain = try await store.fetchContact(id: plain.id)
    #expect(storedFavorite?.isPinned == true)
    #expect(storedFavorite?.isFavorite == true)
    #expect(storedPlain?.isPinned == false)
  }

  @Test
  func `saveContact leaves an existing pin in place`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let store = try await createTestStore()
    let favorite = makeContactDTO(radioID: UUID(), isFavorite: true, name: "Ada")
    try await store.saveContact(favorite)
    try await store.performChatPinMigration(defaults: defaults)

    var stale = try #require(await store.fetchContact(id: favorite.id))
    stale = stale.with(isPinned: false)
    try await store.saveContact(stale)

    let stored = try await store.fetchContact(id: favorite.id)
    #expect(stored?.isPinned == true)
    #expect(stored?.isFavorite == true)
  }

  @Test
  func `second migration does not pin a favorite saved later`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let store = try await createTestStore()
    let radioID = UUID()
    try await store.performChatPinMigration(defaults: defaults)

    let later = makeContactDTO(radioID: radioID, isFavorite: true, isPinned: false, name: "Cleo")
    try await store.saveContact(later)
    try await store.performChatPinMigration(defaults: defaults)

    let stored = try await store.fetchContact(id: later.id)
    #expect(stored?.isFavorite == true)
    #expect(stored?.isPinned == false)
  }

  @Test
  func `favorite repeater becomes pinned`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let store = try await createTestStore()
    let repeater = makeContactDTO(
      radioID: UUID(),
      isFavorite: true,
      name: "Relay",
      type: .repeater
    )
    try await store.saveContact(repeater)

    try await store.performChatPinMigration(defaults: defaults)

    let stored = try await store.fetchContact(id: repeater.id)
    #expect(stored?.isPinned == true)
    #expect(stored?.isFavorite == true)
    #expect(stored?.type == .repeater)
  }

  @Test
  func `fetchConversations on an unmigrated suite returns the favorited contact pinned`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let store = try await createTestStore()
    let radioID = UUID()
    let favorite = makeContactDTO(
      radioID: radioID,
      isFavorite: true,
      name: "Ada",
      lastMessageDate: Date(timeIntervalSince1970: 1_700_000_000)
    )
    let plain = makeContactDTO(
      radioID: radioID,
      isFavorite: false,
      name: "Bea",
      lastMessageDate: Date(timeIntervalSince1970: 1_700_000_100)
    )
    try await store.saveContact(favorite)
    try await store.saveContact(plain)

    let conversations = try await store.fetchConversations(radioID: radioID, defaults: defaults)

    let storedFavorite = try #require(conversations.first { $0.id == favorite.id })
    let storedPlain = try #require(conversations.first { $0.id == plain.id })
    #expect(storedFavorite.isPinned == true)
    #expect(storedFavorite.isFavorite == true)
    #expect(storedPlain.isPinned == false)
  }

  @Test
  func `old contact JSON without isPinned decodes the favorite as the pin`() throws {
    let favorite = makeContactDTO(radioID: UUID(), isFavorite: true, isPinned: false, name: "Ada")
    var object = try jsonObject(from: favorite)
    object.removeValue(forKey: "isPinned")

    let decoded = try decodeContact(object)

    #expect(decoded.isFavorite == true)
    #expect(decoded.isPinned == true)
  }

  @Test
  func `contact JSON with isPinned false stays unpinned when favorite`() throws {
    let favorite = makeContactDTO(radioID: UUID(), isFavorite: true, isPinned: true, name: "Ada")
    var object = try jsonObject(from: favorite)
    object["isPinned"] = false

    let decoded = try decodeContact(object)

    #expect(decoded.isFavorite == true)
    #expect(decoded.isPinned == false)
  }

  @Test
  func `contact JSON round-trip writes isPinned and isFavorite`() throws {
    let favorite = makeContactDTO(radioID: UUID(), isFavorite: true, isPinned: false, name: "Ada")
    let object = try jsonObject(from: favorite)

    #expect(object["isPinned"] as? Bool == false)
    #expect(object["isFavorite"] as? Bool == true)
  }

  @Test
  func `contact built from a radio frame is favorite and unpinned`() {
    let contact = Contact(radioID: UUID(), from: makeFrame(flags: ContactFlags.favorite.rawValue, name: "Ada"))

    #expect(contact.isFavorite == true)
    #expect(contact.isPinned == false)
  }

  @Test
  func `update from a radio frame leaves the pin`() {
    let contact = Contact(radioID: UUID(), from: makeFrame(flags: ContactFlags.favorite.rawValue, name: "Ada"))
    contact.isPinned = true

    contact.update(from: makeFrame(flags: 0, name: "Renamed"))

    #expect(contact.isPinned == true)
    #expect(contact.name == "Renamed")
  }

  private func jsonObject(from dto: ContactDTO) throws -> [String: Any] {
    let encoded = try JSONEncoder().encode(dto)
    let object = try JSONSerialization.jsonObject(with: encoded)
    return try #require(object as? [String: Any])
  }

  private func decodeContact(_ object: [String: Any]) throws -> ContactDTO {
    let data = try JSONSerialization.data(withJSONObject: object)
    return try JSONDecoder().decode(ContactDTO.self, from: data)
  }
}
