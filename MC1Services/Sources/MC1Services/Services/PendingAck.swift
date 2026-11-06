import Foundation

/// Tracks pending ACKs for a single outgoing direct message across retry attempts.
///
/// All attempts for the same message share one entry and may add ACK codes.
///
/// Only DMs are tracked here. `RoomServerService` waits for room post ACKs separately.
struct PendingAck {
  struct Owner: Equatable {
    enum Phase {
      case insert, convert, saved
    }

    let id: UUID
    let radioID: UUID
    let deadline: Date
    var phase: Phase
  }

  let messageID: UUID
  let contactID: UUID
  var ackCodes: Set<Data>
  var sentAt: Date
  /// The latest send attempt's ACK-wait derived from the firmware's `est_timeout` hint.
  /// `checkExpiredAcks` reads this as the per-attempt floor on the give-up deadline
  /// so a slow high-spreading-factor round-trip is not failed while still in flight.
  var timeout: TimeInterval
  var isDelivered: Bool = false
  var owner: Owner?
  /// Set when an acknowledgement matches and kept until the terminal save commits.
  var matchedAckCode: UInt32?
  var matchedTripTime: UInt32?
  /// Admission lease for this ACK. A restored live ACK uses its persisted owner ID.
  var sendLeaseID: UUID?

  var isAwaitingAck: Bool {
    !isDelivered && matchedAckCode == nil
  }

  func expiresAt(giveUpWindow: TimeInterval) -> Date {
    owner?.deadline ?? sentAt.addingTimeInterval(max(giveUpWindow, timeout))
  }
}
