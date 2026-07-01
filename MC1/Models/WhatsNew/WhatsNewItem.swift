import Foundation

/// One feature row in a What's New release: an SF Symbol with a localized title
/// and description.
struct WhatsNewItem: Identifiable {
  let id = UUID()
  let symbol: String
  let title: String
  let description: String
}
