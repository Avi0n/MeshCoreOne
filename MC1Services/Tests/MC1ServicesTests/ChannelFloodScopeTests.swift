import Foundation
import Testing
@testable import MC1Services

@Suite("ChannelFloodScope storage")
struct ChannelFloodScopeTests {

    // MARK: - Enum shape

    @Test("Three distinct cases compare as equal only to themselves")
    func enumCasesAreDistinct() {
        #expect(ChannelFloodScope.inherit == .inherit)
        #expect(ChannelFloodScope.allRegions == .allRegions)
        #expect(ChannelFloodScope.region("Germany") == .region("Germany"))
        #expect(ChannelFloodScope.inherit != .allRegions)
        #expect(ChannelFloodScope.inherit != .region(""))
        #expect(ChannelFloodScope.region("Germany") != .region("France"))
    }

    // MARK: - ChannelDTO round-trip

    @Test("DTO default floodScope is .inherit")
    func dtoDefaultIsInherit() {
        let dto = makeDTO()
        #expect(dto.floodScope == .inherit)
    }

    @Test("DTO floodScope roundtrips for .inherit")
    func dtoInheritRoundtrip() {
        let dto = makeDTO(floodScope: .inherit)
        #expect(dto.floodScope == .inherit)
        #expect(dto.regionScope == nil)
    }

    @Test("DTO floodScope roundtrips for .allRegions")
    func dtoAllRegionsRoundtrip() {
        let dto = makeDTO(floodScope: .allRegions)
        #expect(dto.floodScope == .allRegions)
        #expect(dto.regionScope == nil)
    }

    @Test("DTO floodScope roundtrips for .region(name)")
    func dtoSpecificRegionRoundtrip() {
        let dto = makeDTO(floodScope: .region("Germany"))
        #expect(dto.floodScope == .region("Germany"))
        #expect(dto.regionScope == "Germany")
    }

    @Test("DTO treats .region with empty name as .inherit defensively")
    func dtoEmptyRegionNameFallsBackToInherit() {
        // A storage layer pathology: floodScopeModeRawValue says "specific" but regionScope is nil.
        // Public enum should never expose a malformed .region(nil); fall back to .inherit.
        let dto = ChannelDTO.testChannel(
            radioID: UUID(),
            floodScope: .region("Germany")
        ).with(regionScope: nil)
        #expect(dto.floodScope == .inherit)
    }

    // MARK: - Channel model round-trip (through persistence)

    @Test("Channel model default floodScope is .inherit")
    func channelDefaultIsInherit() async throws {
        let store = try await createTestStore()
        let radioID = UUID()
        let dto = makeDTO(radioID: radioID)
        try await store.saveChannel(dto)

        let fetched = try await store.fetchChannel(id: dto.id)
        #expect(fetched?.floodScope == .inherit)
    }

    @Test("Channel model persists .allRegions distinctly from .inherit")
    func channelPersistsAllRegionsDistinct() async throws {
        let store = try await createTestStore()
        let radioID = UUID()
        let dto = makeDTO(radioID: radioID, floodScope: .allRegions)
        try await store.saveChannel(dto)

        let fetched = try await store.fetchChannel(id: dto.id)
        #expect(fetched?.floodScope == .allRegions)
    }

    @Test("Channel model persists .region(name)")
    func channelPersistsSpecificRegion() async throws {
        let store = try await createTestStore()
        let radioID = UUID()
        let dto = makeDTO(radioID: radioID, floodScope: .region("Germany"))
        try await store.saveChannel(dto)

        let fetched = try await store.fetchChannel(id: dto.id)
        #expect(fetched?.floodScope == .region("Germany"))
    }

    // MARK: - Codable envelope migration

    @Test("Codable round-trips all three cases for current envelope format")
    func codableRoundTripCurrent() throws {
        let radioID = UUID()
        for scope in [ChannelFloodScope.inherit, .allRegions, .region("Germany")] {
            let dto = makeDTO(radioID: radioID, floodScope: scope)
            let encoded = try JSONEncoder().encode(dto)
            let decoded = try JSONDecoder().decode(ChannelDTO.self, from: encoded)
            #expect(decoded.floodScope == scope, "roundtrip lost \(scope)")
        }
    }

    @Test("Legacy envelope (missing mode key, nil regionScope) decodes as .inherit")
    func legacyEnvelopeNilRegionDecodesInherit() throws {
        let json = legacyJSON(regionScope: nil)
        let decoded = try JSONDecoder().decode(ChannelDTO.self, from: Data(json.utf8))
        #expect(decoded.floodScope == .inherit)
    }

    @Test("Legacy envelope (missing mode key, named regionScope) decodes as .region")
    func legacyEnvelopeNamedRegionDecodesSpecific() throws {
        let json = legacyJSON(regionScope: "Germany")
        let decoded = try JSONDecoder().decode(ChannelDTO.self, from: Data(json.utf8))
        #expect(decoded.floodScope == .region("Germany"))
    }

    @Test("setChannelFloodScope updates existing channel atomically")
    func setChannelFloodScopeAtomic() async throws {
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
}
