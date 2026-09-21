import SwiftUI

/// Shared menu and swipe buttons for a conversation row. `edge` picks the full set or one swipe side.
struct ConversationRowActions: View {
  enum Edge {
    case all
    case leading
    case trailing
  }

  @Environment(\.appState) private var appState

  let conversation: Conversation
  let viewModel: ChatViewModel
  let onDelete: () -> Void
  let edge: Edge

  private var isConnected: Bool {
    appState.connectionState == .ready
  }

  private var isTogglingFavorite: Bool {
    guard case let .direct(contact) = conversation else { return false }
    return viewModel.togglingFavoriteID == contact.id
  }

  var body: some View {
    if edge != .leading {
      deleteButton
      muteButton
    }
    if edge != .trailing {
      favoriteButton
    }
  }

  private var deleteButton: some View {
    Button(role: .destructive) {
      onDelete()
    } label: {
      Label(L10n.Chats.Chats.Action.delete, systemImage: "trash")
    }
    .disabled(!isConnected || viewModel.isDeletePending(conversation.id))
  }

  private var muteButton: some View {
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
  }

  private var favoriteButton: some View {
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

extension View {
  func conversationContextMenu(
    conversation: Conversation,
    viewModel: ChatViewModel,
    onDelete: @escaping () -> Void
  ) -> some View {
    contextMenu {
      ConversationRowActions(
        conversation: conversation,
        viewModel: viewModel,
        onDelete: onDelete,
        edge: .all
      )
    }
  }

  func conversationSwipeActions(
    conversation: Conversation,
    viewModel: ChatViewModel,
    onDelete: @escaping () -> Void
  ) -> some View {
    swipeActions(edge: .leading, allowsFullSwipe: true) {
      ConversationRowActions(
        conversation: conversation,
        viewModel: viewModel,
        onDelete: onDelete,
        edge: .leading
      )
    }
    // Full-swipe off so Delete cannot fire without a tap.
    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
      ConversationRowActions(
        conversation: conversation,
        viewModel: viewModel,
        onDelete: onDelete,
        edge: .trailing
      )
    }
  }
}
