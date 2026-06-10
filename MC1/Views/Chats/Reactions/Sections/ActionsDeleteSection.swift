import SwiftUI

struct ActionsDeleteSection: View {
    let availability: MessageActionAvailability
    let onSelectAction: (MessageAction) -> Void

    var body: some View {
        if availability.canDelete {
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.delete,
                icon: "trash",
                isDestructive: true,
                action: { onSelectAction(.delete) }
            )
            .liquidGlass()
            .padding(.top, 6)
        }
    }
}
