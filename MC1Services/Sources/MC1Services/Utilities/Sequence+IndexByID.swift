import Foundation

extension Sequence where Element: Identifiable {
    /// Build a `[Element.ID: Int]` mapping each element's ID to its
    /// position in the sequence. Assumes IDs are unique — duplicate keys
    /// trap. Used by chat-timeline code to maintain an O(1) lookup from
    /// message ID to row index.
    public func indexByID() -> [Element.ID: Int] {
        Dictionary(uniqueKeysWithValues: enumerated().map { ($0.element.id, $0.offset) })
    }
}
