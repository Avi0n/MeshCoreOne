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
  /// Nickname for a channel sender matched by name only, so identity is
  /// unverified. Non-nil solely on the channel-sender resolution path; always
  /// nil for repeater and mesh-path resolutions.
  public let unverifiedNickname: String?

  public var isFallback: Bool {
    matchKind == .fallback
  }

  public init(
    displayName: String,
    matchKind: NodeNameMatchKind,
    unverifiedNickname: String? = nil
  ) {
    self.displayName = displayName
    self.matchKind = matchKind
    self.unverifiedNickname = unverifiedNickname
  }
}
