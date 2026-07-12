import SwiftUI

/// Long-press context-menu actions for a conversation row: delete, mute/unmute, favorite/unfavorite.
/// Delete is gated while a removal is in flight so a rapid re-press can't double-fire.
struct ConversationContextMenuModifier: ViewModifier {
  @Environment(\.appState) private var appState

  let conversation: Conversation
  let viewModel: ChatViewModel
  let onDelete: () -> Void

  private var isConnected: Bool {
    appState.connectionState == .ready
  }

  private var isTogglingFavorite: Bool {
    guard case let .direct(contact) = conversation else { return false }
    return viewModel.togglingFavoriteID == contact.id
  }

  func body(content: Content) -> some View {
    content.contextMenu {
      Button(role: .destructive) {
        onDelete()
      } label: {
        Label(L10n.Chats.Chats.Action.delete, systemImage: "trash")
      }
      .disabled(!isConnected || viewModel.isDeletePending(conversation.id))

      Button {
        Task {
          await viewModel.toggleMute(conversation)
        }
      } label: {
        Label(
          conversation.isMuted ? L10n.Chats.Chats.Action.unmute : L10n.Chats.Chats.Action.mute,
          systemImage: conversation.isMuted ? "bell" : "bell.slash"
        )
      }
      .disabled(!isConnected)

      Button {
        Task {
          await viewModel.toggleFavorite(conversation, disableAnimation: true)
        }
      } label: {
        Label(
          conversation.isFavorite ? L10n.Chats.Chats.Action.unfavorite : L10n.Chats.Chats.Action.favorite,
          systemImage: conversation.isFavorite ? "star.slash" : "star.fill"
        )
      }
      .disabled(!isConnected || isTogglingFavorite)
    }
  }
}

extension View {
  func conversationContextMenu(
    conversation: Conversation,
    viewModel: ChatViewModel,
    onDelete: @escaping () -> Void
  ) -> some View {
    modifier(ConversationContextMenuModifier(
      conversation: conversation,
      viewModel: viewModel,
      onDelete: onDelete
    ))
  }
}
