import Foundation
@testable import MC1Services
import SwiftData
import Testing

@Suite("Chat pin channel and room", .serialized)
struct ChatPinChannelRoomTests {
  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  @Test
  func `channel JSON with legacy isFavorite and no isPinned decodes pinned`() throws {
    var legacy = try jsonObject(from: ChannelDTO.testChannel(radioID: UUID(), isPinned: false))
    legacy.removeValue(forKey: "isPinned")
    legacy["isFavorite"] = true

    let decoded = try decode(ChannelDTO.self, from: legacy)
    #expect(decoded.isPinned == true)
  }

  @Test
  func `room JSON with legacy isFavorite and no isPinned decodes pinned`() throws {
    var legacy = try jsonObject(from: RemoteNodeSessionDTO.testSession(radioID: UUID(), isPinned: false))
    legacy.removeValue(forKey: "isPinned")
    legacy["isFavorite"] = true

    let decoded = try decode(RemoteNodeSessionDTO.self, from: legacy)
    #expect(decoded.isPinned == true)
  }

  @Test
  func `saveChannel leaves an existing pin in place`() async throws {
    let store = try await createTestStore()
    let pinned = ChannelDTO.testChannel(radioID: UUID(), isPinned: true)
    try await store.saveChannel(pinned)

    try await store.saveChannel(pinned.with(isPinned: false))

    let stored = try await store.fetchChannel(id: pinned.id)
    #expect(stored?.isPinned == true)
  }

  @Test
  func `saveRemoteNodeSession leaves an existing pin in place`() async throws {
    let store = try await createTestStore()
    let pinned = RemoteNodeSessionDTO.testSession(radioID: UUID(), isPinned: true)
    try await store.saveRemoteNodeSessionDTO(pinned)

    try await store.saveRemoteNodeSessionDTO(pinned.with(isPinned: false))

    let stored = try await store.fetchRemoteNodeSession(id: pinned.id)
    #expect(stored?.isPinned == true)
  }

  private func jsonObject(from value: some Encodable) throws -> [String: Any] {
    let encoded = try JSONEncoder().encode(value)
    let object = try JSONSerialization.jsonObject(with: encoded)
    return try #require(object as? [String: Any])
  }

  private func decode<T: Decodable>(_ type: T.Type, from object: [String: Any]) throws -> T {
    let data = try JSONSerialization.data(withJSONObject: object)
    return try JSONDecoder().decode(type, from: data)
  }
}
