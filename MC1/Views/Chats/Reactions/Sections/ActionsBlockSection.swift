import SwiftUI

struct ActionsBlockSection: View {
    let availability: MessageActionAvailability
    let onSelectAction: (MessageAction) -> Void

    var body: some View {
        if availability.canBlockSender {
            Divider()
                .padding(.vertical, 8)
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.blockSender,
                icon: "hand.raised",
                isDestructive: true,
                action: { onSelectAction(.blockSender) }
            )
        }
    }
}
