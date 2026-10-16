import Foundation
import Observation

/// View model for the emoji picker sheet
@Observable
@MainActor
final class EmojiPickerViewModel {
  private let provider = EmojiProvider()
  private var searchTask: Task<Void, Never>?

  var searchQuery: String = "" {
    didSet {
      searchTask?.cancel()
      searchTask = Task {
        await updateCategories()
      }
    }
  }

  private(set) var categories: [EmojiCategoryData] = []

  func load() async {
    await updateCategories()
  }

  func markAsFrequentlyUsed(_ emoji: String) {
    provider.markAsFrequentlyUsed(emoji)
  }

  private func updateCategories() async {
    let query = searchQuery.isEmpty ? nil : searchQuery
    let result = await provider.categories(searchQuery: query)
    guard !Task.isCancelled else { return }
    categories = result
  }
}
