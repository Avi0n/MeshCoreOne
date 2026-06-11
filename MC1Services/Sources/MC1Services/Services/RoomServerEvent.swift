import Foundation

/// Room-server notifications broadcast by `RoomServerService.events()`.
/// The stream is multicast: every subscriber receives every event.
public enum RoomServerEvent: Sendable {
    /// An outbound room message's delivery status changed.
    case statusUpdated(messageID: UUID, status: MessageStatus)
    /// An incoming message recovered a disconnected room session.
    case connectionRecovered(sessionID: UUID)
}
