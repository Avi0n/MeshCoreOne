import Foundation

/// Rewrites remote `clock sync` to `time <host-epoch>`. Companion firmware
/// restamps CLI packets to its own RTC, so the packet timestamp is not the phone clock.
public enum RemoteCLICommandRewriter: Sendable {
  public static let clockSyncCommand = "clock sync"
  public static let timeCommandPrefix = "time "

  public static func rewrite(_ command: String, now: Date = Date()) -> String {
    let normalized = command.split(whereSeparator: \.isWhitespace).joined(separator: " ").lowercased()
    guard normalized == clockSyncCommand else { return command }
    return timeCommandPrefix + String(epochSeconds32(now))
  }

  /// Saturates pre-1970 and post-2106 dates instead of trapping `UInt32(_:)`.
  private static func epochSeconds32(_ date: Date) -> UInt32 {
    let seconds = date.timeIntervalSince1970
    guard seconds.isFinite, seconds > 0 else { return 0 }
    guard seconds < Double(UInt32.max) else { return UInt32.max }
    return UInt32(seconds)
  }
}
