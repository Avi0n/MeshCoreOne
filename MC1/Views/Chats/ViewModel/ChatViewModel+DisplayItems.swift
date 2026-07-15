import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Display Items

  /// Optimistically append a message if not already present. Called from
  /// the incoming admission path after the receive-time prefetch resolves
  /// or hits its timeout, and from the outgoing send paths immediately
  /// after `createPendingMessage`. Preserves unread-counter math via the
  /// item-count delta observed by `ChatTiledView`.
  func appendMessageIfNew(_ message: MessageDTO) {
    guard coordinator != nil, let timelineWriter else { return }
    let previous = messages.last

    // Synchronous append: coordinator append, render item insertion, and
    // channel sender bookkeeping all mutate Observable state on the main
    // actor in one call frame, so SwiftUI already invalidates dependent
    // views once per change cycle without an explicit transaction.
    guard timelineWriter.append(message) else { return }
    let newItem = makeItem(for: message, previous: previous)
    timelineWriter.appendRenderItem(newItem)
    if let senderName = message.senderNodeName,
       let radioID = currentChannel?.radioID {
      addChannelSenderIfNew(senderName, radioID: radioID, timestamp: message.timestamp)
    }

    // URL detection and cache rehydration happen synchronously inside
    // `makeItem` (see `seedPreviewStateIfNeeded`), so the appended row is
    // already carrying its preview fragment.
  }

  /// Build MessageItems with pre-computed properties via the shared bake pipeline.
  func buildItems() {
    guard let coordinator, let timelineWriter else { return }
    bake.bakeAll(
      messages: coordinator.messages,
      writer: timelineWriter,
      envInputs: envInputs,
      senderTables: currentSenderTables(),
      postApply: { [weak self] in self?.decodeLegacyPreviewImages() }
    )
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
