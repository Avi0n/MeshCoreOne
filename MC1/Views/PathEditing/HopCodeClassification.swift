import Foundation

/// Outcome of a single hex code parsed from a bulk-add entry.
enum HopCodeStatus {
  /// Valid, resolves to a node, and fits within the hop cap. Carries the
  /// prebuilt hop so callers append without re-parsing or re-resolving.
  case willAdd(PathHop)
  case alreadyInPath
  case notFound // valid hex but no matching node
  case invalidFormat // wrong length or non-hex
  case pathFull // valid and resolvable but past the hop cap
}

/// One parsed code from a bulk-add entry, with its status.
struct HopCodeClassification: Identifiable {
  /// The uppercased code, unique after de-duplication; also the stable id.
  let code: String
  let status: HopCodeStatus

  var id: String {
    code
  }

  /// True when tapping "Add" will append this code to the path.
  var willBeAdded: Bool {
    if case .willAdd = status { return true }
    return false
  }
}
