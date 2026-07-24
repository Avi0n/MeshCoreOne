import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Display Items

  /// Optimistically append a message if not already present. Called from
  /// the incoming admission path after the receive-time prefetch resolves
  /// or hits its timeout, and from the outgoing send paths immediately
  /// after `createPendingMessage`. Preserves unread-counter math via the
  /// item-count delta observed by `ChatTiledView`.
  ///
  /// Synchronous: timeline admission and channel sender bookkeeping mutate
  /// Observable state on the main actor in one call frame, so SwiftUI
  /// invalidates dependent views once per change cycle without an explicit
  /// transaction.
  func appendMessageIfNew(_ message: MessageDTO) {
    guard timeline.admit(message) else { return }
    if let senderName = message.senderNodeName,
       let radioID = currentChannel?.radioID {
      addChannelSenderIfNew(senderName, radioID: radioID, timestamp: message.timestamp)
    }
  }

  /// Build MessageItems with pre-computed properties via the shared bake pipeline.
  func buildItems() {
    timeline.rebakeAll()
  }

  /// Get full message DTO for a MessageItem.
  /// Logs a warning if lookup fails (indicates data inconsistency).
  func message(for item: MessageItem) -> MessageDTO? {
    guard let message = messagesByID[item.id] else {
      logger.warning("Message lookup failed for item id=\(item.id)")
      return nil
    }
    return message
  }
}
