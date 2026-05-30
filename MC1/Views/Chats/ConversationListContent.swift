import SwiftUI
import MC1Services

struct ConversationListContent: View {
    enum ListMode {
        case selection(Binding<ChatRoute?>)
        case navigation(onNavigate: (ChatRoute) -> Void, onRequestRoomAuth: (RemoteNodeSessionDTO) -> Void)
    }

    @Environment(\.appTheme) private var theme

    private let viewModel: ChatViewModel
    private let favoriteConversations: [Conversation]
    private let otherConversations: [Conversation]
    private let mode: ListMode
    private let hasLoadedOnce: Bool
    private let emptyStateMessage: (title: String, description: String, systemImage: String)
    private let onDeleteConversation: (Conversation) -> Void
    @Binding private var selectedFilter: ChatFilter

    init(
        viewModel: ChatViewModel,
        favoriteConversations: [Conversation],
        otherConversations: [Conversation],
        selectedFilter: Binding<ChatFilter>,
        hasLoadedOnce: Bool,
        emptyStateMessage: (title: String, description: String, systemImage: String),
        selection: Binding<ChatRoute?>,
        onDeleteConversation: @escaping (Conversation) -> Void
    ) {
        self.viewModel = viewModel
        self.favoriteConversations = favoriteConversations
        self.otherConversations = otherConversations
        self._selectedFilter = selectedFilter
        self.hasLoadedOnce = hasLoadedOnce
        self.emptyStateMessage = emptyStateMessage
        self.mode = .selection(selection)
        self.onDeleteConversation = onDeleteConversation
    }

    init(
        viewModel: ChatViewModel,
        favoriteConversations: [Conversation],
        otherConversations: [Conversation],
        selectedFilter: Binding<ChatFilter>,
        hasLoadedOnce: Bool,
        emptyStateMessage: (title: String, description: String, systemImage: String),
        onNavigate: @escaping (ChatRoute) -> Void,
        onRequestRoomAuth: @escaping (RemoteNodeSessionDTO) -> Void,
        onDeleteConversation: @escaping (Conversation) -> Void
    ) {
        self.viewModel = viewModel
        self.favoriteConversations = favoriteConversations
        self.otherConversations = otherConversations
        self._selectedFilter = selectedFilter
        self.hasLoadedOnce = hasLoadedOnce
        self.emptyStateMessage = emptyStateMessage
        self.mode = .navigation(onNavigate: onNavigate, onRequestRoomAuth: onRequestRoomAuth)
        self.onDeleteConversation = onDeleteConversation
    }

    var body: some View {
        Group {
            if !hasLoadedOnce {
                List {
                    filterSection
                }
                .listStyle(.plain)
                .overlay {
                    ProgressView()
                }
            } else {
                TimelineView(.everyMinute) { context in
                    listContent(referenceDate: context.date)
                }
            }
        }
    }

    private var filterSection: some View {
        PinnedFilterHeader { ChatFilterPicker(selection: $selectedFilter) }
    }

    @ViewBuilder
    private var emptyStateRow: some View {
        Section {
            ContentUnavailableView {
                Label(emptyStateMessage.title, systemImage: emptyStateMessage.systemImage)
            } description: {
                Text(emptyStateMessage.description)
            } actions: {
                if selectedFilter != .all {
                    Button(L10n.Chats.Chats.Filter.clear) {
                        selectedFilter = .all
                    }
                }
            }
        }
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
    }

    private var hasNoConversations: Bool {
        favoriteConversations.isEmpty && otherConversations.isEmpty
    }

    @ViewBuilder
    private func listContent(referenceDate: Date) -> some View {
        switch mode {
        case .selection(let selection):
            List(selection: selection) {
                filterSection

                if hasNoConversations {
                    emptyStateRow
                } else {
                    Section {
                        ForEach(favoriteConversations) { conversation in
                            ConversationSelectionRow(
                                conversation: conversation,
                                viewModel: viewModel,
                                referenceDate: referenceDate,
                                onDelete: { onDeleteConversation(conversation) }
                            )
                        }
                    }
                    .accessibilityLabel(L10n.Chats.Chats.Section.favorites)
                    .accessibilityHidden(favoriteConversations.isEmpty)
                    .themedPlainRowBackground(theme)

                    Section {
                        ForEach(otherConversations) { conversation in
                            ConversationSelectionRow(
                                conversation: conversation,
                                viewModel: viewModel,
                                referenceDate: referenceDate,
                                onDelete: { onDeleteConversation(conversation) }
                            )
                        }
                    }
                    .accessibilityLabel(L10n.Chats.Chats.Section.conversations)
                    .accessibilityHidden(otherConversations.isEmpty)
                    .themedPlainRowBackground(theme)
                }
            }
            .listStyle(.plain)

        case .navigation(let onNavigate, let onRequestRoomAuth):
            List {
                filterSection

                if hasNoConversations {
                    emptyStateRow
                } else {
                    Section {
                        ForEach(favoriteConversations) { conversation in
                            ConversationNavigationRow(
                                conversation: conversation,
                                viewModel: viewModel,
                                referenceDate: referenceDate,
                                onNavigate: onNavigate,
                                onRequestRoomAuth: onRequestRoomAuth,
                                onDelete: { onDeleteConversation(conversation) }
                            )
                        }
                    }
                    .accessibilityLabel(L10n.Chats.Chats.Section.favorites)
                    .accessibilityHidden(favoriteConversations.isEmpty)
                    .themedPlainRowBackground(theme)

                    Section {
                        ForEach(otherConversations) { conversation in
                            ConversationNavigationRow(
                                conversation: conversation,
                                viewModel: viewModel,
                                referenceDate: referenceDate,
                                onNavigate: onNavigate,
                                onRequestRoomAuth: onRequestRoomAuth,
                                onDelete: { onDeleteConversation(conversation) }
                            )
                        }
                    }
                    .accessibilityLabel(L10n.Chats.Chats.Section.conversations)
                    .accessibilityHidden(otherConversations.isEmpty)
                    .themedPlainRowBackground(theme)
                }
            }
            .listStyle(.plain)
        }
    }
}

// MARK: - Extracted Views

private struct ConversationSelectionRow: View {
    let conversation: Conversation
    let viewModel: ChatViewModel
    let referenceDate: Date
    let onDelete: () -> Void

    var body: some View {
        let route = ChatRoute(conversation: conversation)
        switch conversation {
        case .direct(let contact):
            ConversationRow(contact: contact, viewModel: viewModel, referenceDate: referenceDate)
                .tag(route)
                .conversationSwipeActions(conversation: conversation, viewModel: viewModel, onDelete: onDelete)

        case .channel(let channel):
            ChannelConversationRow(channel: channel, viewModel: viewModel, referenceDate: referenceDate)
                .tag(route)
                .conversationSwipeActions(conversation: conversation, viewModel: viewModel, onDelete: onDelete)

        case .room(let session):
            RoomConversationRow(session: session, referenceDate: referenceDate)
                .tag(route)
                .conversationSwipeActions(conversation: conversation, viewModel: viewModel, onDelete: onDelete)
        }
    }
}

private struct ConversationNavigationRow: View {
    let conversation: Conversation
    let viewModel: ChatViewModel
    let referenceDate: Date
    let onNavigate: (ChatRoute) -> Void
    let onRequestRoomAuth: (RemoteNodeSessionDTO) -> Void
    let onDelete: () -> Void

    var body: some View {
        let route = ChatRoute(conversation: conversation)
        switch conversation {
        case .direct(let contact):
            NavigationLink(value: route) {
                ConversationRow(contact: contact, viewModel: viewModel, referenceDate: referenceDate)
            }
            .conversationSwipeActions(conversation: conversation, viewModel: viewModel, onDelete: onDelete)

        case .channel(let channel):
            NavigationLink(value: route) {
                ChannelConversationRow(channel: channel, viewModel: viewModel, referenceDate: referenceDate)
            }
            .conversationSwipeActions(conversation: conversation, viewModel: viewModel, onDelete: onDelete)

        case .room(let session):
            Button {
                if session.isConnected {
                    onNavigate(route)
                } else {
                    onRequestRoomAuth(session)
                }
            } label: {
                RoomConversationRow(session: session, referenceDate: referenceDate)
            }
            .buttonStyle(.plain)
            .conversationSwipeActions(conversation: conversation, viewModel: viewModel, onDelete: onDelete)
        }
    }
}
