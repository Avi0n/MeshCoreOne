import Foundation
import MeshCore

/// Dual-field read/write helpers for message and RX-log region labeling.
///
/// MeshCore owns `RegionMatchResult`. This type maps match results to storage
/// fields and coalesces dual fields for readers (including rows that only have
/// `regionScope`).
public enum RegionScopeSemantics {
  /// Compact chip join for multi-match labels (e.g. `de-hh / de-by`).
  public static let chipNameSeparator = " / "

  /// Map a match result into the two persisted fields.
  ///
  /// - none → `(nil, [])`
  /// - unique → `(name, [name])`
  /// - ambiguous → `(nil, sorted names)` — never a single first-match name
  public static func storageFields(
    from match: RegionMatchResult
  ) -> (regionScope: String?, regionScopeMatches: [String]) {
    switch match {
    case .none:
      return (nil, [])
    case let .unique(name):
      let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
      guard !trimmed.isEmpty else { return (nil, []) }
      return (trimmed, [trimmed])
    case let .ambiguous(names):
      let filtered = Self.filteredSortedNames(names)
      switch filtered.count {
      case 0:
        return (nil, [])
      case 1:
        return (filtered[0], filtered)
      default:
        return (nil, filtered)
      }
    }
  }

  /// Dual-field read. Multi-match wins over a non-nil `regionScope` so a stale
  /// single name never surfaces when the match list has two or more entries.
  ///
  /// Priority: matches ≥ 2 → ambiguous; matches == 1 → unique; empty matches
  /// with non-nil scope → unique (legacy); both empty → none.
  public static func coalesce(
    scope: String?,
    matches: [String]
  ) -> RegionMatchResult {
    let filtered = Self.filteredSortedNames(matches)
    switch filtered.count {
    case 0:
      if let scope {
        let trimmed = scope.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? .none : .unique(trimmed)
      }
      return .none
    case 1:
      return .unique(filtered[0])
    default:
      return .ambiguous(filtered)
    }
  }

  /// Chip label from a coalesced result. Nil hides the chip.
  public static func chipLabel(from match: RegionMatchResult) -> String? {
    switch match {
    case .none:
      nil
    case let .unique(name):
      name
    case let .ambiguous(names):
      names.joined(separator: chipNameSeparator)
    }
  }

  /// Post-filter match names for popover / a11y / Message Info lists.
  static func matchNames(from match: RegionMatchResult) -> [String] {
    switch match {
    case .none:
      []
    case let .unique(name):
      [name]
    case let .ambiguous(names):
      names
    }
  }

  /// Blank-filter then `localizedStandardCompare` ascending, de-duplicated.
  private static func filteredSortedNames(_ names: [String]) -> [String] {
    let trimmed = names
      .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
      .filter { !$0.isEmpty }
    return Array(Set(trimmed)).sorted {
      $0.localizedStandardCompare($1) == .orderedAscending
    }
  }
}
