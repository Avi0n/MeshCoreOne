import CoreLocation
import Foundation
import MC1Services
import SwiftUI

extension ChatViewModel {
  // MARK: - Item Build

  /// Assemble `MessageBuildInputs` from current bake and env state.
  func makeBuildInputs(for message: MessageDTO, previous: MessageDTO?) -> MessageBuildInputs {
    bake.makeBuildInputs(
      for: message,
      previous: previous,
      envInputs: envInputs,
      senderTables: currentSenderTables()
    )
  }

  /// Single-message convenience that pairs `makeBuildInputs` with the pure
  /// `MessageFragmentBuilder`. Single-row callers (`appendMessageIfNew`,
  /// `rebuildDisplayItem`) keep using this; the
  /// batch path in `buildItems()` calls `makeBuildInputs` on main and then
  /// invokes the builder off-actor with the resulting snapshot.
  func makeItem(for message: MessageDTO, previous: MessageDTO?) -> MessageItem {
    MessageFragmentBuilder.makeItem(
      for: message,
      inputs: makeBuildInputs(for: message, previous: previous),
      envInputs: envInputs
    )
  }

  /// Recover the previous message in display order from the canonical
  /// `messages` array. Survives reordering side effects (e.g.,
  /// `reorderSameSenderClusters`) because it reads the current array at
  /// call time, not an item-index snapshot.
  func previousMessage(for messageID: UUID) -> MessageDTO? {
    guard let index = messages.firstIndex(where: { $0.id == messageID }),
          index > 0 else { return nil }
    return messages[index - 1]
  }
}
