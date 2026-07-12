import Foundation

/// Shared bounds for radio-backed commands issued from list actions.
enum RadioCommandTimeout {
  /// Upper bound for a delete that round-trips to the radio (channel clear, room leave, remove
  /// node). Too short re-admits a slow-but-successful delete as a spurious error; on the gated
  /// paths an expiry surfaces on a still-visible row, never a hidden one.
  static let delete: Duration = .seconds(7)
}
