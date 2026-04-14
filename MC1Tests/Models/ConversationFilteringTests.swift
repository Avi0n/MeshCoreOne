import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite struct ConversationFilteringTests {

    // MARK: - Test Data

    private func makeContact(
        name: String,
        isFavorite: Bool = false,
        unreadCount: Int = 0,
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
            nickname: nil,
            isBlocked: false,
            isMuted: isMuted,
            isFavorite: isFavorite,
            lastMessageDate: Date(),
            unreadCount: unreadCount
        )
    }

    private func makeChannel(
        name: String,
        unreadCount: Int = 0,
        notificationLevel: NotificationLevel = .all,
        isFavorite: Bool = false
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
            notificationLevel: notificationLevel,
            isFavorite: isFavorite
        )
    }

    private func makeRoom(
        name: String,
        unreadCount: Int = 0,
        notificationLevel: NotificationLevel = .all,
        isFavorite: Bool = false
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
            isFavorite: isFavorite
        )
    }

    // MARK: - Filter Tests

    @Test func allFilterShowsAll() {
        let conversations: [Conversation] = [
            .direct(makeContact(name: "Alice")),
            .channel(makeChannel(name: "General")),
            .room(makeRoom(name: "Room1"))
        ]

        let result = conversations.filtered(by: .all, searchText: "")

        #expect(result.count == 3)
    }

    @Test func filterByUnread() {
        let conversations: [Conversation] = [
            .direct(makeContact(name: "Alice", unreadCount: 5)),
            .direct(makeContact(name: "Bob", unreadCount: 0)),
            .channel(makeChannel(name: "General", unreadCount: 2))
        ]

        let result = conversations.filtered(by: .unread, searchText: "")

        #expect(result.count == 2)
        #expect(result.allSatisfy { $0.unreadCount > 0 })
    }

    @Test func filterByDirectMessages() {
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

    @Test func filterByChannelsIncludesRooms() {
        let conversations: [Conversation] = [
            .direct(makeContact(name: "Alice")),
            .channel(makeChannel(name: "General")),
            .room(makeRoom(name: "Room1"))
        ]

        let result = conversations.filtered(by: .channels, searchText: "")

        #expect(result.count == 2)
        #expect(result.allSatisfy {
            if case .channel = $0 { return true }
            if case .room = $0 { return true }
            return false
        })
    }

    @Test func searchWithinFilter() {
        let conversations: [Conversation] = [
            .direct(makeContact(name: "Alice", unreadCount: 1)),
            .direct(makeContact(name: "Bob", unreadCount: 1)),
            .direct(makeContact(name: "Charlie", unreadCount: 0))
        ]

        let result = conversations.filtered(by: .unread, searchText: "Ali")

        #expect(result.count == 1)
        #expect(result.first?.displayName == "Alice")
    }

    @Test func searchOnlyWithoutFilter() {
        let conversations: [Conversation] = [
            .direct(makeContact(name: "Alice")),
            .direct(makeContact(name: "Bob")),
            .channel(makeChannel(name: "Alpha"))
        ]

        let result = conversations.filtered(by: .all, searchText: "Al")

        #expect(result.count == 2)
    }

    @Test func emptyResultsWhenNoMatch() {
        let conversations: [Conversation] = [
            .direct(makeContact(name: "Alice")),
            .direct(makeContact(name: "Bob"))
        ]

        let result = conversations.filtered(by: .all, searchText: "Zzzz")

        #expect(result.isEmpty)
    }

    @Test func unreadFilterExcludesMuted() {
        let conversations: [Conversation] = [
            .direct(makeContact(name: "Alice", unreadCount: 5, isMuted: false)),
            .direct(makeContact(name: "Bob", unreadCount: 3, isMuted: true)),
            .channel(makeChannel(name: "General", unreadCount: 2, notificationLevel: .all))
        ]

        let result = conversations.filtered(by: .unread, searchText: "")

        #expect(result.count == 2)
        #expect(!result.contains { $0.displayName == "Bob" })
    }
}
