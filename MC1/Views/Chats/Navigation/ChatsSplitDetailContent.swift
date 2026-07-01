import MC1Services
import SwiftUI

struct ChatsSplitDetailContent: View {
  @Environment(\.appState) private var appState

  let viewModel: ChatViewModel

  var body: some View {
    switch appState.navigation.chatsSelectedRoute {
    case let .direct(contact):
      ChatConversationView(conversationType: .dm(contact), parentViewModel: viewModel)
        .id(contact.id)
    case let .channel(channel):
      ChatConversationView(conversationType: .channel(channel), parentViewModel: viewModel)
        .id(channel.id)
    case let .room(session):
      RoomConversationView(session: session)
        .id(session.id)
    case .none:
      ContentUnavailableView(L10n.Chats.Chats.EmptyState.selectConversation, systemImage: "message")
    }
  }
}
