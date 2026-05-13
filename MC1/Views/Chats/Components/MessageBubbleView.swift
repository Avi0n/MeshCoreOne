import SwiftUI
import MC1Services

/// Renders a UnifiedMessageBubble for a stored MessageItem.
/// Reads the message DTO from the view model and wires callbacks.
struct MessageBubbleView: View {
    let item: MessageItem
    let contactName: String
    let deviceName: String
    let configuration: MessageBubbleConfiguration
    @Bindable var viewModel: ChatViewModel
    let recentEmojisStore: RecentEmojisStore
    @Binding var selectedMessageForActions: MessageDTO?
    @Binding var imageViewerData: ImageViewerData?
    let onRetryMessage: (MessageDTO) -> Void

    var body: some View {
        if let message = viewModel.message(for: item) {
            UnifiedMessageBubble(
                message: message,
                contactName: contactName,
                deviceName: deviceName,
                configuration: configuration,
                item: item,
                imageResolver: { ref in
                    switch ref.role {
                    case .inline:
                        return viewModel.decodedImage(for: ref.cacheKey)
                    case .linkPreviewImage:
                        return viewModel.decodedPreviewImage(for: ref.cacheKey)
                    case .linkPreviewIcon:
                        return viewModel.decodedPreviewIcon(for: ref.cacheKey)
                    }
                },
                callbacks: MessageBubbleCallbacks(
                    onRetry: { onRetryMessage(message) },
                    onReaction: { emoji in
                        recentEmojisStore.recordUsage(emoji)
                        Task { await viewModel.sendReaction(emoji: emoji, to: message) }
                    },
                    onLongPress: { selectedMessageForActions = message },
                    onImageTap: {
                        if let data = viewModel.imageData(for: message.id) {
                            imageViewerData = ImageViewerData(
                                imageData: data,
                                isGIF: viewModel.isGIFImage(for: message.id)
                            )
                        }
                    },
                    onRetryImageFetch: {
                        Task { await viewModel.retryImageFetch(for: message.id) }
                    },
                    onRequestPreviewFetch: {
                        if viewModel.shouldRequestImageFetch(for: message.id) {
                            viewModel.requestImageFetch(for: message.id)
                        } else {
                            viewModel.requestPreviewFetch(for: message.id)
                        }
                    },
                    onManualPreviewFetch: {
                        Task {
                            await viewModel.manualFetchPreview(for: message.id)
                        }
                    }
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
