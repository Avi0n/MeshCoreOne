import Foundation

public extension Sequence where Element: Identifiable {
  /// Build a `[Element.ID: Int]` mapping each element's ID to its
  /// position in the sequence. On duplicate IDs the later offset wins,
  /// matching the last-write-wins semantics used by `replaceAll` and
  /// related chat-timeline mutations. Used by chat-timeline code to
  /// maintain an O(1) lookup from message ID to row index.
  func indexByID() -> [Element.ID: Int] {
    Dictionary(enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { _, new in new })
  }
}
