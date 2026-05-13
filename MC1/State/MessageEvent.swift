import Foundation
import MC1Services

/// Events emitted by mesh subsystems and consumed by chat / room views via
/// `MessageEventStream`. Each case is sourced from a concrete service
/// callback wired in `AppState.wireMessageEvents` — there are no
/// speculative or unreachable cases. Consumers should switch
/// exhaustively (no `default`) so a new case becomes a compile error
/// rather than a silent skip.
public enum MessageEvent: Sendable, Equatable {
    case directMessageReceived(message: MessageDTO, contact: ContactDTO)
    case channelMessageReceived(message: MessageDTO, channelIndex: UInt8)
    case roomMessageReceived(message: RoomMessageDTO, sessionID: UUID)
    case messageStatusResolved(messageID: UUID)
    case messageFailed(messageID: UUID)
    case messageRetrying(messageID: UUID, attempt: Int, maxAttempts: Int)
    case heardRepeatRecorded(messageID: UUID, count: Int)
    case reactionReceived(messageID: UUID, summary: String)
    case routingChanged(contactID: UUID, isFlood: Bool)
    case roomMessageStatusUpdated(messageID: UUID)
    case roomMessageFailed(messageID: UUID)
}
