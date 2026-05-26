import SwiftUI
import MC1Services

/// Input bar for chat conversations, configured per conversation type.
struct ChatConversationInputBar: View {
    let conversationType: ChatConversationType
    @Binding var composingText: String
    @FocusState.Binding var isFocused: Bool
    let nodeNameByteCount: Int
    let onSend: (String) async -> Void
    let onWillSend: () -> Void

    var body: some View {
        switch conversationType {
        case .dm:
            ChatInputBar(
                text: $composingText,
                isFocused: $isFocused,
                placeholder: L10n.Chats.Chats.Input.Placeholder.directMessage,
                maxBytes: ProtocolLimits.maxDirectMessageLength,
                isEncrypted: true,
                leading: { ChatShareMenu(onInsert: insertShared) }
            ) { text in
                onWillSend()
                Task { await onSend(text) }
            }

        case .channel(let channel):
            let maxBytes = ProtocolLimits.maxChannelMessageLength(
                nodeNameByteCount: nodeNameByteCount
            )
            ChatInputBar(
                text: $composingText,
                isFocused: $isFocused,
                placeholder: conversationType.isPublicStyleChannel
                    ? L10n.Chats.Chats.Channel.typePublic
                    : L10n.Chats.Chats.Channel.typePrivate,
                maxBytes: maxBytes,
                isEncrypted: channel.isEncryptedChannel,
                leading: { ChatShareMenu(onInsert: insertShared) }
            ) { text in
                onWillSend()
                Task { await onSend(text) }
            }
        }
    }

    /// Appends a shared token to the compose field and focuses it, matching the
    /// reply and mention insertion flow. A single space separates the token from
    /// existing text only when the field is non-empty and does not already end in
    /// whitespace.
    private func insertShared(_ shared: String) {
        if !composingText.isEmpty, let last = composingText.last, !last.isWhitespace {
            composingText.append(" ")
        }
        composingText.append(shared)
        isFocused = true
    }
}
