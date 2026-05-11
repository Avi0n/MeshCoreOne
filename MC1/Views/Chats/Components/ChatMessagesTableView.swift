import SwiftUI
import MC1Services

/// Messages table with ChatTableView, overlay FABs, and divider state management
struct ChatMessagesTableView: View {
    @Bindable var viewModel: ChatViewModel
    let contactName: String
    let deviceName: String
    let configuration: MessageBubbleConfiguration
    let recentEmojisStore: RecentEmojisStore
    let showInlineImages: Bool
    let autoPlayGIFs: Bool
    let showIncomingPath: Bool
    let showIncomingHopCount: Bool
    let showIncomingRegion: Bool

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
    let onMentionSeen: (UUID) async -> Void
    let onScrollToMention: () -> Void
    let onRetryMessage: (MessageDTO) -> Void

    @State private var hasDismissedDividerFAB = false

    private var showDividerFAB: Bool {
        newMessagesDividerMessageID != nil && !isDividerVisible && !hasDismissedDividerFAB
    }

    var body: some View {
        let mentionIDSet = Set(unseenMentionIDs)
        ChatTableView(
            items: viewModel.displayItems,
            cellContent: { item in
                MessageBubbleView(
                    item: item,
                    contactName: contactName,
                    deviceName: deviceName,
                    configuration: configuration,
                    viewModel: viewModel,
                    recentEmojisStore: recentEmojisStore,
                    showInlineImages: showInlineImages,
                    autoPlayGIFs: autoPlayGIFs,
                    showIncomingPath: showIncomingPath,
                    showIncomingHopCount: showIncomingHopCount,
                    showIncomingRegion: showIncomingRegion,
                    selectedMessageForActions: $selectedMessageForActions,
                    imageViewerData: $imageViewerData,
                    onRetryMessage: onRetryMessage
                )
            },
            isAtBottom: $isAtBottom,
            unreadCount: $unreadCount,
            scrollToBottomRequest: $scrollToBottomRequest,
            scrollToMentionRequest: $scrollToMentionRequest,
            isUnseenMention: { item in
                item.containsSelfMention && !item.mentionSeen && mentionIDSet.contains(item.id)
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
    }
}
