import Testing
import Foundation
@testable import MC1Services

@Suite("ChannelDTO regionScope propagation")
struct ChannelRegionScopeTests {

    // MARK: - Helpers

    private func makeDTO(regionScope: String? = nil) -> ChannelDTO {
        ChannelDTO(
            id: UUID(),
            radioID: UUID(),
            index: 1,
            name: "Test",
            secret: Data(repeating: 0, count: 16),
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0,
            regionScope: regionScope
        )
    }

    // MARK: - Init Tests

    @Test("nil regionScope preserved on init")
    func nilRegionScopePreserved() {
        let dto = makeDTO()
        #expect(dto.regionScope == nil)
    }

    @Test("non-nil regionScope preserved on init")
    func regionScopePreserved() {
        let dto = makeDTO(regionScope: "Europe")
        #expect(dto.regionScope == "Europe")
    }

    // MARK: - with(notificationLevel:)

    @Test("with(notificationLevel:) preserves regionScope")
    func withNotificationLevelPreservesRegionScope() {
        let dto = makeDTO(regionScope: "UK")
        let updated = dto.with(notificationLevel: .muted)

        #expect(updated.regionScope == "UK")
        #expect(updated.notificationLevel == .muted)
    }

    @Test("with(notificationLevel:) preserves nil regionScope")
    func withNotificationLevelPreservesNilRegionScope() {
        let dto = makeDTO()
        let updated = dto.with(notificationLevel: .mentionsOnly)

        #expect(updated.regionScope == nil)
    }

    // MARK: - with(isFavorite:)

    @Test("with(isFavorite:) preserves regionScope")
    func withIsFavoritePreservesRegionScope() {
        let dto = makeDTO(regionScope: "France")
        let updated = dto.with(isFavorite: true)

        #expect(updated.regionScope == "France")
        #expect(updated.isFavorite == true)
    }

    @Test("with(isFavorite:) preserves nil regionScope")
    func withIsFavoritePreservesNilRegionScope() {
        let dto = makeDTO()
        let updated = dto.with(isFavorite: true)

        #expect(updated.regionScope == nil)
    }

    // MARK: - with(regionScope:)

    @Test("with(regionScope:) updates the value")
    func withRegionScopeUpdates() {
        let dto = makeDTO(regionScope: "Europe")
        let updated = dto.with(regionScope: "UK")

        #expect(updated.regionScope == "UK")
    }

    @Test("with(regionScope: nil) clears the value")
    func withRegionScopeClears() {
        let dto = makeDTO(regionScope: "Europe")
        let updated = dto.with(regionScope: nil)

        #expect(updated.regionScope == nil)
    }

    @Test("with(regionScope:) sets value from nil")
    func withRegionScopeSetsFromNil() {
        let dto = makeDTO()
        let updated = dto.with(regionScope: "Asia")

        #expect(updated.regionScope == "Asia")
    }

    @Test("with(regionScope:) preserves all other fields")
    func withRegionScopePreservesOtherFields() {
        let dto = makeDTO(regionScope: "Europe")
        let updated = dto.with(regionScope: "UK")

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
