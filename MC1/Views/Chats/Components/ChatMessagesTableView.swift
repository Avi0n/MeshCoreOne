import MC1Services
import SwiftUI

/// Messages list with `ChatTiledView` and the scroll-to-bottom overlay button.
/// When the conversation has an unread backlog it opens scrolled to the baked
/// "New Messages" divider.
struct ChatMessagesTableView: View {
  @Bindable var viewModel: ChatViewModel
  let contactName: String
  let deviceName: String
  let configuration: MessageBubbleConfiguration
  let recentEmojisStore: RecentEmojisStore
  let envInputs: EnvInputs

  @Binding var isAtBottom: Bool
  @Binding var unreadCount: Int
  @Binding var scrollToBottomRequest: Int
  @Binding var scrollToTargetRequest: Int
  @Binding var scrollToTargetID: UUID?
  @Binding var selectedMessageForActions: MessageDTO?
  @Binding var imageViewerData: ImageViewerData?

  /// Baked divider id the list opens scrolled to; nil opens at the bottom.
  let openAtDividerItemID: UUID?
  let onRetryMessage: (MessageDTO) -> Void

  @Environment(\.appTheme) private var theme
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize
  @Environment(\.openURL) private var openURL

  var body: some View {
    let factory = ChatCellContentFactory(
      contactName: contactName,
      deviceName: deviceName,
      configuration: configuration,
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

    ChatTiledView(
      items: viewModel.items,
      cellContent: factory.makeContent(for:),
      contentBackground: theme.surfaces?.canvas,
      appearanceIdentity: appearanceIdentity,
      isAtBottom: $isAtBottom,
      unreadCount: $unreadCount,
      scrollToBottomRequest: scrollToBottomRequest,
      scrollToTargetRequest: scrollToTargetRequest,
      scrollTargetID: scrollToTargetID,
      initialScrollTargetID: openAtDividerItemID,
      onLoadOlder: { await viewModel.loadOlderMessages() }
    )
    .overlay(alignment: .bottomTrailing) {
      ScrollToBottomButton(
        isVisible: !isAtBottom,
        unreadCount: unreadCount,
        onTap: { scrollToBottomRequest += 1 }
      )
      .padding(.trailing, 16)
      .padding(.bottom, 8)
    }
    .onChange(of: envInputs) { _, new in
      viewModel.applyEnvInputs(new)
    }
  }

  /// Theme + appearance fingerprint. A change rebuilds the list so bubble fills
  /// that read `\.appTheme` (not baked into `MessageItem`) repaint.
  private var appearanceIdentity: String {
    let appearance = AppearanceToken.make(
      colorScheme: colorScheme,
      contrast: colorSchemeContrast,
      dynamicTypeSize: dynamicTypeSize
    )
    return "\(theme.id)|\(appearance)"
  }
}
