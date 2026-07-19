import MC1Services
import SwiftUI

/// Long-press context-menu actions for a node row: send message (chat contacts only), delete,
/// block/unblock, favorite/unfavorite, matching the conversation list. Delete is gated while a
/// removal is in flight so a rapid re-press can't double-fire.
struct ContactContextMenuModifier: ViewModifier {
  @Environment(\.appState) private var appState

  let contact: ContactDTO
  let viewModel: ContactsViewModel

  private var isConnected: Bool {
    appState.connectionState == .ready
  }

  func body(content: Content) -> some View {
    content.contextMenu {
      if contact.type == .chat, !contact.isBlocked {
        Button {
          appState.navigation.navigateToChat(with: contact)
        } label: {
          Label(L10n.Contacts.Contacts.Detail.sendMessage, systemImage: "message.fill")
        }
        .disabled(!isConnected)
      }

      Button(role: .destructive) {
        Task {
          await viewModel.deleteContact(contact)
        }
      } label: {
        Label(L10n.Contacts.Contacts.Common.delete, systemImage: "trash")
      }
      .disabled(!isConnected || viewModel.isDeletePending(contact.id))

      if contact.type == .chat {
        Button {
          Task {
            await viewModel.toggleBlocked(contact: contact)
          }
        } label: {
          Label(
            contact.isBlocked ? L10n.Contacts.Contacts.Action.unblock : L10n.Contacts.Contacts.Action.block,
            systemImage: contact.isBlocked ? "hand.raised.slash" : "hand.raised"
          )
        }
        .disabled(!isConnected)
      }

      Button {
        Task {
          await viewModel.toggleFavorite(contact: contact)
        }
      } label: {
        Label(
          contact.isFavorite ? L10n.Contacts.Contacts.Action.unfavorite : L10n.Contacts.Contacts.Row.favorite,
          systemImage: contact.isFavorite ? "star.slash" : "star.fill"
        )
      }
      .disabled(!isConnected || viewModel.togglingFavoriteID == contact.id)
    }
  }
}

extension View {
  func contactContextMenu(contact: ContactDTO, viewModel: ContactsViewModel) -> some View {
    modifier(ContactContextMenuModifier(contact: contact, viewModel: viewModel))
  }
}
