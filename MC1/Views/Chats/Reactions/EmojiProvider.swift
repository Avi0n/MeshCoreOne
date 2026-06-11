import Foundation
import SwiftUI
import Emojibase

/// View data for a single emoji in the picker
struct EmojiItem: Identifiable, Equatable {
    let id: String
    let unicode: String
    let label: String

    init(id: String, unicode: String, label: String) {
        self.id = id
        self.unicode = unicode
        self.label = label
    }

    init(emoji: Emoji, categoryID: String) {
        self.id = "\(categoryID)-\(emoji.hexcode)"
        self.unicode = emoji.unicode
        self.label = emoji.label
    }
}

/// View data for an emoji category
struct EmojiCategoryData: Identifiable {
    let id: String
    let emojis: [EmojiItem]

    var localizedName: String {
        switch id {
        case "frequent":
            return L10n.Chats.Reactions.Emoji.Category.frequent
        case EmojibaseCategory.people.rawValue:
            return L10n.Chats.Reactions.Emoji.Category.people
        case EmojibaseCategory.nature.rawValue:
            return L10n.Chats.Reactions.Emoji.Category.nature
        case EmojibaseCategory.foods.rawValue:
            return L10n.Chats.Reactions.Emoji.Category.foods
        case EmojibaseCategory.activity.rawValue:
            return L10n.Chats.Reactions.Emoji.Category.activity
        case EmojibaseCategory.places.rawValue:
            return L10n.Chats.Reactions.Emoji.Category.places
        case EmojibaseCategory.objects.rawValue:
            return L10n.Chats.Reactions.Emoji.Category.objects
        case EmojibaseCategory.symbols.rawValue:
            return L10n.Chats.Reactions.Emoji.Category.symbols
        case EmojibaseCategory.flags.rawValue:
            return L10n.Chats.Reactions.Emoji.Category.flags
        default:
            return id.capitalized
        }
    }
}

/// Loading state for emoji data
enum EmojiProviderState {
    case notLoaded
    case loading
    case loaded
    case failed(Error)
}

/// Provides emoji data for the picker with search and frequently-used tracking
@MainActor
@Observable
final class EmojiProvider {
    private(set) var state: EmojiProviderState = .notLoaded
    private var store: EmojibaseStore?

    @ObservationIgnored
    @AppStorage("frequentEmojis") private var frequentEmojisData: Data = Data()

    private static let maxFrequentEmojis = 20
    private static let categoryOrder: [EmojibaseCategory] = [
        .people, .nature, .foods, .activity, .places, .objects, .symbols, .flags
    ]

    /// Loads emoji data if not already loaded
    func loadIfNeeded() async {
        guard case .notLoaded = state else { return }

        state = .loading
        do {
            let datasource = EmojibaseDatasource()
            store = try await datasource.load()
            state = .loaded
        } catch {
            state = .failed(error)
        }
    }

    /// Returns categories filtered by search query
    func categories(searchQuery: String?) async -> [EmojiCategoryData] {
        await loadIfNeeded()

        guard let store else { return [] }

        var result: [EmojiCategoryData] = []

        // Add frequently used section if no search query
        if searchQuery == nil || searchQuery?.isEmpty == true {
            let frequent = frequentlyUsedEmojis()
            if !frequent.isEmpty {
                let items = frequent.enumerated().map { index, unicode in
                    EmojiItem(id: "frequent-\(index)", unicode: unicode, label: "")
                }
                result.append(EmojiCategoryData(id: "frequent", emojis: items))
            }
        }

        // Add standard categories
        for category in Self.categoryOrder {
            guard let emojis = store.emojisFor(category: category) else { continue }

            let filtered: [Emoji]
            if let query = searchQuery, !query.isEmpty {
                let lowercasedQuery = query.lowercased()
                filtered = emojis.filter { emoji in
                    emoji.label.localizedStandardContains(lowercasedQuery) ||
                    emoji.shortcodes.contains { $0.localizedStandardContains(lowercasedQuery) } ||
                    emoji.tags?.contains { $0.localizedStandardContains(lowercasedQuery) } == true
                }
            } else {
                filtered = emojis
            }

            if !filtered.isEmpty {
                let items = filtered.map { EmojiItem(emoji: $0, categoryID: category.rawValue) }
                result.append(EmojiCategoryData(id: category.rawValue, emojis: items))
            }
        }

        return result
    }

    /// Marks an emoji as frequently used
    func markAsFrequentlyUsed(_ emoji: String) {
        var frequent = frequentlyUsedEmojis()

        // Remove if already present (will re-add at front)
        frequent.removeAll { $0 == emoji }

        // Add to front
        frequent.insert(emoji, at: 0)

        // Trim to max size
        if frequent.count > Self.maxFrequentEmojis {
            frequent = Array(frequent.prefix(Self.maxFrequentEmojis))
        }

        // Persist
        if let data = try? JSONEncoder().encode(frequent) {
            frequentEmojisData = data
        }
    }

    /// Returns the list of frequently used emojis
    func frequentlyUsedEmojis() -> [String] {
        guard let emojis = try? JSONDecoder().decode([String].self, from: frequentEmojisData) else {
            return []
        }
        return emojis
    }
}
