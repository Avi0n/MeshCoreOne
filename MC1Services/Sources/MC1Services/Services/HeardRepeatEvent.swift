import Foundation

/// Notification that a heard repeat was recorded for a sent channel message.
///
/// Broadcast by `HeardRepeatsService.events()`. The stream is multicast:
/// every subscriber receives every event.
public struct HeardRepeatEvent: Sendable {
  /// The sent message the repeat was correlated to.
  public let messageID: UUID
  /// The message's updated heard-repeat count.
  public let count: Int
}
