import SwiftUI
import MC1Services

/// Constructs a UnifiedMessageBubble for a given display item, resolving message data from the view model
struct MessageBubbleView: View {
    let item: MessageDisplayItem
    let contactName: String
    let deviceName: String
    let configuration: MessageBubbleConfiguration
    @Bindable var viewModel: ChatViewModel
    let recentEmojisStore: RecentEmojisStore
    let showInlineImages: Bool
    let autoPlayGIFs: Bool
    let showIncomingPath: Bool
    let showIncomingHopCount: Bool
    let showIncomingRegion: Bool
    @Binding var selectedMessageForActions: MessageDTO?
    @Binding var imageViewerData: ImageViewerData?
    let onRetryMessage: (MessageDTO) -> Void

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    var body: some View {
        if let message = viewModel.message(for: item) {
            UnifiedMessageBubble(
                message: message,
                contactName: contactName,
                deviceName: deviceName,
                configuration: configuration,
                displayState: MessageDisplayState(
                    showTimestamp: item.showTimestamp,
                    showDirectionGap: item.showDirectionGap,
                    showSenderName: item.showSenderName,
                    showNewMessagesDivider: item.showNewMessagesDivider,
                    detectedURL: item.detectedURL,
                    previewState: item.previewState,
                    loadedPreview: item.loadedPreview,
                    isImageURL: item.isImageURL,
                    decodedImage: viewModel.decodedImage(for: message.id),
                    decodedPreviewImage: viewModel.decodedPreviewImage(for: message.id),
                    decodedPreviewIcon: viewModel.decodedPreviewIcon(for: message.id),
                    isGIF: viewModel.isGIFImage(for: message.id),
                    showInlineImages: showInlineImages,
                    autoPlayGIFs: autoPlayGIFs,
                    showIncomingHopCount: showIncomingHopCount,
                    showIncomingRegion: showIncomingRegion,
                    formattedPath: (showIncomingPath && !message.isOutgoing)
                        ? MessagePathFormatter.format(message)
                        : nil,
                    formattedText: viewModel.formattedText(
                        for: message.id,
                        text: message.text,
                        isOutgoing: message.isOutgoing,
                        currentUserName: deviceName,
                        isHighContrast: colorSchemeContrast == .increased
                    )
                ),
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
                        if item.isImageURL && showInlineImages {
                            viewModel.requestImageFetch(for: message.id, showInlineImages: showInlineImages)
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
