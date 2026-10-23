import Foundation

/// One feature row in a What's New release: an SF Symbol with a localized title
/// and description.
struct WhatsNewItem: Identifiable {
  let id = UUID()
  let symbol: String
  let title: String
  let description: String
  let flipsSymbolVertically: Bool

  init(
    symbol: String,
    title: String,
    description: String,
    flipsSymbolVertically: Bool = false
  ) {
    self.symbol = symbol
    self.title = title
    self.description = description
    self.flipsSymbolVertically = flipsSymbolVertically
  }
}
