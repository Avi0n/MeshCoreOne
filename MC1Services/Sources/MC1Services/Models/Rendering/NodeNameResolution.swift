import Foundation

/// Whether a name resolution corresponds to an exact public-key match, a
/// proximity fallback within a hash-prefix collision set, or no match at all.
public enum NodeNameMatchKind: Sendable, Equatable, Hashable {
  case exact
  case fallback
  case unresolved
}

/// Resolution result for a sender node name. Pairs the resolved display name
/// with the confidence level (`matchKind`) so the caller can disambiguate
/// exact identity from proximity-based guesses.
public struct NodeNameResolution: Sendable, Equatable, Hashable {
  public let displayName: String
  public let matchKind: NodeNameMatchKind

  public var isFallback: Bool {
    matchKind == .fallback
  }

  public init(displayName: String, matchKind: NodeNameMatchKind) {
    self.displayName = displayName
    self.matchKind = matchKind
  }
}
