import Foundation

/// Data-change and incoming-message notifications broadcast by `SyncCoordinator`.
///
/// Subscribe via `SyncCoordinator.dataEvents()`. The stream is multicast: every
/// subscriber receives every event, so coexisting consumers (`AppState` for
/// version bumps, `MessageEventDispatcher` for chat forwarding) never steal
/// each other's events.
public enum SyncDataEvent: Sendable {
  /// Contacts data changed; observers should reload contact lists.
  case contactsChanged
  /// Conversations data changed; observers should reload chat lists.
  case conversationsChanged
  /// An incoming direct message was persisted for a known contact.
  case directMessageReceived(message: MessageDTO, contact: ContactDTO, scope: ReceiveScope)
  /// An incoming channel message was persisted.
  case channelMessageReceived(
    message: MessageDTO, channelIndex: UInt8, scope: ReceiveScope
  )
  /// An incoming signed room message was persisted.
  case roomMessageReceived(RoomMessageDTO, roomID: UUID?, scope: ReceiveScope)
  /// A reaction was persisted and its target message's summary updated.
  case reactionReceived(
    messageID: UUID, summary: String, scope: ReceiveScope? = nil, contact: ContactDTO? = nil
  )
}
