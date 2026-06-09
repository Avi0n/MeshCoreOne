import Testing
import Foundation
@testable import MC1Services

@Suite("ChannelDTO floodScope propagation through with(...) methods")
struct ChannelRegionScopeTests {

    // MARK: - Helpers

    private func makeDTO(floodScope: ChannelFloodScope = .inherit) -> ChannelDTO {
        ChannelDTO(
            id: UUID(),
            radioID: UUID(),
            index: 1,
            name: "Test",
            secret: Data(repeating: 0, count: 16),
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0,
            floodScope: floodScope
        )
    }

    // MARK: - Init Tests

    @Test("Default floodScope is .inherit")
    func defaultFloodScopeIsInherit() {
        let dto = makeDTO()
        #expect(dto.floodScope == .inherit)
    }

    @Test(".region(name) preserved on init")
    func regionPreserved() {
        let dto = makeDTO(floodScope: .region("Europe"))
        #expect(dto.floodScope == .region("Europe"))
    }

    // MARK: - with(notificationLevel:)

    @Test("with(notificationLevel:) preserves .region(name)")
    func withNotificationLevelPreservesRegion() {
        let dto = makeDTO(floodScope: .region("UK"))
        let updated = dto.with(notificationLevel: .muted)

        #expect(updated.floodScope == .region("UK"))
        #expect(updated.notificationLevel == .muted)
    }

    @Test("with(notificationLevel:) preserves .allRegions")
    func withNotificationLevelPreservesAllRegions() {
        let dto = makeDTO(floodScope: .allRegions)
        let updated = dto.with(notificationLevel: .mentionsOnly)

        #expect(updated.floodScope == .allRegions)
    }

    @Test("with(notificationLevel:) preserves .inherit")
    func withNotificationLevelPreservesInherit() {
        let dto = makeDTO()
        let updated = dto.with(notificationLevel: .mentionsOnly)

        #expect(updated.floodScope == .inherit)
    }

    // MARK: - with(isFavorite:)

    @Test("with(isFavorite:) preserves .region(name)")
    func withIsFavoritePreservesRegion() {
        let dto = makeDTO(floodScope: .region("France"))
        let updated = dto.with(isFavorite: true)

        #expect(updated.floodScope == .region("France"))
        #expect(updated.isFavorite == true)
    }

    @Test("with(isFavorite:) preserves .inherit")
    func withIsFavoritePreservesInherit() {
        let dto = makeDTO()
        let updated = dto.with(isFavorite: true)

        #expect(updated.floodScope == .inherit)
    }

    // MARK: - with(floodScope:)

    @Test("with(floodScope:) updates from .region to .region")
    func withFloodScopeUpdatesBetweenRegions() {
        let dto = makeDTO(floodScope: .region("Europe"))
        let updated = dto.with(floodScope: .region("UK"))

        #expect(updated.floodScope == .region("UK"))
    }

    @Test("with(floodScope: .inherit) clears region name in storage")
    func withFloodScopeInheritClears() {
        let dto = makeDTO(floodScope: .region("Europe"))
        let updated = dto.with(floodScope: .inherit)

        #expect(updated.floodScope == .inherit)
        #expect(updated.regionScope == nil)
    }

    @Test("with(floodScope:) sets specific region from inherit")
    func withFloodScopeSetsFromInherit() {
        let dto = makeDTO()
        let updated = dto.with(floodScope: .region("Asia"))

        #expect(updated.floodScope == .region("Asia"))
    }

    @Test("with(floodScope:) preserves all other fields")
    func withFloodScopePreservesOtherFields() {
        let dto = makeDTO(floodScope: .region("Europe"))
        let updated = dto.with(floodScope: .region("UK"))

        #expect(updated.id == dto.id)
        #expect(updated.radioID == dto.radioID)
        #expect(updated.index == dto.index)
        #expect(updated.name == dto.name)
        #expect(updated.secret == dto.secret)
        #expect(updated.isEnabled == dto.isEnabled)
        #expect(updated.lastMessageDate == dto.lastMessageDate)
        #expect(updated.unreadCount == dto.unreadCount)
        #expect(updated.unreadMentionCount == dto.unreadMentionCount)
        #expect(updated.notificationLevel == dto.notificationLevel)
        #expect(updated.isFavorite == dto.isFavorite)
    }
}
