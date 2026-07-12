import SwiftUI

/// The destructive action group (block sender, delete) plus the single separator
/// above it. Owning both rows and the divider in one view keeps the group's
/// separator rule in one place, instead of a hidden contract between sibling
/// sections about which one draws it.
struct ActionsDestructiveSection: View {
  let availability: MessageActionAvailability
  let onSelectAction: (MessageAction) -> Void

  var body: some View {
    if availability.canBlockSender || availability.canDelete {
      Divider()
        .padding(.vertical, 8)
      if availability.canBlockSender {
        ActionButton(
          title: L10n.Chats.Chats.Message.Action.blockSender,
          icon: "hand.raised",
          isDestructive: true,
          action: { onSelectAction(.blockSender) }
        )
      }
      if availability.canDelete {
        ActionButton(
          title: L10n.Chats.Chats.Message.Action.delete,
          icon: "trash",
          isDestructive: true,
          action: { onSelectAction(.delete) }
        )
      }
    }
  }
}
