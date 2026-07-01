import Foundation
@testable import MC1
@testable import MC1Services
import Testing

struct ChatConversationTypeTests {
  // MARK: - Test Helpers

  private func makeContact(
    id: UUID = UUID(),
    name: String = "TestUser",
    nickname: String? = nil,
    outPathLength: UInt8 = 2
  ) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: UUID(),
      publicKey: Data(),
      name: name,
      typeRawValue: 0,
      flags: 0,
      outPathLength: outPathLength,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nickname,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  private func makeChannel(
    id: UUID = UUID(),
    index: UInt8 = 1,
    name: String = "General",
    floodScope: ChannelFloodScope = .inherit
  ) -> ChannelDTO {
    ChannelDTO(
      id: id,
      radioID: UUID(),
      index: index,
      name: name,
      secret: Data(repeating: 0, count: 16),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      floodScope: floodScope
    )
  }

  // MARK: - navigationTitle

  @Test
  func `DM navigationTitle returns contact displayName`() {
    let contact = makeContact(name: "Alice")
    let sut = ChatConversationType.dm(contact)
    #expect(sut.navigationTitle == "Alice")
  }

  @Test
  func `DM navigationTitle prefers nickname when set`() {
    let contact = makeContact(name: "Alice", nickname: "Ally")
    let sut = ChatConversationType.dm(contact)
    #expect(sut.navigationTitle == "Ally")
  }

  @Test
  func `Channel navigationTitle returns channel name`() {
    let channel = makeChannel(name: "General")
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationTitle == "General")
  }

  @Test
  func `Channel navigationTitle returns default name when empty`() {
    let channel = makeChannel(index: 3, name: "")
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationTitle == L10n.Chats.Chats.Channel.defaultName(3))
  }

  // MARK: - navigationSubtitle

  @Test
  func `DM subtitle shows flood routing when flood routed`() {
    let contact = makeContact(outPathLength: 0xFF)
    let sut = ChatConversationType.dm(contact)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: nil) == L10n.Chats.Chats.ConnectionStatus.floodRouting)
  }

  @Test
  func `DM subtitle shows direct path with hop count`() {
    let contact = makeContact(outPathLength: 2)
    let sut = ChatConversationType.dm(contact)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: nil) == L10n.Chats.Chats.ConnectionStatus.direct(contact.pathHopCount))
  }

  @Test
  func `Channel subtitle shows public for public channel`() {
    let channel = makeChannel(index: 0, name: "Public")
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: nil) == L10n.Chats.Chats.Channel.typePublic)
  }

  @Test
  func `Channel subtitle shows hashtag for hash-prefixed channel`() {
    let channel = makeChannel(index: 5, name: "#random")
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: nil) == L10n.Chats.Chats.ChannelInfo.ChannelType.hashtag)
  }

  @Test
  func `Channel subtitle shows private for private channel`() {
    let channel = makeChannel(index: 3, name: "Secret")
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: nil) == L10n.Chats.Chats.Channel.typePrivate)
  }

  @Test
  func `Channel subtitle appends per-channel region override`() {
    let channel = makeChannel(index: 3, name: "Ops", floodScope: .region("Germany"))
    let sut = ChatConversationType.channel(channel)
    let expected = "\(L10n.Chats.Chats.Channel.typePrivate) \u{00B7} Germany"
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: nil) == expected)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: "Spain") == expected)
  }

  @Test
  func `Channel subtitle inherits device default and appends (default) suffix`() {
    let channel = makeChannel(index: 3, name: "Ops", floodScope: .inherit)
    let sut = ChatConversationType.channel(channel)
    let suffixed = L10n.Chats.Chats.ChannelInfo.Region.scopedDefault("Spain")
    let expected = "\(L10n.Chats.Chats.Channel.typePrivate) \u{00B7} \(suffixed)"
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: "Spain") == expected)
  }

  @Test
  func `Channel subtitle appends (default) suffix when explicit region matches device default`() {
    let channel = makeChannel(index: 3, name: "Ops", floodScope: .region("Spain"))
    let sut = ChatConversationType.channel(channel)
    let suffixed = L10n.Chats.Chats.ChannelInfo.Region.scopedDefault("Spain")
    let expected = "\(L10n.Chats.Chats.Channel.typePrivate) \u{00B7} \(suffixed)"
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: "Spain") == expected)
  }

  @Test
  func `Channel subtitle omits region when inherit and no device default`() {
    let channel = makeChannel(index: 3, name: "Ops", floodScope: .inherit)
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: nil) == L10n.Chats.Chats.Channel.typePrivate)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: "") == L10n.Chats.Chats.Channel.typePrivate)
  }

  @Test
  func `Channel subtitle omits region for allRegions scope`() {
    let channel = makeChannel(index: 3, name: "Ops", floodScope: .allRegions)
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationSubtitle(deviceDefaultFloodScopeName: "Spain") == L10n.Chats.Chats.Channel.typePrivate)
  }

  // MARK: - navigationSubtitleAccessibilityLabel

  @Test
  func `Accessibility label nil for DMs`() {
    let sut = ChatConversationType.dm(makeContact())
    #expect(sut.navigationSubtitleAccessibilityLabel(deviceDefaultFloodScopeName: "Spain") == nil)
  }

  @Test
  func `Accessibility label nil when no effective region`() {
    let channel = makeChannel(floodScope: .inherit)
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationSubtitleAccessibilityLabel(deviceDefaultFloodScopeName: nil) == nil)
  }

  @Test
  func `Accessibility label uses default-scoped variant when inheriting device default`() {
    let channel = makeChannel(index: 3, name: "Ops", floodScope: .inherit)
    let sut = ChatConversationType.channel(channel)
    let expected = L10n.Chats.Chats.ChannelInfo.Region.defaultScopedAccessibility(
      L10n.Chats.Chats.Channel.typePrivate, "Spain"
    )
    #expect(sut.navigationSubtitleAccessibilityLabel(deviceDefaultFloodScopeName: "Spain") == expected)
  }

  @Test
  func `Accessibility label uses default-scoped variant when explicit region matches device default`() {
    let channel = makeChannel(index: 3, name: "Ops", floodScope: .region("Spain"))
    let sut = ChatConversationType.channel(channel)
    let expected = L10n.Chats.Chats.ChannelInfo.Region.defaultScopedAccessibility(
      L10n.Chats.Chats.Channel.typePrivate, "Spain"
    )
    #expect(sut.navigationSubtitleAccessibilityLabel(deviceDefaultFloodScopeName: "Spain") == expected)
  }

  @Test
  func `Accessibility label uses per-channel region override`() {
    let channel = makeChannel(index: 3, name: "Ops", floodScope: .region("Germany"))
    let sut = ChatConversationType.channel(channel)
    let expected = L10n.Chats.Chats.ChannelInfo.Region.scopedAccessibility(
      L10n.Chats.Chats.Channel.typePrivate, "Germany"
    )
    #expect(sut.navigationSubtitleAccessibilityLabel(deviceDefaultFloodScopeName: "Spain") == expected)
  }

  @Test
  func `Accessibility label nil for allRegions`() {
    let channel = makeChannel(floodScope: .allRegions)
    let sut = ChatConversationType.channel(channel)
    #expect(sut.navigationSubtitleAccessibilityLabel(deviceDefaultFloodScopeName: "Spain") == nil)
  }

  // MARK: - conversationID

  @Test
  func `DM conversationID returns contact ID`() {
    let id = UUID()
    let contact = makeContact(id: id)
    let sut = ChatConversationType.dm(contact)
    #expect(sut.conversationID == id)
  }

  @Test
  func `Channel conversationID returns channel ID`() {
    let id = UUID()
    let channel = makeChannel(id: id)
    let sut = ChatConversationType.channel(channel)
    #expect(sut.conversationID == id)
  }

  // MARK: - isPublicStyleChannel

  @Test
  func `DM isPublicStyleChannel is false`() {
    let sut = ChatConversationType.dm(makeContact())
    #expect(sut.isPublicStyleChannel == false)
  }

  @Test
  func `Public channel (index 0) isPublicStyleChannel is true`() {
    let channel = makeChannel(index: 0, name: "Public")
    let sut = ChatConversationType.channel(channel)
    #expect(sut.isPublicStyleChannel == true)
  }

  @Test
  func `Hash-prefixed channel isPublicStyleChannel is true`() {
    let channel = makeChannel(index: 5, name: "#general")
    let sut = ChatConversationType.channel(channel)
    #expect(sut.isPublicStyleChannel == true)
  }

  @Test
  func `Private channel isPublicStyleChannel is false`() {
    let channel = makeChannel(index: 3, name: "Secret")
    let sut = ChatConversationType.channel(channel)
    #expect(sut.isPublicStyleChannel == false)
  }

  // MARK: - suppressesMapPreviews

  @Test
  func `Channel named wardriving suppresses map previews`() {
    let sut = ChatConversationType.channel(makeChannel(name: "wardriving"))
    #expect(sut.suppressesMapPreviews == true)
  }

  @Test
  func `Wardriving match is case-insensitive`() {
    #expect(ChatConversationType.channel(makeChannel(name: "Wardriving")).suppressesMapPreviews == true)
    #expect(ChatConversationType.channel(makeChannel(name: "WARDRIVING")).suppressesMapPreviews == true)
  }

  @Test
  func `Wardriving match trims surrounding whitespace`() {
    let sut = ChatConversationType.channel(makeChannel(name: " wardriving "))
    #expect(sut.suppressesMapPreviews == true)
  }

  @Test
  func `Hash-prefixed wardriving suppresses (tolerates the # channel convention)`() {
    #expect(ChatConversationType.channel(makeChannel(name: "#wardriving")).suppressesMapPreviews == true)
    #expect(ChatConversationType.channel(makeChannel(name: "#Wardriving")).suppressesMapPreviews == true)
  }

  @Test
  func `Wardriving with suffix does not suppress (exact match, not prefix)`() {
    let sut = ChatConversationType.channel(makeChannel(name: "wardriving-east"))
    #expect(sut.suppressesMapPreviews == false)
  }

  @Test
  func `Ordinary channel does not suppress map previews`() {
    let sut = ChatConversationType.channel(makeChannel(name: "general"))
    #expect(sut.suppressesMapPreviews == false)
  }

  @Test
  func `DM never suppresses map previews`() {
    let sut = ChatConversationType.dm(makeContact())
    #expect(sut.suppressesMapPreviews == false)
  }

  // MARK: - radioID

  @Test
  func `radioID returns the contact's radioID for a DM conversation`() {
    let radio = UUID()
    let contact = ContactDTO(
      id: UUID(),
      radioID: radio,
      publicKey: Data(),
      name: "Alice",
      typeRawValue: 0,
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
      unreadCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
    let conversation = ChatConversationType.dm(contact)
    #expect(conversation.radioID == radio)
  }

  @Test
  func `radioID returns the channel's radioID for a channel conversation`() {
    let radio = UUID()
    let channel = ChannelDTO(
      id: UUID(),
      radioID: radio,
      index: 3,
      name: "General",
      secret: Data(repeating: 0, count: 16),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      floodScope: .inherit
    )
    let conversation = ChatConversationType.channel(channel)
    #expect(conversation.radioID == radio)
  }

  // MARK: - replacingContact(_:)

  @Test
  func `replacingContact returns DM with updated contact`() {
    let original = makeContact(name: "Alice")
    let sut = ChatConversationType.dm(original)
    let updated = makeContact(name: "Bob")
    let result = sut.replacingContact(updated)

    #expect(result.navigationTitle == "Bob")
  }

  @Test
  func `replacingContact returns self for channel`() {
    let channel = makeChannel(name: "General")
    let sut = ChatConversationType.channel(channel)
    let contact = makeContact(name: "Alice")
    let result = sut.replacingContact(contact)

    #expect(result.navigationTitle == "General")
  }

  // MARK: - draftConversationID

  @Test
  func `DM draftConversationID keys on radioID and contact id`() {
    let contact = makeContact()
    let sut = ChatConversationType.dm(contact)

    #expect(sut.draftConversationID == .dm(radioID: contact.radioID, contactID: contact.id))
  }

  @Test
  func `Channel draftConversationID keys on the slot index, not the row UUID`() {
    let channel = makeChannel(index: 3)
    let sut = ChatConversationType.channel(channel)

    #expect(sut.draftConversationID == .channel(radioID: channel.radioID, channelIndex: channel.index))
  }
}
