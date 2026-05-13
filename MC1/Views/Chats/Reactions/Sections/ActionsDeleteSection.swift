import SwiftUI

struct ActionsDeleteSection: View {
    let availability: MessageActionAvailability
    let onSelectAction: (MessageAction) -> Void

    var body: some View {
        if availability.canDelete {
            Divider()
                .padding(.vertical, 8)
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.delete,
                icon: "trash",
                isDestructive: true,
                action: { onSelectAction(.delete) }
            )
        }
    }
}
