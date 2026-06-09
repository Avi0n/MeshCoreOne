import SwiftUI

struct ActionsBlockSection: View {
    let availability: MessageActionAvailability
    let onSelectAction: (MessageAction) -> Void

    var body: some View {
        if availability.canBlockSender {
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.blockSender,
                icon: "hand.raised",
                isDestructive: true,
                action: { onSelectAction(.blockSender) }
            )
            .liquidGlass()
            .padding(.top, 6)
        }
    }
}
