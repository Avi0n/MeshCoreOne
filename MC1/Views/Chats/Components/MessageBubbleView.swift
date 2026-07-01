import MC1Services
import SwiftUI

/// Renders a `UnifiedMessageBubble` for a stored `MessageItem`. Resolves
/// the message DTO and per-message assets through `BubbleResolver` and
/// dispatches user interactions through `BubbleActions`. The view does
/// not hold a reference to `ChatViewModel`, which lets it adopt
/// `Equatable` on `MessageItem` alone so SwiftUI can skip rebodies when
/// the row identity and content are unchanged.
struct MessageBubbleView: View, Equatable {
  let item: MessageItem
  let contactName: String
  let deviceName: String
  let configuration: MessageBubbleConfiguration
  let resolver: BubbleResolver
  let actions: BubbleActions

  nonisolated static func == (lhs: MessageBubbleView, rhs: MessageBubbleView) -> Bool {
    lhs.item == rhs.item
  }

  var body: some View {
    if let message = resolver.message(item) {
      UnifiedMessageBubble(
        message: message,
        contactName: contactName,
        deviceName: deviceName,
        configuration: configuration,
        item: item,
        layout: FragmentLayout(content: item.content),
        imageResolver: { ref in resolver.image(ref) },
        callbacks: MessageBubbleCallbacks(
          onRetry: { actions.onRetryMessage(message) },
          onReaction: { emoji in actions.onReaction(emoji, message) },
          onLongPress: { actions.onLongPress(message) },
          onImageTap: { actions.onImageTap(message) },
          onRetryInlineImage: { actions.onRetryInlineImage(message.id) },
          onRequestPreviewFetch: { actions.onRequestPreviewFetch(message.id) },
          onManualPreviewFetch: { actions.onManualPreviewFetch(message.id) },
          onMapPreviewTap: { coordinate in actions.onMapPreviewTap(coordinate) },
          snapshotResolver: actions.snapshotResolver,
          requestSnapshot: actions.requestSnapshot,
          retrySnapshot: actions.retrySnapshot
        )
      )
    } else {
      Text(L10n.Chats.Chats.Message.unavailable)
        .font(.caption)
        .foregroundStyle(.secondary)
        .accessibilityLabel(L10n.Chats.Chats.Message.unavailableAccessibility)
    }
  }
}
