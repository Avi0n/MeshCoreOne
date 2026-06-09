import MC1Services
import SwiftUI

struct ActionsButtonsSection: View {
    let availability: MessageActionAvailability
    let onSelectAction: (MessageAction) -> Void

    @AppStorage(AppStorageKey.replyWithQuote.rawValue) private var replyWithQuote = AppStorageKey.defaultReplyWithQuote

    var body: some View {
        if availability.canReply {
            ActionButton(
                title: replyWithQuote ? L10n.Chats.Chats.Message.Action.reply : L10n.Chats.Chats.Message.Action.mention,
                icon: "arrowshape.turn.up.left",
                action: { onSelectAction(.reply) }
            )
            rowDivider
        }

        if availability.canSendDM {
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.sendDM,
                icon: "bubble.left.and.bubble.right",
                action: { onSelectAction(.sendDM) }
            )
            rowDivider
        }

        ActionButton(
            title: L10n.Chats.Chats.Message.Action.copy,
            icon: "doc.on.doc",
            action: { onSelectAction(.copy) }
        )

        if availability.canSendAgain {
            rowDivider
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.sendAgain,
                icon: "arrow.uturn.forward",
                action: { onSelectAction(.sendAgain) }
            )
        }
    }

    private var rowDivider: some View {
        Divider().padding(.leading, 52)
    }
}
