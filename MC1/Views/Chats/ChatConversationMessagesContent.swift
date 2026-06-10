import SwiftUI
import MC1Services
import OSLog

private let logger = Logger(subsystem: "com.mc1", category: "ChatConversationMessagesContent")

/// Unified inner content view for both DM and Channel conversations.
/// Handles loading state, empty state, message table, bubble construction, and overlay buttons.
struct ChatConversationMessagesContent: View {
    // MARK: - Identity

    let conversationType: ChatConversationType
    @Bindable var viewModel: ChatViewModel
    let deviceName: String
    let recentEmojisStore: RecentEmojisStore

    // MARK: - Display Preferences

    let envInputs: EnvInputs

    // MARK: - Scroll State Bindings

    @Binding var isAtBottom: Bool
    @Binding var unreadCount: Int
    @Binding var scrollToBottomRequest: Int
    @Binding var scrollToMentionRequest: Int
    @Binding var scrollToDividerRequest: Int
    @Binding var isDividerVisible: Bool

    // MARK: - Mention State (read-only)

    let unseenMentionIDs: [UUID]
    let scrollToTargetID: UUID?
    let newMessagesDividerMessageID: UUID?

    // MARK: - Sheet State Bindings

    @Binding var imageViewerData: ImageViewerData?

    // MARK: - Callbacks

    let onMentionSeen: (UUID) async -> Void
    let onScrollToMention: () -> Void
    let onRetryMessage: (MessageDTO) -> Void
    let makeActionsMenu: (MessageDTO) -> AnyView

    // MARK: - Body

    var body: some View {
        Group {
            if viewModel.renderState.phase == .loaded, viewModel.messages.isEmpty {
                emptyState
            } else if viewModel.messages.isEmpty {
                Color.clear
            } else {
                ChatMessagesTableView(
                    viewModel: viewModel,
                    contactName: conversationType.navigationTitle,
                    deviceName: deviceName,
                    configuration: bubbleConfiguration,
                    recentEmojisStore: recentEmojisStore,
                    envInputs: envInputs,
                    isAtBottom: $isAtBottom,
                    unreadCount: $unreadCount,
                    scrollToBottomRequest: $scrollToBottomRequest,
                    scrollToMentionRequest: $scrollToMentionRequest,
                    scrollToDividerRequest: $scrollToDividerRequest,
                    isDividerVisible: $isDividerVisible,
                    imageViewerData: $imageViewerData,
                    unseenMentionIDs: unseenMentionIDs,
                    scrollToTargetID: scrollToTargetID,
                    newMessagesDividerMessageID: newMessagesDividerMessageID,
                    onMentionSeen: onMentionSeen,
                    onScrollToMention: onScrollToMention,
                    onRetryMessage: onRetryMessage,
                    makeActionsMenu: makeActionsMenu
                )
            }
        }
    }

    // MARK: - Empty State

    @ViewBuilder
    private var emptyState: some View {
        switch conversationType {
        case .dm(let contact):
            DMEmptyMessagesView(contact: contact)
        case .channel(let channel):
            ChannelEmptyMessagesView(
                channel: channel,
                displayName: conversationType.navigationTitle,
                isPublicStyle: conversationType.isPublicStyleChannel
            )
        }
    }

    // MARK: - Bubble Configuration

    private var bubbleConfiguration: MessageBubbleConfiguration {
        switch conversationType {
        case .dm:
            .directMessage
        case .channel:
            .channel(isPublic: conversationType.isPublicStyleChannel)
        }
    }
}

// MARK: - DM Empty Messages View

private struct DMEmptyMessagesView: View {
    let contact: ContactDTO

    var body: some View {
        VStack(spacing: 16) {
            ContactAvatar(contact: contact, size: 80)

            Text(contact.displayName)
                .font(.title2)
                .bold()

            Text(L10n.Chats.Chats.EmptyState.startConversation)
                .foregroundStyle(.secondary)

            if contact.hasLocation {
                Label(L10n.Chats.Chats.ContactInfo.hasLocation, systemImage: "location.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

// MARK: - Channel Empty Messages View

private struct ChannelEmptyMessagesView: View {
    let channel: ChannelDTO
    let displayName: String
    let isPublicStyle: Bool

    var body: some View {
        VStack(spacing: 16) {
            ChannelAvatar(channel: channel, size: 80)

            Text(displayName)
                .font(.title2)
                .bold()

            Text(L10n.Chats.Chats.Channel.EmptyState.noMessages)
                .foregroundStyle(.secondary)

            Text(isPublicStyle
                ? L10n.Chats.Chats.Channel.EmptyState.publicDescription
                : L10n.Chats.Chats.Channel.EmptyState.privateDescription)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

// MARK: - Previews

#Preview("DM Conversation") {
    NavigationStack {
        ChatConversationMessagesContent(
            conversationType: .dm(ContactDTO(from: Contact(
                radioID: UUID(),
                publicKey: Data(repeating: 0x42, count: 32),
                name: "Alice"
            ))),
            viewModel: ChatViewModel(),
            deviceName: "My Device",
            recentEmojisStore: RecentEmojisStore(),
            envInputs: .default,
            isAtBottom: .constant(true),
            unreadCount: .constant(0),
            scrollToBottomRequest: .constant(0),
            scrollToMentionRequest: .constant(0),
            scrollToDividerRequest: .constant(0),
            isDividerVisible: .constant(false),
            unseenMentionIDs: [],
            scrollToTargetID: nil,
            newMessagesDividerMessageID: nil,
            imageViewerData: .constant(nil),
            onMentionSeen: { _ in },
            onScrollToMention: {},
            onRetryMessage: { _ in },
            makeActionsMenu: { _ in AnyView(EmptyView()) }
        )
    }
    .environment(\.appState, AppState())
}

#Preview("Channel Conversation") {
    NavigationStack {
        ChatConversationMessagesContent(
            conversationType: .channel(ChannelDTO(from: Channel(
                radioID: UUID(),
                index: 1,
                name: "General"
            ))),
            viewModel: ChatViewModel(),
            deviceName: "My Device",
            recentEmojisStore: RecentEmojisStore(),
            envInputs: .default,
            isAtBottom: .constant(true),
            unreadCount: .constant(0),
            scrollToBottomRequest: .constant(0),
            scrollToMentionRequest: .constant(0),
            scrollToDividerRequest: .constant(0),
            isDividerVisible: .constant(false),
            unseenMentionIDs: [],
            scrollToTargetID: nil,
            newMessagesDividerMessageID: nil,
            imageViewerData: .constant(nil),
            onMentionSeen: { _ in },
            onScrollToMention: {},
            onRetryMessage: { _ in },
            makeActionsMenu: { _ in AnyView(EmptyView()) }
        )
    }
    .environment(\.appState, AppState())
}
