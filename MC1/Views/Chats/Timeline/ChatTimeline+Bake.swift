import Foundation
import MC1Services

extension ChatTimeline {
  // MARK: - Baking

  /// Rebuilds every render item from canonical messages via the shared bake
  /// pipeline. A no-op while unbound; a stale writer drops the build at the
  /// coordinator.
  func rebakeAll() {
    guard let coordinator, let writer else { return }
    bake.bakeAll(
      messages: coordinator.messages,
      writer: writer,
      envInputs: envInputs,
      senderTables: senderTablesProvider(),
      postApply: postApply
    )
  }

  /// Rebuilds a single row's `MessageItem` with current preview, image, and
  /// message state. No-ops when the message is no longer present.
  func rebakeRow(_ messageID: UUID) {
    guard let coordinator, let writer else { return }
    guard let message = coordinator.messagesByID[messageID] else {
      logger.warning("rebake requested for missing message id \(messageID)")
      return
    }
    let previous: MessageDTO? = {
      guard let index = coordinator.messages.firstIndex(where: { $0.id == messageID }),
            index > 0 else { return nil }
      return coordinator.messages[index - 1]
    }()
    writer.updateRenderItem(id: messageID) { _ in
      makeItem(for: message, previous: previous)
    }
  }

  /// Builds one `MessageItem` from current bake and env state. URL detection
  /// and decoded-cache rehydration run synchronously inside
  /// `makeBuildInputs`, so the returned item already carries its preview
  /// fragment at a stable height.
  func makeItem(for message: MessageDTO, previous: MessageDTO?) -> MessageItem {
    MessageFragmentBuilder.makeItem(
      for: message,
      inputs: bake.makeBuildInputs(
        for: message,
        previous: previous,
        envInputs: envInputs,
        senderTables: senderTablesProvider()
      ),
      envInputs: envInputs
    )
  }
}
