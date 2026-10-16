@testable import MC1
import Testing

@Suite("Reaction details initial tab")
struct ReactionDetailsSelectionTests {
  @Test
  func `preferred emoji is selected when present`() {
    #expect(
      ReactionDetailsSheet.resolvedSelection(preferred: "❤️", available: ["👍", "❤️", "😂"])
        == "❤️"
    )
  }

  @Test
  func `missing preferred falls back to first`() {
    #expect(
      ReactionDetailsSheet.resolvedSelection(preferred: "🎉", available: ["👍", "❤️"])
        == "👍"
    )
  }

  @Test
  func `nil preferred uses first`() {
    #expect(
      ReactionDetailsSheet.resolvedSelection(preferred: nil, available: ["👍", "❤️"])
        == "👍"
    )
  }

  @Test
  func `empty groups yield nil`() {
    #expect(
      ReactionDetailsSheet.resolvedSelection(preferred: "👍", available: [])
        == nil
    )
  }
}
