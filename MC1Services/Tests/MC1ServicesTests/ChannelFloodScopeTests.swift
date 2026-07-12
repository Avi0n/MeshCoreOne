import Foundation
@testable import MC1Services
import Testing

@Suite("ChannelFloodScope storage")
struct ChannelFloodScopeTests {
  // MARK: - Enum shape

  @Test
  func `Three distinct cases compare as equal only to themselves`() {
    #expect(ChannelFloodScope.inherit == .inherit)
    #expect(ChannelFloodScope.allRegions == .allRegions)
    #expect(ChannelFloodScope.region("Germany") == .region("Germany"))
    #expect(ChannelFloodScope.inherit != .allRegions)
    #expect(ChannelFloodScope.inherit != .region(""))
    #expect(ChannelFloodScope.region("Germany") != .region("France"))
  }

  // MARK: - ChannelDTO round-trip

  @Test
  func `DTO default floodScope is .inherit`() {
    let dto = makeDTO()
    #expect(dto.floodScope == .inherit)
  }

  @Test
  func `DTO floodScope roundtrips for .inherit`() {
    let dto = makeDTO(floodScope: .inherit)
    #expect(dto.floodScope == .inherit)
    #expect(dto.regionScope == nil)
  }

  @Test
  func `DTO floodScope roundtrips for .allRegions`() {
    let dto = makeDTO(floodScope: .allRegions)
    #expect(dto.floodScope == .allRegions)
    #expect(dto.regionScope == nil)
  }

  @Test
  func `DTO floodScope roundtrips for .region(name)`() {
    let dto = makeDTO(floodScope: .region("Germany"))
    #expect(dto.floodScope == .region("Germany"))
    #expect(dto.regionScope == "Germany")
  }

  @Test
  func `DTO treats .region with empty name as .inherit defensively`() {
    // A storage layer pathology: floodScopeModeRawValue says "specific" but regionScope is nil.
    // Public enum should never expose a malformed .region(nil); fall back to .inherit.
    let dto = ChannelDTO.testChannel(
      radioID: UUID(),
      floodScope: .region("Germany")
    ).with(regionScope: nil)
    #expect(dto.floodScope == .inherit)
  }

  // MARK: - Channel model round-trip (through persistence)

  @Test
  func `Channel model default floodScope is .inherit`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let dto = makeDTO(radioID: radioID)
    try await store.saveChannel(dto)

    let fetched = try await store.fetchChannel(id: dto.id)
    #expect(fetched?.floodScope == .inherit)
  }

  @Test
  func `Channel model persists .allRegions distinctly from .inherit`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let dto = makeDTO(radioID: radioID, floodScope: .allRegions)
    try await store.saveChannel(dto)

    let fetched = try await store.fetchChannel(id: dto.id)
    #expect(fetched?.floodScope == .allRegions)
  }

  @Test
  func `Channel model persists .region(name)`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let dto = makeDTO(radioID: radioID, floodScope: .region("Germany"))
    try await store.saveChannel(dto)

    let fetched = try await store.fetchChannel(id: dto.id)
    #expect(fetched?.floodScope == .region("Germany"))
  }

  // MARK: - Codable envelope migration

  @Test
  func `Codable round-trips all three cases for current envelope format`() throws {
    let radioID = UUID()
    for scope in [ChannelFloodScope.inherit, .allRegions, .region("Germany")] {
      let dto = makeDTO(radioID: radioID, floodScope: scope)
      let encoded = try JSONEncoder().encode(dto)
      let decoded = try JSONDecoder().decode(ChannelDTO.self, from: encoded)
      #expect(decoded.floodScope == scope, "roundtrip lost \(scope)")
    }
  }

  @Test
  func `Legacy envelope (missing mode key, nil regionScope) decodes as .inherit`() throws {
    let json = legacyJSON(regionScope: nil)
    let decoded = try JSONDecoder().decode(ChannelDTO.self, from: Data(json.utf8))
    #expect(decoded.floodScope == .inherit)
  }

  @Test
  func `Legacy envelope (missing mode key, named regionScope) decodes as .region`() throws {
    let json = legacyJSON(regionScope: "Germany")
    let decoded = try JSONDecoder().decode(ChannelDTO.self, from: Data(json.utf8))
    #expect(decoded.floodScope == .region("Germany"))
  }

  @Test
  func `Legacy envelope omitting notificationLevel key decodes as .all`() throws {
    let json = try legacyJSONOmitting("notificationLevel")
    let decoded = try JSONDecoder().decode(ChannelDTO.self, from: json)
    #expect(decoded.notificationLevel == .all)
  }

  @Test
  func `Legacy envelope omitting unreadMentionCount key decodes as 0`() throws {
    let json = try legacyJSONOmitting("unreadMentionCount")
    let decoded = try JSONDecoder().decode(ChannelDTO.self, from: json)
    #expect(decoded.unreadMentionCount == 0)
  }

  @Test
  func `Legacy envelope omitting isFavorite key decodes as false`() throws {
    let json = try legacyJSONOmitting("isFavorite")
    let decoded = try JSONDecoder().decode(ChannelDTO.self, from: json)
    #expect(decoded.isFavorite == false)
  }

  @Test
  func `setChannelFloodScope updates existing channel atomically`() async throws {
    let store = try await createTestStore()
    let radioID = UUID()
    let dto = makeDTO(radioID: radioID, floodScope: .region("Germany"))
    try await store.saveChannel(dto)

    try await store.setChannelFloodScope(dto.id, floodScope: .allRegions)
    let after = try await store.fetchChannel(id: dto.id)
    #expect(after?.floodScope == .allRegions)
    #expect(after?.regionScope == nil, "switching to .allRegions must clear the region name")

    try await store.setChannelFloodScope(dto.id, floodScope: .inherit)
    let afterInherit = try await store.fetchChannel(id: dto.id)
    #expect(afterInherit?.floodScope == .inherit)
    #expect(afterInherit?.regionScope == nil)
  }

  // MARK: - Helpers

  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func makeDTO(
    radioID: UUID = UUID(),
    floodScope: ChannelFloodScope = .inherit
  ) -> ChannelDTO {
    ChannelDTO.testChannel(radioID: radioID, floodScope: floodScope)
  }

  private func legacyJSON(regionScope: String?) -> String {
    let region = regionScope.map { "\"\($0)\"" } ?? "null"
    return """
    {
      "id": "\(UUID().uuidString)",
      "radioID": "\(UUID().uuidString)",
      "index": 1,
      "name": "General",
      "secret": "\(Data(repeating: 0, count: 16).base64EncodedString())",
      "isEnabled": true,
      "unreadCount": 0,
      "unreadMentionCount": 0,
      "notificationLevel": 2,
      "isFavorite": false,
      "regionScope": \(region)
    }
    """
  }

  /// A backup predating a given field: encode a real DTO, then drop one key so the
  /// JSON otherwise matches the current wire format exactly. Proves the decoder's
  /// missing-key fallback survives an old envelope instead of throwing.
  private func legacyJSONOmitting(_ key: String) throws -> Data {
    // Non-fallback source values so a passing decode proves the fallback fired
    // rather than echoing the encoded value.
    let dto = ChannelDTO.testChannel(
      radioID: UUID(),
      unreadMentionCount: 5,
      notificationLevel: .muted,
      isFavorite: true
    )
    let encoded = try JSONEncoder().encode(dto)
    guard var object = try JSONSerialization.jsonObject(with: encoded) as? [String: Any] else {
      throw DecodingError.dataCorrupted(
        .init(codingPath: [], debugDescription: "encoded ChannelDTO was not a JSON object")
      )
    }
    object.removeValue(forKey: key)
    return try JSONSerialization.data(withJSONObject: object)
  }
}
