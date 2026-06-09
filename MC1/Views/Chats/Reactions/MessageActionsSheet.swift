import MC1Services
import SwiftUI

enum MessageAction: Equatable {
    case react(String)
    case reply
    case copy
    case sendAgain
    case sendDM
    case blockSender
    case delete
}

/// iMessage-style long-press menu: a floating emoji reaction bar above a
/// compact action list. Designed to be presented in a `.popover` anchored to
/// the message bubble (haptic touch), with a clear presentation background so
/// the cards float detached.
struct MessageActionsMenu: View {
    @Environment(\.dismiss) private var dismiss

    let message: MessageDTO
    let senderName: String
    let senderMatchKind: NodeNameMatchKind
    let recentEmojis: [String]
    let onAction: (MessageAction) -> Void
    let onShowInfo: () -> Void

    @State private var destructiveHapticTrigger = 0
    @State private var showEmojiPicker = false

    private var availability: MessageActionAvailability {
        MessageActionAvailability(message: message)
    }

    private func perform(_ action: MessageAction) {
        if action == .delete || action == .blockSender { destructiveHapticTrigger += 1 }
        dismiss()
        onAction(action)
    }

    var body: some View {
        VStack(spacing: 8) {
            // Emoji reaction bar
            EmojiPickerRow(
                emojis: recentEmojis,
                onSelect: { perform(.react($0)) },
                onOpenKeyboard: { showEmojiPicker = true }
            )
            .padding(.vertical, 6)
            .liquidGlass(in: .capsule)

            // Primary actions
            VStack(spacing: 0) {
                if availability.canReply {
                    ActionButton(title: L10n.Chats.Chats.Message.Action.reply,
                                 icon: "arrowshape.turn.up.left",
                                 action: { perform(.reply) })
                    rowDivider
                }
                if availability.canSendDM {
                    ActionButton(title: L10n.Chats.Chats.Message.Action.sendDM,
                                 icon: "bubble.left.and.bubble.right",
                                 action: { perform(.sendDM) })
                    rowDivider
                }
                ActionButton(title: L10n.Chats.Chats.Message.Action.copy,
                             icon: "doc.on.doc",
                             action: { perform(.copy) })
                if availability.canSendAgain {
                    rowDivider
                    ActionButton(title: L10n.Chats.Chats.Message.Action.sendAgain,
                                 icon: "arrow.uturn.forward",
                                 action: { perform(.sendAgain) })
                }
                rowDivider
                ActionButton(title: L10n.Chats.Chats.Message.Action.details,
                             icon: "info.circle",
                             action: {
                                 dismiss()
                                 DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                                     onShowInfo()
                                 }
                             })
            }
            .liquidGlass(in: .rect(cornerRadius: 14))

            // Destructive actions
            if availability.canBlockSender {
                ActionButton(title: L10n.Chats.Chats.Message.Action.blockSender,
                             icon: "hand.raised",
                             isDestructive: true,
                             action: { perform(.blockSender) })
                .liquidGlass(in: .rect(cornerRadius: 14))
            }
            if availability.canDelete {
                ActionButton(title: L10n.Chats.Chats.Message.Action.delete,
                             icon: "trash",
                             isDestructive: true,
                             action: { perform(.delete) })
                .liquidGlass(in: .rect(cornerRadius: 14))
            }
        }
        .frame(width: 260)
        .padding(8)
        .sensoryFeedback(.warning, trigger: destructiveHapticTrigger)
        .sheet(isPresented: $showEmojiPicker) {
            EmojiPickerSheet(onSelect: { perform(.react($0)) })
        }
    }

    private var rowDivider: some View {
        Divider().padding(.leading, 52)
    }
}

#Preview("Incoming") {
    let message = Message(
        radioID: UUID(), contactID: UUID(),
        text: "Hey, can you meet me at the coffee shop?",
        directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue, pathLength: 2
    )
    message.snr = 8.5
    return MessageActionsMenu(
        message: MessageDTO(from: message),
        senderName: "Alice", senderMatchKind: .exact,
        recentEmojis: RecentEmojisStore.defaultEmojis,
        onAction: { _ in }, onShowInfo: {}
    )
    .padding()
    .background(.gray)
}
