import Foundation

/// Result of removing unfavorited nodes from the device
public struct RemoveUnfavoritedResult: Sendable {
  public let removed: Int
  public let total: Int
}
