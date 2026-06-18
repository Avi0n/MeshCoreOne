import SwiftUI
import MC1Services

/// Info sheet content for chat conversations, configured per conversation type.
struct ChatConversationInfoSheet: View {
    let conversationType: ChatConversationType
    let chatViewModel: ChatViewModel
    let onClearChannelMessages: () async -> Void
    let onDeleteChannel: () -> Void

    var body: some View {
        switch conversationType {
        case .dm(let contact):
            NavigationStack {
                ContactDetailView(contact: contact, showFromDirectChat: true)
            }

        case .channel(let channel):
            ChannelInfoSheet(
                channel: channel,
                onClearMessages: {
                    Task { await onClearChannelMessages() }
                },
                onDelete: {
                    onDeleteChannel()
                }
            )
            .environment(\.chatViewModel, chatViewModel)
        }
    }
}
