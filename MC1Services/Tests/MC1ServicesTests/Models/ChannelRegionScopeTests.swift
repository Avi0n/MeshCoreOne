import Foundation
@testable import MC1Services
import Testing

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

  @Test
  func `Default floodScope is .inherit`() {
    let dto = makeDTO()
    #expect(dto.floodScope == .inherit)
  }

  @Test
  func `.region(name) preserved on init`() {
    let dto = makeDTO(floodScope: .region("Europe"))
    #expect(dto.floodScope == .region("Europe"))
  }

  // MARK: - with(notificationLevel:)

  @Test
  func `with(notificationLevel:) preserves .region(name)`() {
    let dto = makeDTO(floodScope: .region("UK"))
    let updated = dto.with(notificationLevel: .muted)

    #expect(updated.floodScope == .region("UK"))
    #expect(updated.notificationLevel == .muted)
  }

  @Test
  func `with(notificationLevel:) preserves .allRegions`() {
    let dto = makeDTO(floodScope: .allRegions)
    let updated = dto.with(notificationLevel: .mentionsOnly)

    #expect(updated.floodScope == .allRegions)
  }

  @Test
  func `with(notificationLevel:) preserves .inherit`() {
    let dto = makeDTO()
    let updated = dto.with(notificationLevel: .mentionsOnly)

    #expect(updated.floodScope == .inherit)
  }

  // MARK: - with(isFavorite:)

  @Test
  func `with(isFavorite:) preserves .region(name)`() {
    let dto = makeDTO(floodScope: .region("France"))
    let updated = dto.with(isFavorite: true)

    #expect(updated.floodScope == .region("France"))
    #expect(updated.isFavorite == true)
  }

  @Test
  func `with(isFavorite:) preserves .inherit`() {
    let dto = makeDTO()
    let updated = dto.with(isFavorite: true)

    #expect(updated.floodScope == .inherit)
  }

  // MARK: - with(floodScope:)

  @Test
  func `with(floodScope:) updates from .region to .region`() {
    let dto = makeDTO(floodScope: .region("Europe"))
    let updated = dto.with(floodScope: .region("UK"))

    #expect(updated.floodScope == .region("UK"))
  }

  @Test
  func `with(floodScope: .inherit) clears region name in storage`() {
    let dto = makeDTO(floodScope: .region("Europe"))
    let updated = dto.with(floodScope: .inherit)

    #expect(updated.floodScope == .inherit)
    #expect(updated.regionScope == nil)
  }

  @Test
  func `with(floodScope:) sets specific region from inherit`() {
    let dto = makeDTO()
    let updated = dto.with(floodScope: .region("Asia"))

    #expect(updated.floodScope == .region("Asia"))
  }

  @Test
  func `with(floodScope:) preserves all other fields`() {
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
