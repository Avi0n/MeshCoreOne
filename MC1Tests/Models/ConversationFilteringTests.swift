import Foundation
@testable import MC1
@testable import MC1Services
import Testing

struct ConversationFilteringTests {
  // MARK: - Test Data

  private func makeContact(
    name: String,
    isFavorite: Bool = false,
    unreadCount: Int = 0,
    unreadMentionCount: Int = 0,
    isMuted: Bool = false
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0, count: 32),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: isMuted,
      isFavorite: isFavorite,
      lastMessageDate: Date(),
      unreadCount: unreadCount,
      unreadMentionCount: unreadMentionCount
    )
  }

  private func makeChannel(
    name: String,
    unreadCount: Int = 0,
    unreadMentionCount: Int = 0,
    notificationLevel: NotificationLevel = .all,
    isPinned: Bool = false
  ) -> ChannelDTO {
    ChannelDTO(
      id: UUID(),
      radioID: UUID(),
      index: 1,
      name: name,
      secret: Data(repeating: 0, count: 16),
      isEnabled: true,
      lastMessageDate: Date(),
      unreadCount: unreadCount,
      unreadMentionCount: unreadMentionCount,
      notificationLevel: notificationLevel,
      isPinned: isPinned
    )
  }

  private func makeRoom(
    name: String,
    unreadCount: Int = 0,
    notificationLevel: NotificationLevel = .all,
    isPinned: Bool = false
  ) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0, count: 32),
      name: name,
      role: .roomServer,
      isConnected: true,
      lastConnectedDate: Date(),
      unreadCount: unreadCount,
      notificationLevel: notificationLevel,
      isPinned: isPinned
    )
  }

  // MARK: - Filter Tests

  @Test func `all filter shows all`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice")),
      .channel(makeChannel(name: "General")),
      .room(makeRoom(name: "Room1"))
    ]

    let result = conversations.filtered(by: .all, searchText: "")

    #expect(result.count == 3)
  }

  @Test func `filter by unread`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice", unreadCount: 5)),
      .direct(makeContact(name: "Bob", unreadCount: 0)),
      .channel(makeChannel(name: "General", unreadCount: 2))
    ]

    let result = conversations.filtered(by: .unread, searchText: "")

    #expect(result.count == 2)
    // swiftformat:disable:next preferKeyPath
    #expect(result.allSatisfy { $0.isUnread })
  }

  @Test func `filter by direct messages`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice")),
      .direct(makeContact(name: "Bob")),
      .channel(makeChannel(name: "General")),
      .room(makeRoom(name: "Room1"))
    ]

    let result = conversations.filtered(by: .directMessages, searchText: "")

    #expect(result.count == 2)
    #expect(result.allSatisfy {
      if case .direct = $0 { return true }
      return false
    })
  }

  @Test func `filter by channels excludes rooms`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice")),
      .channel(makeChannel(name: "General")),
      .room(makeRoom(name: "Room1"))
    ]

    let result = conversations.filtered(by: .channels, searchText: "")

    #expect(result.count == 1)
    #expect(result.allSatisfy {
      if case .channel = $0 { return true }
      return false
    })
  }

  @Test func `filter by rooms excludes channels`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice")),
      .channel(makeChannel(name: "General")),
      .room(makeRoom(name: "Room1")),
      .room(makeRoom(name: "Room2"))
    ]

    let result = conversations.filtered(by: .rooms, searchText: "")

    #expect(result.count == 2)
    #expect(result.allSatisfy {
      if case .room = $0 { return true }
      return false
    })
  }

  @Test func `search within filter`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice", unreadCount: 1)),
      .direct(makeContact(name: "Bob", unreadCount: 1)),
      .direct(makeContact(name: "Charlie", unreadCount: 0))
    ]

    let result = conversations.filtered(by: .unread, searchText: "Ali")

    #expect(result.count == 1)
    #expect(result.first?.displayName == "Alice")
  }

  @Test func `search only without filter`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice")),
      .direct(makeContact(name: "Bob")),
      .channel(makeChannel(name: "Alpha"))
    ]

    let result = conversations.filtered(by: .all, searchText: "Al")

    #expect(result.count == 2)
  }

  @Test func `empty results when no match`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice")),
      .direct(makeContact(name: "Bob"))
    ]

    let result = conversations.filtered(by: .all, searchText: "Zzzz")

    #expect(result.isEmpty)
  }

  @Test func `unread filter excludes muted conversations including mention only`() {
    let conversations: [Conversation] = [
      .direct(makeContact(
        name: "Ada",
        unreadCount: 0,
        unreadMentionCount: 1,
        isMuted: false
      )),
      .direct(makeContact(
        name: "Muted Mention",
        unreadCount: 0,
        unreadMentionCount: 1,
        isMuted: true
      )),
      .direct(makeContact(name: "Muted Count", unreadCount: 1, isMuted: true)),
      .channel(makeChannel(name: "Mentions", unreadCount: 0, unreadMentionCount: 1)),
      .channel(makeChannel(
        name: "Quiet",
        unreadCount: 0,
        unreadMentionCount: 1,
        notificationLevel: .muted
      )),
      .room(makeRoom(name: "Loud", unreadCount: 1)),
      .room(makeRoom(name: "Silent", unreadCount: 1, notificationLevel: .muted))
    ]

    let names = conversations.filtered(by: .unread, searchText: "").map(\.displayName)

    #expect(names == ["Ada", "Mentions", "Loud"])
    #expect(conversations.first { $0.displayName == "Muted Mention" }?.isUnread == true)
    #expect(conversations.first { $0.displayName == "Quiet" }?.isUnread == true)
    #expect(conversations.first { $0.displayName == "Silent" }?.isUnread == true)
  }

  @Test func `search ignores the unread filter and muted exclusion`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Alice", unreadCount: 0)),
      .direct(makeContact(name: "Bob", unreadCount: 1, isMuted: true)),
      .channel(makeChannel(
        name: "Alpine",
        unreadCount: 0,
        unreadMentionCount: 1,
        notificationLevel: .muted
      ))
    ]

    #expect(conversations.filtered(by: .unread, searchText: "Ali").map(\.displayName) == ["Alice"])
    #expect(conversations.filtered(by: .unread, searchText: "Bob").map(\.displayName) == ["Bob"])
    #expect(conversations.filtered(by: .unread, searchText: "Alp").map(\.displayName) == ["Alpine"])
  }

  @Test func `search returns every category while another category stays selected`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Ada")),
      .channel(makeChannel(name: "Adage")),
      .room(makeRoom(name: "Adapter")),
      .channel(makeChannel(name: "General"))
    ]

    let names = conversations.filtered(by: .channels, searchText: "Ada").map(\.displayName)

    #expect(names == ["Ada", "Adage", "Adapter"])
  }

  @Test func `whitespace query does not fall back to the selected category`() {
    let conversations: [Conversation] = [
      .direct(makeContact(name: "Ada")),
      .room(makeRoom(name: "Adapter"))
    ]

    #expect(conversations.filtered(by: .rooms, searchText: "   ").isEmpty)
    #expect(conversations.filtered(by: .rooms, searchText: "").map(\.displayName) == ["Adapter"])
  }
}
