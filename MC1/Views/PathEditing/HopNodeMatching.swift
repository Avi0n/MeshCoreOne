import Foundation

/// Query matching for the shared Add-Hop picker, kept as free functions so both
/// the picker and the conforming view models can reach them without coupling to a
/// concrete view model.
enum HopNodeMatching {
  /// True when `query` is non-empty and every character is a hex digit.
  /// All-digit names like "1234" therefore match both name and pubkey branches —
  /// acceptable since both surfaces produce the same row.
  static func isHexQuery(_ query: String) -> Bool {
    !query.isEmpty && query.allSatisfy(\.isHexDigit)
  }

  /// True if `query` is empty, matches the node name as a substring, or (for a
  /// hex query) prefixes the public-key hex. Name matching uses
  /// `range(of:options:)` with `[.caseInsensitive, .diacriticInsensitive]` so
  /// Turkish İ/ı and NFC/NFD Cyrillic fold correctly regardless of runtime locale.
  static func matches(_ node: PickerNode, query: String) -> Bool {
    guard !query.isEmpty else { return true }
    let nameHit = node.displayName.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil
    if isHexQuery(query) {
      return nameHit || node.publicKeyHex.lowercased().hasPrefix(query.lowercased())
    }
    return nameHit
  }

  /// Filter `nodes` to those matching `query`, preserving order.
  static func filtered(_ nodes: [PickerNode], by query: String) -> [PickerNode] {
    guard !query.isEmpty else { return nodes }
    return nodes.filter { matches($0, query: query) }
  }
}
