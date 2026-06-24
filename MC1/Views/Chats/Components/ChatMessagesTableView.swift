import SwiftUI
import MC1Services

/// Messages table with ChatTableView, overlay scroll buttons, and divider state management
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
    @Binding var scrollToMentionRequest: Int
    @Binding var scrollToDividerRequest: Int
    @Binding var isDividerVisible: Bool
    @Binding var selectedMessageForActions: MessageDTO?
    @Binding var imageViewerData: ImageViewerData?

    let unseenMentionIDs: [UUID]
    let scrollToTargetID: UUID?
    let newMessagesDividerMessageID: UUID?
    let onMentionSeen: (UUID) async -> Bool
    let onScrollToMention: () -> Void
    let onRetryMessage: (MessageDTO) -> Void

    @State private var hasDismissedDividerButton = false
    @Environment(\.appTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.openURL) private var openURL

    private var showDividerButton: Bool {
        newMessagesDividerMessageID != nil && !isDividerVisible && !hasDismissedDividerButton
    }

    var body: some View {
        let mentionIDSet = Set(unseenMentionIDs)
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
                    Task { await viewModel.manualFetchPreview(for: messageID) }
                },
                onMapPreviewTap: { coordinate in
                    viewModel.navigateToMap(coordinate)
                },
                snapshotResolver: { MapSnapshotStore.shared.image(for: $0) },
                requestSnapshot: { MapSnapshotStore.shared.request($0) },
                retrySnapshot: { MapSnapshotStore.shared.retry($0) }
            )
        )
        ChatTableView(
            items: viewModel.items,
            cellContent: factory.makeContent(for:),
            contentBackground: theme.surfaces?.canvas,
            themeID: theme.id,
            appearanceToken: AppearanceToken.make(
                colorScheme: colorScheme,
                contrast: colorSchemeContrast,
                dynamicTypeSize: dynamicTypeSize
            ),
            isAtBottom: $isAtBottom,
            unreadCount: $unreadCount,
            scrollToBottomRequest: $scrollToBottomRequest,
            scrollToMentionRequest: $scrollToMentionRequest,
            isUnseenMention: { item in
                item.envelope.containsSelfMention
                    && !item.envelope.mentionSeen
                    && mentionIDSet.contains(item.id)
            },
            onMentionBecameVisible: { id in
                await onMentionSeen(id)
            },
            onSecondaryClick: { item in
                if let message = viewModel.message(for: item) {
                    selectedMessageForActions = message
                }
            },
            mentionTargetID: scrollToTargetID,
            scrollToDividerRequest: $scrollToDividerRequest,
            dividerItemID: newMessagesDividerMessageID,
            isDividerVisible: $isDividerVisible,
            onNearTop: { release in
                Task { @MainActor in
                    await viewModel.loadOlderMessages()
                    release()
                }
            },
            isLoadingOlderMessages: viewModel.isLoadingOlder
        )
        .overlay(alignment: .bottomTrailing) {
            VStack(spacing: 12) {
                if showDividerButton {
                    ScrollToDividerButton(
                        onTap: {
                            scrollToDividerRequest += 1
                            hasDismissedDividerButton = true
                        }
                    )
                    .transition(.scale.combined(with: .opacity))
                }

                if !unseenMentionIDs.isEmpty {
                    ScrollToMentionButton(
                        unreadMentionCount: unseenMentionIDs.count,
                        onTap: { onScrollToMention() }
                    )
                    .transition(.scale.combined(with: .opacity))
                }

                ScrollToBottomButton(
                    isVisible: !isAtBottom,
                    unreadCount: unreadCount,
                    onTap: { scrollToBottomRequest += 1 }
                )
            }
            .animation(.snappy(duration: 0.2), value: showDividerButton)
            .animation(.snappy(duration: 0.2), value: unseenMentionIDs.isEmpty)
            .padding(.trailing, 16)
            .padding(.bottom, 8)
        }
        .onChange(of: newMessagesDividerMessageID) { _, _ in
            hasDismissedDividerButton = false
        }
        .onChange(of: envInputs) { _, new in
            viewModel.applyEnvInputs(new)
        }
    }
}
