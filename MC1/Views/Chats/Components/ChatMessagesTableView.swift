import SwiftUI
import MC1Services

/// Messages table with ChatTableView, overlay FABs, and divider state management
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
    @Binding var imageViewerData: ImageViewerData?

    let unseenMentionIDs: [UUID]
    let scrollToTargetID: UUID?
    let newMessagesDividerMessageID: UUID?
    let onMentionSeen: (UUID) async -> Void
    let onScrollToMention: () -> Void
    let onRetryMessage: (MessageDTO) -> Void
    let makeActionsMenu: (MessageDTO) -> AnyView

    @State private var hasDismissedDividerFAB = false
    @Environment(\.appTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var showDividerFAB: Bool {
        newMessagesDividerMessageID != nil && !isDividerVisible && !hasDismissedDividerFAB
    }

    var body: some View {
        let mentionIDSet = Set(unseenMentionIDs)
        let factory = ChatCellContentFactory(
            contactName: contactName,
            deviceName: deviceName,
            configuration: configuration,
            theme: theme,
            resolver: BubbleResolver(viewModel: viewModel),
            actions: BubbleActions(
                onRetryMessage: onRetryMessage,
                onReaction: { emoji, message in
                    recentEmojisStore.recordUsage(emoji)
                    Task { await viewModel.sendReaction(emoji: emoji, to: message) }
                },
                makeActionsMenu: makeActionsMenu,
                onImageTap: { message in
                    if let data = viewModel.imageData(for: message.id) {
                        imageViewerData = ImageViewerData(
                            imageData: data,
                            isGIF: viewModel.isGIFImage(for: message.id)
                        )
                    }
                },
                onRetryImageFetch: { messageID in
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
                }
            )
        )
        ChatTableView(
            items: viewModel.items,
            cellContent: factory.makeContent(for:),
            contentBackground: theme.surfaces?.canvas,
            themeID: theme.id,
            appearanceToken: AppearanceToken.make(colorScheme: colorScheme, contrast: colorSchemeContrast),
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
                Task {
                    await onMentionSeen(id)
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
                if showDividerFAB {
                    ScrollToDividerButton(
                        onTap: {
                            scrollToDividerRequest += 1
                            hasDismissedDividerFAB = true
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
            .animation(.snappy(duration: 0.2), value: showDividerFAB)
            .animation(.snappy(duration: 0.2), value: unseenMentionIDs.isEmpty)
            .padding(.trailing, 16)
            .padding(.bottom, 8)
        }
        .onChange(of: newMessagesDividerMessageID) { _, _ in
            hasDismissedDividerFAB = false
        }
        .onChange(of: envInputs) { _, new in
            viewModel.applyEnvInputs(new)
        }
    }
}
