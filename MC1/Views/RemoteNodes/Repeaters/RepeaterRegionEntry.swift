import Foundation

struct RepeaterRegionEntry: Identifiable, Equatable {
  static let unscopedName = "*"

  enum Parent: Hashable, Sendable, Identifiable {
    case unscoped
    case named(String)

    var id: String {
      switch self {
      case .unscoped: RepeaterRegionEntry.unscopedName
      case let .named(name): name
      }
    }
  }

  var id: String {
    name
  }

  let name: String
  /// Nil for the Unscoped root. Children of Unscoped store `unscopedName`, not nil.
  let parentName: String?
  let depth: Int
  var floodAllowed: Bool
  var isHome: Bool

  var isUnscoped: Bool {
    name == Self.unscopedName
  }

  var namedParent: String? {
    guard let parentName, parentName != Self.unscopedName else { return nil }
    return parentName
  }
}
