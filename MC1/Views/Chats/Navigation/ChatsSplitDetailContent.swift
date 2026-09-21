import MC1Services
import SwiftUI

struct ChatsSplitDetailContent: View {
  @Environment(\.appState) private var appState

  let viewModel: ChatViewModel
  /// Passed in so this view sees the same route as the parent's `.id`. Reading
  /// it here would build the new conversation before the `.id` changes.
  let route: ChatRoute?

  var body: some View {
    switch route {
    case let .direct(contact):
      ChatConversationView(
        conversationType: .dm(contact),
        parentViewModel: viewModel,
        coordinatorRegistry: appState.ensureChatCoordinatorRegistry()
      )
      .id(contact.id)
    case let .channel(channel):
      ChatConversationView(
        conversationType: .channel(channel),
        parentViewModel: viewModel,
        coordinatorRegistry: appState.ensureChatCoordinatorRegistry()
      )
      .id(channel.id)
    case let .room(session):
      RoomConversationView(session: session)
        .id(session.id)
    case .none:
      ContentUnavailableView(L10n.Chats.Chats.EmptyState.selectConversation, systemImage: "message")
    }
  }
}
