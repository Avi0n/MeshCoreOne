import Foundation
import MC1Services

/// Events emitted by mesh subsystems and consumed by chat / room views via
/// `MessageEventStream`. Each case is sourced from a concrete service
/// event stream consumed by `MessageEventDispatcher`; there are no
/// speculative or unreachable cases. Consumers should switch
/// exhaustively (no `default`) so a new case becomes a compile error
/// rather than a silent skip.
enum MessageEvent: Equatable {
  case directMessageReceived(message: MessageDTO, contact: ContactDTO)
  case channelMessageReceived(message: MessageDTO, channelIndex: UInt8)
  case roomMessageReceived(message: RoomMessageDTO, sessionID: UUID)
  /// Fired when a message's status resolves to .sent or .delivered for an
  /// original (non-resend) send. `roundTripTime` is supplied only when firmware
  /// reports it (.delivered via end-to-end ACK); .sent transitions and
  /// finalizeSend-path .delivered pass nil. Consumers may animate the bubble
  /// status footer in place without a DB fetch — the dispatcher writes the DB
  /// row before firing for all five sites that route through this case.
  case messageStatusResolved(messageID: UUID, status: MessageStatus, roundTripTime: UInt32? = nil)
  /// Fired after a channel-message resend (`MessageService.resendChannelMessage`)
  /// completes. Carries no status payload because the resend path mutates
  /// `heardRepeats` and `sendCount` alongside the status flip, and the bubble
  /// status row reads both fields off the DTO. Consumers must route this case
  /// through `enqueueReload` so the next refresh re-fetches every affected
  /// field — the in-place `applyStatusUpdate` helper cannot refresh
  /// `heardRepeats`/`sendCount`.
  case messageResent(messageID: UUID)
  case messageFailed(messageID: UUID)
  case messageRetrying(messageID: UUID, attempt: Int, maxAttempts: Int)
  case heardRepeatRecorded(messageID: UUID, count: Int)
  case reactionReceived(messageID: UUID, summary: String)
  case routingChanged(contactID: UUID, isFlood: Bool)
  case roomMessageStatusUpdated(messageID: UUID)
  case roomMessageFailed(messageID: UUID)
}
