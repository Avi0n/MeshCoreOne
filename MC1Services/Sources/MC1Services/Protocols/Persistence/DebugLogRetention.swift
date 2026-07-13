import Foundation

/// Retention policy for persisted debug log entries, shared by the
/// connection-time prune and the diagnostics export so an export can
/// return everything retention keeps.
public enum DebugLogRetention {
  /// Entries older than this window are pruned.
  public static let window: TimeInterval = 7 * 24 * 60 * 60

  /// Hard row ceiling enforced after the time-based prune, as a disk
  /// backstop when the window alone would exceed it.
  public static let maxEntries = 50000
}
