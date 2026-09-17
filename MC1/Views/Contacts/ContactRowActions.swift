import MC1Services
import SwiftUI

/// Shared menu and swipe buttons for a node row. `edge` picks the full set or one swipe side; send message is menu-only.
struct ContactRowActions: View {
  enum Edge {
    case all
    case leading
    case trailing
  }

  @Environment(\.appState) private var appState

  let contact: ContactDTO
  let viewModel: ContactsViewModel
  let edge: Edge

  private var isConnected: Bool {
    appState.connectionState == .ready
  }

  /// ZephCore V-contact remove is disabled (would turn off firmware admin CLI).
  private var isVContact: Bool {
    guard let selfKey = appState.connectedDevice?.publicKey else { return false }
    return VContactIdentity.isVContact(publicKey: contact.publicKey, selfPublicKey: selfKey)
  }

  var body: some View {
    if edge == .all, contact.type == .chat, !contact.isBlocked {
      sendMessageButton
    }

    if edge != .leading {
      if !isVContact {
        deleteButton
      }
      if contact.type == .chat {
        blockButton
      }
    }

    if edge != .trailing {
      favoriteButton
    }
  }

  private var sendMessageButton: some View {
    Button {
      appState.navigation.navigateToChat(with: contact)
    } label: {
      Label(L10n.Contacts.Contacts.Detail.sendMessage, systemImage: "message.fill")
    }
    .disabled(!isConnected)
  }

  private var deleteButton: some View {
    Button(role: .destructive) {
      Task {
        await viewModel.deleteContact(contact)
      }
    } label: {
      Label(L10n.Contacts.Contacts.Common.delete, systemImage: "trash")
    }
    .disabled(!isConnected || viewModel.isDeletePending(contact.id))
  }

  private var blockButton: some View {
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

  private var favoriteButton: some View {
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

extension View {
  func contactContextMenu(contact: ContactDTO, viewModel: ContactsViewModel) -> some View {
    contextMenu {
      ContactRowActions(contact: contact, viewModel: viewModel, edge: .all)
    }
  }

  func contactSwipeActions(contact: ContactDTO, viewModel: ContactsViewModel) -> some View {
    swipeActions(edge: .leading, allowsFullSwipe: true) {
      ContactRowActions(contact: contact, viewModel: viewModel, edge: .leading)
    }
    // Full-swipe off so Delete cannot fire without a tap.
    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
      ContactRowActions(contact: contact, viewModel: viewModel, edge: .trailing)
    }
  }
}
