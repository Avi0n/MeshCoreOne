import MC1Services
import SwiftUI

/// Unified inner content view for both DM and Channel conversations.
/// Handles loading state, empty state, the messages list, bubble construction, and overlay buttons.
struct ChatConversationMessagesContent: View {
  // MARK: - Identity

  let conversationType: ChatConversationType
  @Bindable var viewModel: ChatViewModel
  let deviceName: String
  let recentEmojisStore: RecentEmojisStore

  // MARK: - Display Preferences

  let envInputs: EnvInputs

  // MARK: - Scroll State

  @Binding var isAtBottom: Bool
  @Binding var unreadCount: Int
  let scrollToBottomRequest: Int
  let scrollToTargetRequest: Int
  let scrollToTargetID: UUID?

  /// Baked "New Messages" divider id the chat opens scrolled to; nil opens at the bottom.
  let openAtDividerItemID: UUID?

  /// Fired once the tiled view has consumed `openAtDividerItemID`, so the owner
  /// can retire it and a later `.id` rebuild won't re-jump to the divider.
  let onDividerTargetConsumed: () -> Void

  // MARK: - Sheet State Bindings

  @Binding var selectedMessageForActions: MessageDTO?
  @Binding var imageViewerData: ImageViewerData?

  // MARK: - Callbacks

  let onRetryMessage: (MessageDTO) -> Void

  @Environment(\.appTheme) private var theme
  @Environment(\.openURL) private var openURL

  // MARK: - Body

  var body: some View {
    Group {
      if viewModel.renderState.phase == .loaded, viewModel.messages.isEmpty {
        emptyState
      } else if viewModel.messages.isEmpty {
        Color.clear
      } else {
        messagesList
      }
    }
  }

  // MARK: - Messages List

  /// The messages list bound to the current view model. When the conversation has an unread
  /// backlog it opens scrolled to the baked "New Messages" divider.
  private var messagesList: some View {
    ChatTiledView(
      items: viewModel.items,
      cellContent: cellFactory.makeContent(for:),
      contentBackground: theme.surfaces?.canvas,
      isAtBottom: $isAtBottom,
      unreadCount: $unreadCount,
      scrollToBottomRequest: scrollToBottomRequest,
      scrollToTargetRequest: scrollToTargetRequest,
      scrollTargetID: scrollToTargetID,
      initialScrollTargetID: openAtDividerItemID,
      onLoadOlder: { await viewModel.loadOlderMessages() },
      onInitialTargetConsumed: onDividerTargetConsumed
    )
    .onChange(of: envInputs) { _, new in
      viewModel.applyEnvInputs(new)
    }
  }

  private var cellFactory: ChatCellContentFactory {
    ChatCellContentFactory(
      contactName: conversationType.navigationTitle,
      deviceName: deviceName,
      configuration: bubbleConfiguration,
      theme: theme,
      openURL: openURL,
      resolver: BubbleResolver(viewModel: viewModel),
      actions: BubbleActions(
        onRetryMessage: onRetryMessage,
        onReaction: { emoji, message in
          recentEmojisStore.recordUsage(emoji)
          Task { await viewModel.sendReaction(emoji: emoji, to: message) }
        },
        onLongPress: { message in selectedMessageForActions = message },
        onImageTap: { message in
          if let data = viewModel.imageData(for: message.id) {
            imageViewerData = ImageViewerData(
              imageData: data,
              isGIF: viewModel.isGIFImage(for: message.id)
            )
          }
        },
        onRetryInlineImage: { messageID in
          Task { await viewModel.retryImageFetch(for: messageID) }
        },
        onRequestPreviewFetch: { messageID in
          if viewModel.shouldRequestImageFetch(for: messageID) {
            viewModel.requestImageFetch(for: messageID)
          } else {
            viewModel.requestPreviewFetch(for: messageID)
          }
        },
        onManualPreviewFetch: { messageID in
          if viewModel.shouldRequestImageFetch(for: messageID) {
            viewModel.manualFetchImage(for: messageID)
          } else {
            Task { await viewModel.manualFetchPreview(for: messageID) }
          }
        },
        onMapPreviewTap: { coordinate in
          viewModel.navigateToMap(coordinate)
        },
        snapshotResolver: { MapSnapshotStore.shared.image(for: $0) },
        requestSnapshot: { MapSnapshotStore.shared.request($0) },
        retrySnapshot: { MapSnapshotStore.shared.retry($0) }
      )
    )
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
      scrollToBottomRequest: 0,
      scrollToTargetRequest: 0,
      scrollToTargetID: nil,
      openAtDividerItemID: nil,
      onDividerTargetConsumed: {},
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
      scrollToBottomRequest: 0,
      scrollToTargetRequest: 0,
      scrollToTargetID: nil,
      openAtDividerItemID: nil,
      onDividerTargetConsumed: {},
      selectedMessageForActions: .constant(nil),
      imageViewerData: .constant(nil),
      onRetryMessage: { _ in }
    )
  }
  .environment(\.appState, AppState())
}
