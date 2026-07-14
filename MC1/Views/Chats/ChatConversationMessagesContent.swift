import MC1Services
import OSLog
import SwiftUI

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
  @Binding var scrollToTargetRequest: Int
  @Binding var scrollToTargetID: UUID?

  /// Baked "New Messages" divider id the chat opens scrolled to; nil opens at the bottom.
  let openAtDividerItemID: UUID?

  // MARK: - Sheet State Bindings

  @Binding var selectedMessageForActions: MessageDTO?
  @Binding var imageViewerData: ImageViewerData?

  // MARK: - Callbacks

  let onRetryMessage: (MessageDTO) -> Void

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
          scrollToTargetRequest: $scrollToTargetRequest,
          scrollToTargetID: $scrollToTargetID,
          selectedMessageForActions: $selectedMessageForActions,
          imageViewerData: $imageViewerData,
          openAtDividerItemID: openAtDividerItemID,
          onRetryMessage: onRetryMessage
        )
      }
    }
  }

  // MARK: - Empty State

  @ViewBuilder
  private var emptyState: some View {
    switch conversationType {
    case let .dm(contact):
      DMEmptyMessagesView(contact: contact)
    case let .channel(channel):
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
      scrollToTargetRequest: .constant(0),
      scrollToTargetID: .constant(nil),
      openAtDividerItemID: nil,
      selectedMessageForActions: .constant(nil),
      imageViewerData: .constant(nil),
      onRetryMessage: { _ in }
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
      scrollToTargetRequest: .constant(0),
      scrollToTargetID: .constant(nil),
      openAtDividerItemID: nil,
      selectedMessageForActions: .constant(nil),
      imageViewerData: .constant(nil),
      onRetryMessage: { _ in }
    )
  }
  .environment(\.appState, AppState())
}
