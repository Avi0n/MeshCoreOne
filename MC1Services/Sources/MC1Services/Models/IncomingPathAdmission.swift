import Foundation

/// One store turn's decision for an incoming repeat path.
public enum IncomingPathAdmission: Sendable, Equatable {
  /// The parent message is gone, so nothing was written.
  case missingParent
  /// `pathNodes` was nil. The observed path is now the canonical path.
  case adoptedCanonical
  /// The path was new and is stored as an extra arrival. The value is the new count.
  case recordedExtra(Int)
  /// The path or RX entry was already recorded.
  case unchanged
}
