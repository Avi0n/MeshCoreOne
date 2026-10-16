import Foundation

/// Outbound-message lifecycle notifications broadcast by `MessageService`.
///
/// Subscribe via `MessageService.statusEvents()`. The stream is multicast:
/// every subscriber receives every event, so coexisting consumers never steal
/// each other's events. Events carry the resolved messageID rather than the
/// raw ackCode so consumers can gate on conversation membership without
/// re-walking the service's pending-ACK table.
public enum MessageStatusEvent: Sendable {
  /// A message reached a resolved status: `.sent` once the radio queues a
  /// DM or channel broadcast, `.delivered` once a DM's end-to-end ACK
  /// arrives. Channel broadcasts have no recipient ACK, so `.sent` is their
  /// terminal success state. `roundTripTime` is the firmware-reported value
  /// when supplied, riding along so the UI can fold status and timing into
  /// one in-place bubble update without re-reading the DTO.
  case statusResolved(messageID: UUID, status: MessageStatus, roundTripTime: UInt32?)
  /// A resend completed with `.sent` committed. Carries no status payload
  /// because resends also mutate `heardRepeats` and `sendCount`; consumers
  /// must refresh the entire DTO rather than apply a status-only update.
  case resent(messageID: UUID)
  /// A retry attempt is in flight; use for UI retry progress.
  case retrying(messageID: UUID, attempt: Int, maxAttempts: Int)
  /// A contact's routing switched between direct and flood during a send.
  case routingChanged(contactID: UUID, isFlood: Bool)
  /// A message failed after exhausting retries or a terminal send error.
  case failed(messageID: UUID)
}
