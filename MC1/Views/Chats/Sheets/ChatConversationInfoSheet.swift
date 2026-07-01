import MC1Services
import SwiftUI

/// Info sheet content for chat conversations, configured per conversation type.
struct ChatConversationInfoSheet: View {
  let conversationType: ChatConversationType
  let chatViewModel: ChatViewModel
  let onClearChannelMessages: () async -> Void
  let onClearDirectMessages: () async -> Void
  let onDeleteChannel: () -> Void

  var body: some View {
    switch conversationType {
    case let .dm(contact):
      NavigationStack {
        ContactDetailView(
          contact: contact,
          showFromDirectChat: true,
          onClearMessages: { Task { await onClearDirectMessages() } }
        )
      }

    case let .channel(channel):
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
