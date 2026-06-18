import SwiftUI
import MC1Services

struct ChatsSplitDetailContent: View {
    @Environment(\.appState) private var appState

    let viewModel: ChatViewModel

    var body: some View {
        switch appState.navigation.chatsSelectedRoute {
        case .direct(let contact):
            ChatConversationView(conversationType: .dm(contact), parentViewModel: viewModel)
                .id(contact.id)
        case .channel(let channel):
            ChatConversationView(conversationType: .channel(channel), parentViewModel: viewModel)
                .id(channel.id)
        case .room(let session):
            RoomConversationView(session: session)
                .id(session.id)
        case .none:
            ContentUnavailableView(L10n.Chats.Chats.EmptyState.selectConversation, systemImage: "message")
        }
    }
}
