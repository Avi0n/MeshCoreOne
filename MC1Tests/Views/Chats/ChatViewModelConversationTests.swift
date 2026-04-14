import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@MainActor
struct ChatViewModelConversationTests {

    // MARK: - Test Helpers

    private func makeContact(
        id: UUID = UUID(),
        name: String = "Test",
        isFavorite: Bool = false,
        lastMessageDate: Date? = nil
    ) -> ContactDTO {
        ContactDTO(
            id: id,
            radioID: UUID(),
            publicKey: Data(),
            name: name,
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
            isFavorite: isFavorite,
            lastMessageDate: lastMessageDate,
            unreadCount: 0,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }

    // MARK: - favoriteConversations Tests

    @Test("favoriteConversations returns only favorites")
    func favoriteConversationsReturnsOnlyFavorites() {
        let viewModel = ChatViewModel()
        viewModel.conversations = [
            makeContact(name: "Alice", isFavorite: true),
            makeContact(name: "Bob", isFavorite: false),
            makeContact(name: "Charlie", isFavorite: true)
        ]

        let favorites = viewModel.favoriteConversations

        #expect(favorites.count == 2)
        #expect(favorites.allSatisfy { $0.isFavorite })
    }

    @Test("favoriteConversations sorts by lastMessageDate descending")
    func favoriteConversationsSortsByDate() {
        let viewModel = ChatViewModel()
        let older = Date(timeIntervalSince1970: 1000)
        let newer = Date(timeIntervalSince1970: 2000)

        viewModel.conversations = [
            makeContact(name: "Older", isFavorite: true, lastMessageDate: older),
            makeContact(name: "Newer", isFavorite: true, lastMessageDate: newer)
        ]

        let favorites = viewModel.favoriteConversations

        #expect(favorites.count == 2)
        #expect(favorites[0].displayName == "Newer")
        #expect(favorites[1].displayName == "Older")
    }

    @Test("favoriteConversations returns empty when no favorites")
    func favoriteConversationsEmptyWhenNoFavorites() {
        let viewModel = ChatViewModel()
        viewModel.conversations = [
            makeContact(name: "Alice", isFavorite: false),
            makeContact(name: "Bob", isFavorite: false)
        ]

        #expect(viewModel.favoriteConversations.isEmpty)
    }

    // MARK: - nonFavoriteConversations Tests

    @Test("nonFavoriteConversations returns only non-favorites")
    func nonFavoriteConversationsReturnsOnlyNonFavorites() {
        let viewModel = ChatViewModel()
        viewModel.conversations = [
            makeContact(name: "Alice", isFavorite: true),
            makeContact(name: "Bob", isFavorite: false),
            makeContact(name: "Charlie", isFavorite: false)
        ]

        let nonFavorites = viewModel.nonFavoriteConversations

        #expect(nonFavorites.count == 2)
        #expect(nonFavorites.allSatisfy { !$0.isFavorite })
    }

    @Test("nonFavoriteConversations sorts by lastMessageDate descending")
    func nonFavoriteConversationsSortsByDate() {
        let viewModel = ChatViewModel()
        let older = Date(timeIntervalSince1970: 1000)
        let newer = Date(timeIntervalSince1970: 2000)

        viewModel.conversations = [
            makeContact(name: "Older", isFavorite: false, lastMessageDate: older),
            makeContact(name: "Newer", isFavorite: false, lastMessageDate: newer)
        ]

        let nonFavorites = viewModel.nonFavoriteConversations

        #expect(nonFavorites.count == 2)
        #expect(nonFavorites[0].displayName == "Newer")
        #expect(nonFavorites[1].displayName == "Older")
    }

    // MARK: - allConversations Tests

    @Test("allConversations returns favorites first then non-favorites")
    func allConversationsFavoritesFirst() {
        let viewModel = ChatViewModel()
        let now = Date()

        viewModel.conversations = [
            makeContact(name: "NonFav", isFavorite: false, lastMessageDate: now),
            makeContact(name: "Fav", isFavorite: true, lastMessageDate: now.addingTimeInterval(-1000))
        ]

        let all = viewModel.allConversations

        #expect(all.count == 2)
        #expect(all[0].displayName == "Fav")
        #expect(all[1].displayName == "NonFav")
    }

    // MARK: - Cache Invalidation Tests

    @Test("cache invalidates when conversation favorite state changes")
    func cacheInvalidatesOnFavoriteChange() {
        let viewModel = ChatViewModel()
        let contact = makeContact(name: "Test", isFavorite: false)
        viewModel.conversations = [contact]

        // Initial state
        #expect(viewModel.favoriteConversations.isEmpty)
        #expect(viewModel.nonFavoriteConversations.count == 1)

        // Update favorite state
        viewModel.conversations = [
            makeContact(id: contact.id, name: "Test", isFavorite: true)
        ]
        viewModel.invalidateConversationCache()

        // After invalidation
        #expect(viewModel.favoriteConversations.count == 1)
        #expect(viewModel.nonFavoriteConversations.isEmpty)
    }

    // MARK: - Edge Cases

    @Test("handles empty conversations array")
    func handlesEmptyConversations() {
        let viewModel = ChatViewModel()
        viewModel.conversations = []
        viewModel.channels = []
        viewModel.roomSessions = []

        #expect(viewModel.favoriteConversations.isEmpty)
        #expect(viewModel.nonFavoriteConversations.isEmpty)
        #expect(viewModel.allConversations.isEmpty)
    }

    @Test("handles nil lastMessageDate by sorting to end")
    func handlesNilLastMessageDate() {
        let viewModel = ChatViewModel()
        let withDate = Date()

        viewModel.conversations = [
            makeContact(name: "NoDate", isFavorite: true, lastMessageDate: nil),
            makeContact(name: "HasDate", isFavorite: true, lastMessageDate: withDate)
        ]

        let favorites = viewModel.favoriteConversations

        #expect(favorites[0].displayName == "HasDate")
        #expect(favorites[1].displayName == "NoDate")
    }
}
