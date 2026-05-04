import SwiftUI
import UIKit  // UIPasteboard for .copy action
import MC1Services
import OSLog

private let logger = Logger(subsystem: "com.mc1", category: "ChatConversationView")
private let messageActionSheetPresentationDelay: Duration = .milliseconds(300)

/// Unified chat conversation view supporting both DMs and Channels.
struct ChatConversationView: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.linkPreviewCache) private var linkPreviewCache

    @State private var conversationType: ChatConversationType
    let parentViewModel: ChatViewModel?

    @State private var chatViewModel = ChatViewModel()

    // MARK: - Scroll State

    @State private var isAtBottom = true
    @State private var unreadCount = 0
    @State private var scrollToBottomRequest = 0
    @State private var scrollToMentionRequest = 0
    @State private var unseenMentionIDs: [UUID] = []
    @State private var scrollToTargetID: UUID?
    @State private var mentionScrollTask: Task<Void, Never>?
    @State private var scrollToDividerRequest = 0
    @State private var isDividerVisible = false

    // MARK: - Sheet State

    @State private var showingInfo = false
    @State private var selectedMessageForActions: MessageDTO?
    @State private var blockSenderContext: BlockSenderContext?
    @State private var sendDMContext: SendDMContext?
    @State private var imageViewerData: ImageViewerData?

    // MARK: - Other State

    @State private var recentEmojisStore = RecentEmojisStore()
    @State private var mentionSenderOrder: [String: UInt32]?
    @State private var eventCursor: Int?
    @FocusState private var isInputFocused: Bool

    // MARK: - AppStorage

    @AppStorage("showInlineImages") private var showInlineImages = true
    @AppStorage("autoPlayGIFs") private var autoPlayGIFs = true
    @AppStorage("showIncomingPath") private var showIncomingPath = false
    @AppStorage("showIncomingHopCount") private var showIncomingHopCount = false
    @AppStorage("showIncomingRegion") private var showIncomingRegion = false
    @AppStorage("replyWithQuote") private var replyWithQuote = false

    // MARK: - Init

    init(conversationType: ChatConversationType, parentViewModel: ChatViewModel? = nil) {
        self._conversationType = State(initialValue: conversationType)
        self.parentViewModel = parentViewModel
    }

    // MARK: - Body

    var body: some View {
        ChatConversationMessagesContent(
            conversationType: conversationType,
            viewModel: chatViewModel,
            deviceName: appState.localNodeName,
            recentEmojisStore: recentEmojisStore,
            showInlineImages: showInlineImages,
            autoPlayGIFs: autoPlayGIFs,
            showIncomingPath: showIncomingPath,
            showIncomingHopCount: showIncomingHopCount,
            showIncomingRegion: showIncomingRegion,
            isAtBottom: $isAtBottom,
            unreadCount: $unreadCount,
            scrollToBottomRequest: $scrollToBottomRequest,
            scrollToMentionRequest: $scrollToMentionRequest,
            scrollToDividerRequest: $scrollToDividerRequest,
            isDividerVisible: $isDividerVisible,
            unseenMentionIDs: unseenMentionIDs,
            scrollToTargetID: scrollToTargetID,
            newMessagesDividerMessageID: chatViewModel.newMessagesDividerMessageID,
            selectedMessageForActions: $selectedMessageForActions,
            imageViewerData: $imageViewerData,
            onMentionSeen: { await markMentionSeen(messageID: $0) },
            onScrollToMention: { scrollToNextMention() },
            onRetryMessage: { retryMessage($0) }
        )
        .safeAreaInset(edge: .bottom, spacing: 8) {
            ChatConversationInputBar(
                conversationType: conversationType,
                composingText: $chatViewModel.composingText,
                isFocused: $isInputFocused,
                nodeNameByteCount: appState.connectedDevice?.nodeName.utf8.count ?? 0,
                onSend: { text in
                    switch conversationType {
                    case .dm:
                        await chatViewModel.sendMessage(text: text)
                    case .channel:
                        await chatViewModel.sendChannelMessage(text: text)
                    }
                },
                onWillSend: { scrollToBottomRequest += 1 }
            )
        }
        .overlay(alignment: .bottom) {
            ChatConversationMentionOverlay(
                suggestions: mentionSuggestions,
                onSelectMention: { insertMention(for: $0) }
            )
        }
        .navigationHeader(
            title: conversationType.navigationTitle,
            subtitle: conversationType.navigationSubtitle(
                deviceDefaultFloodScopeName: appState.connectedDevice?.defaultFloodScopeName
            ),
            subtitleAccessibilityLabel: conversationType.navigationSubtitleAccessibilityLabel(
                deviceDefaultFloodScopeName: appState.connectedDevice?.defaultFloodScopeName
            )
        )
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Info", systemImage: "info.circle") {
                    showingInfo = true
                }
            }
        }
        // Info sheet — type-specific
        .sheet(isPresented: $showingInfo, onDismiss: {
            switch conversationType {
            case .dm:
                Task { await refreshContact() }
            case .channel:
                Task { await refreshChannel() }
            }
        }, content: {
            ChatConversationInfoSheet(
                conversationType: conversationType,
                chatViewModel: chatViewModel,
                onClearChannelMessages: {
                    guard case .channel(let channel) = conversationType else { return }
                    await chatViewModel.loadChannelMessages(for: channel)
                    if let parent = parentViewModel {
                        await parent.loadChannels(radioID: channel.radioID)
                        await parent.loadLastMessagePreviews()
                    }
                },
                onDeleteChannel: { dismiss() }
            )
        })
        // Message actions sheet — shared
        .sheet(item: $selectedMessageForActions) { message in
            messageActionsSheet(for: message)
                .environment(\.horizontalSizeClass, horizontalSizeClass)
        }
        // Block sender sheet — channel only
        .sheet(item: $blockSenderContext) { context in
            BlockSenderSheet(
                senderName: context.senderName,
                radioID: context.radioID
            ) { blockedContactIDs in
                Task {
                    await performBlock(
                        senderName: context.senderName,
                        radioID: context.radioID,
                        contactIDs: blockedContactIDs
                    )
                }
            }
        }
        .sheet(item: $sendDMContext) { context in
            SendDMSheet(
                senderName: context.senderName,
                radioID: context.radioID
            ) { contact in
                appState.navigation.navigateToChat(with: contact)
            }
        }
        .fullScreenCover(item: $imageViewerData) { data in
            FullScreenImageViewer(data: data)
        }
        .onAppear {
            eventCursor = appState.messageEventBroadcaster.currentEventSequence
        }
        .task(id: appState.servicesVersion) {
            await performInitialLoad()
        }
        .onDisappear {
            performCleanup()
        }
        .onChange(of: activeMentionQuery != nil) { _, isActive in
            if isActive {
                mentionSenderOrder = chatViewModel.channelSenderOrder
            } else {
                mentionSenderOrder = nil
            }
        }
        .onChange(of: appState.messageEventBroadcaster.newMessageCount) { _, _ in
            drainEvents()
        }
        .alert(L10n.Chats.Chats.Alert.UnableToSend.title, isPresented: $chatViewModel.showRetryError) {
            Button(L10n.Chats.Chats.Common.ok, role: .cancel) { }
        } message: {
            Text(L10n.Chats.Chats.Alert.UnableToSend.message)
        }
    }

    // MARK: - Initial Load (.task)

    private func performInitialLoad() async {
        // Cancel any in-flight mention paging from a previous servicesVersion
        mentionScrollTask?.cancel()
        mentionScrollTask = nil

        // Capture pending scroll target before loading
        let pendingTarget = appState.navigation.pendingScrollToMessageID
        if pendingTarget != nil {
            appState.navigation.clearPendingScrollToMessage()
        }

        chatViewModel.configure(appState: appState, linkPreviewCache: linkPreviewCache)

        switch conversationType {
        case .dm(let contact):
            await chatViewModel.loadMessages(for: contact)
            await chatViewModel.loadConversations(radioID: contact.radioID)
            await chatViewModel.loadAllContacts(radioID: contact.radioID)
            chatViewModel.loadDraftIfExists()

        case .channel(let channel):
            // Load contacts first so contactNameSet is populated before buildChannelSenders runs
            await chatViewModel.loadAllContacts(radioID: channel.radioID)
            await chatViewModel.loadChannelMessages(for: channel)
            await chatViewModel.loadConversations(radioID: channel.radioID)
        }

        await loadUnseenMentions()

        // Trigger scroll to target message if pending (notification deeplink)
        if let targetID = pendingTarget {
            scrollToTargetID = targetID
            scrollToMentionRequest += 1
        }
    }

    // MARK: - Cleanup (.onDisappear)

    private func performCleanup() {
        mentionScrollTask?.cancel()
        mentionScrollTask = nil

        // Clear notification suppression
        switch conversationType {
        case .dm:
            appState.services?.notificationService.activeContactID = nil
        case .channel:
            appState.services?.notificationService.activeChannelIndex = nil
            appState.services?.notificationService.activeChannelRadioID = nil
        }

        // Refresh parent conversation list when leaving
        if let parent = parentViewModel {
            Task {
                guard let radioID = appState.connectedDevice?.radioID else { return }
                await parent.loadConversations(radioID: radioID)
                if case .channel = conversationType {
                    await parent.loadChannels(radioID: radioID)
                }
                await parent.loadLastMessagePreviews()
            }
        }
    }

    // MARK: - Event Draining

    private func drainEvents() {
        guard let cursor = eventCursor else { return }
        let (events, newCursor, droppedEvents) = appState.messageEventBroadcaster.events(after: cursor)
        eventCursor = newCursor
        var needsReload = droppedEvents
        var needsContactRefresh = false

        switch conversationType {
        case .dm(let contact):
            (needsReload, needsContactRefresh) = drainDMEvents(
                events, contact: contact, needsReload: needsReload
            )
        case .channel(let channel):
            needsReload = drainChannelEvents(events, channel: channel, needsReload: needsReload)
        }

        if needsReload {
            reloadMessages()
        }
        if case .dm = conversationType, needsContactRefresh || droppedEvents {
            Task { await refreshContact() }
        }
        if droppedEvents {
            Task { await loadUnseenMentions() }
        }
    }

    private func reloadMessages() {
        Task {
            switch conversationType {
            case .dm(let contact):
                await chatViewModel.loadMessages(for: contact)
            case .channel(let channel):
                await chatViewModel.loadChannelMessages(for: channel)
            }
        }
    }

    private func handleIncomingMentionIfNeeded(_ message: MessageDTO) {
        guard message.containsSelfMention else { return }
        Task {
            if isAtBottom {
                await markNewArrivalMentionSeen(messageID: message.id)
            } else {
                await loadUnseenMentions()
            }
        }
    }

    private func drainDMEvents(
        _ events: [MessageEvent], contact: ContactDTO, needsReload: Bool
    ) -> (needsReload: Bool, needsContactRefresh: Bool) {
        var needsReload = needsReload
        var needsContactRefresh = false
        for event in events {
            switch event {
            case .directMessageReceived(let message, _) where message.contactID == contact.id:
                chatViewModel.appendMessageIfNew(message)
                handleIncomingMentionIfNeeded(message)
            case .messageStatusUpdated, .messageRetrying:
                needsReload = true
            case .messageFailed(let messageID):
                if chatViewModel.messages.contains(where: { $0.id == messageID }) {
                    needsReload = true
                }
            case .routingChanged(let contactID, _) where contactID == contact.id:
                needsContactRefresh = true
            case .reactionReceived(let messageID, let summary):
                if chatViewModel.messages.contains(where: { $0.id == messageID }) {
                    chatViewModel.updateReactionSummary(for: messageID, summary: summary)
                }
            default:
                break
            }
        }
        return (needsReload, needsContactRefresh)
    }

    private func drainChannelEvents(
        _ events: [MessageEvent], channel: ChannelDTO, needsReload: Bool
    ) -> Bool {
        var needsReload = needsReload
        for event in events {
            switch event {
            case .channelMessageReceived(let message, let channelIndex)
                where channelIndex == channel.index && message.radioID == channel.radioID:
                chatViewModel.appendMessageIfNew(message)
                handleIncomingMentionIfNeeded(message)
            case .messageStatusUpdated:
                needsReload = true
            case .messageFailed(let messageID):
                if chatViewModel.messages.contains(where: { $0.id == messageID }) {
                    needsReload = true
                }
            case .heardRepeatRecorded(let messageID, let count):
                if chatViewModel.messages.contains(where: { $0.id == messageID }) {
                    chatViewModel.updateHeardRepeats(for: messageID, count: count)
                }
            case .reactionReceived(let messageID, let summary):
                if chatViewModel.messages.contains(where: { $0.id == messageID }) {
                    chatViewModel.updateReactionSummary(for: messageID, summary: summary)
                }
            default:
                break
            }
        }
        return needsReload
    }

    // MARK: - Conversation Refresh

    private func refreshContact() async {
        guard case .dm(let contact) = conversationType else { return }
        if let updated = try? await appState.services?.dataStore.fetchContact(id: contact.id) {
            conversationType = conversationType.replacingContact(updated)
            chatViewModel.currentContact = updated
        }
    }

    private func refreshChannel() async {
        guard case .channel(let channel) = conversationType else { return }
        if let updated = try? await appState.offlineDataStore?.fetchChannel(id: channel.id) {
            conversationType = conversationType.replacingChannel(updated)
        }
    }

    // MARK: - Mention Tracking

    private func loadUnseenMentions() async {
        switch conversationType {
        case .dm(let contact):
            guard let dataStore = appState.services?.dataStore else { return }
            do {
                unseenMentionIDs = try await dataStore.fetchUnseenMentionIDs(contactID: contact.id)
            } catch {
                logger.error("Failed to load unseen mentions: \(error)")
            }

        case .channel(let channel):
            guard let services = appState.services else { return }
            do {
                let allIDs = try await services.dataStore.fetchUnseenChannelMentionIDs(
                    radioID: channel.radioID,
                    channelIndex: channel.index
                )

                let blockedNames = await services.syncCoordinator.blockedSenderNames()
                if blockedNames.isEmpty {
                    unseenMentionIDs = allIDs
                    return
                }

                var filteredIDs: [UUID] = []
                for id in allIDs {
                    do {
                        if let message = try await services.dataStore.fetchMessage(id: id),
                           let senderName = message.senderNodeName,
                           blockedNames.contains(senderName) {
                            try await services.dataStore.markMentionSeen(messageID: id)
                            continue
                        }
                    } catch {
                        logger.error("Failed to check/filter mention \(id): \(error)")
                    }
                    filteredIDs.append(id)
                }
                unseenMentionIDs = filteredIDs
            } catch {
                logger.error("Failed to load unseen channel mentions: \(error)")
            }
        }
    }

    private func markMentionSeen(messageID: UUID) async {
        guard unseenMentionIDs.contains(messageID) else { return }
        guard await persistMentionSeen(messageID: messageID) else { return }
        unseenMentionIDs.removeAll { $0 == messageID }
    }

    private func markNewArrivalMentionSeen(messageID: UUID) async {
        _ = await persistMentionSeen(messageID: messageID)
    }

    private func persistMentionSeen(messageID: UUID) async -> Bool {
        guard let dataStore = appState.services?.dataStore else { return false }
        do {
            try await dataStore.markMentionSeen(messageID: messageID)
            switch conversationType {
            case .dm(let contact):
                try await dataStore.decrementUnreadMentionCount(contactID: contact.id)
                if let parent = parentViewModel, let radioID = appState.connectedDevice?.radioID {
                    await parent.loadConversations(radioID: radioID)
                }
            case .channel(let channel):
                try await dataStore.decrementChannelUnreadMentionCount(channelID: channel.id)
                if let parent = parentViewModel, let radioID = appState.connectedDevice?.radioID {
                    await parent.loadChannels(radioID: radioID)
                }
            }
            return true
        } catch {
            logger.error("Failed to mark mention seen: \(error)")
            return false
        }
    }

    // MARK: - Mention Navigation

    private func scrollToNextMention() {
        guard let targetID = unseenMentionIDs.first else { return }

        if chatViewModel.displayItems.contains(where: { $0.id == targetID }) {
            scrollToTargetID = targetID
            scrollToMentionRequest += 1
            return
        }

        mentionScrollTask?.cancel()
        mentionScrollTask = Task {
            do {
                let deadline = ContinuousClock.now + .seconds(10)
                while !chatViewModel.displayItems.contains(where: { $0.id == targetID }) {
                    guard chatViewModel.hasMoreMessages else {
                        logger.warning("Mention \(targetID) not found after exhausting history, removing")
                        if let dataStore = appState.services?.dataStore {
                            try? await dataStore.markMentionSeen(messageID: targetID)
                        }
                        unseenMentionIDs.removeAll { $0 == targetID }
                        break
                    }
                    guard unseenMentionIDs.contains(targetID) else { break }
                    guard ContinuousClock.now < deadline else {
                        logger.warning("Mention \(targetID) paging timed out")
                        break
                    }
                    if chatViewModel.isLoadingOlder {
                        try await Task.sleep(for: .milliseconds(50))
                        continue
                    }
                    await chatViewModel.loadOlderMessages()
                    try Task.checkCancellation()
                }
                if chatViewModel.displayItems.contains(where: { $0.id == targetID }) {
                    scrollToTargetID = targetID
                    scrollToMentionRequest += 1
                }
            } catch is CancellationError {
                // Expected when view disappears during paging
            } catch {
                logger.error("Failed to scroll to mention: \(error)")
            }
        }
    }

    // MARK: - Mention Suggestions

    private var activeMentionQuery: String? {
        MentionUtilities.detectActiveMention(in: chatViewModel.composingText)
    }

    private var mentionSuggestions: [ContactDTO] {
        guard let query = activeMentionQuery else { return [] }
        switch conversationType {
        case .dm:
            return MentionUtilities.filterContacts(chatViewModel.allContacts, query: query)
        case .channel:
            let combined = chatViewModel.allContacts + chatViewModel.channelSenders
            let order = mentionSenderOrder ?? chatViewModel.channelSenderOrder
            return MentionUtilities.filterContacts(combined, query: query, senderOrder: order)
        }
    }

    private func insertMention(for contact: ContactDTO) {
        guard let query = MentionUtilities.detectActiveMention(in: chatViewModel.composingText) else { return }

        let searchPattern = "@" + query
        if let range = chatViewModel.composingText.range(of: searchPattern, options: .backwards) {
            let mention = MentionUtilities.createMention(for: contact.name)
            chatViewModel.composingText.replaceSubrange(range, with: mention + " ")
        }
    }

    // MARK: - Message Actions Sheet

    private func messageActionsSheet(for message: MessageDTO) -> some View {
        let senderName: String = {
            if message.isOutgoing {
                return appState.localNodeName
            }
            switch conversationType {
            case .dm(let contact):
                return contact.displayName
            case .channel:
                return message.senderNodeName ?? L10n.Chats.Chats.Message.Sender.unknown
            }
        }()

        return MessageActionsSheet(
            message: message,
            senderName: senderName,
            recentEmojis: recentEmojisStore.recentEmojis,
            onAction: { action in
                handleMessageAction(action, for: message)
            }
        )
    }

    // MARK: - Message Action Handling

    private func handleMessageAction(_ action: MessageAction, for message: MessageDTO) {
        switch action {
        case .react(let emoji):
            recentEmojisStore.recordUsage(emoji)
            Task { await chatViewModel.sendReaction(emoji: emoji, to: message) }
        case .reply:
            let mentionName: String
            switch conversationType {
            case .dm(let contact):
                mentionName = contact.name
            case .channel:
                mentionName = message.senderNodeName ?? L10n.Chats.Chats.Message.Sender.unknown
            }
            if replyWithQuote {
                chatViewModel.composingText = MentionUtilities.buildReplyText(mentionName: mentionName, messageText: message.text)
            } else {
                chatViewModel.composingText = MentionUtilities.createMention(for: mentionName) + " "
            }
            isInputFocused = true
        case .copy:
            UIPasteboard.general.string = message.text
        case .sendAgain:
            Task { await chatViewModel.sendAgain(message) }
        case .blockSender:
            guard case .channel(let channel) = conversationType, let name = message.senderNodeName else { return }
            Task {
                try? await Task.sleep(for: messageActionSheetPresentationDelay)
                blockSenderContext = BlockSenderContext(senderName: name, radioID: channel.radioID)
            }
        case .sendDM:
            guard case .channel(let channel) = conversationType, let name = message.senderNodeName else { return }
            Task {
                try? await Task.sleep(for: messageActionSheetPresentationDelay)
                sendDMContext = SendDMContext(senderName: name, radioID: channel.radioID)
            }
        case .delete:
            Task { await chatViewModel.deleteMessage(message) }
        }
    }


    private func retryMessage(_ message: MessageDTO) {
        Task {
            switch conversationType {
            case .dm:
                await chatViewModel.retryMessage(message)
            case .channel:
                await chatViewModel.retryChannelMessage(message)
            }
        }
    }

    // MARK: - Blocking (Channel only)

    private func performBlock(senderName: String, radioID: UUID, contactIDs: Set<UUID>) async {
        guard let services = appState.services else { return }

        let dto = BlockedChannelSenderDTO(name: senderName, radioID: radioID)
        do {
            try await services.dataStore.saveBlockedChannelSender(dto)
        } catch {
            logger.error("Failed to save blocked channel sender: \(error)")
            return
        }

        // Delete existing channel messages from the blocked sender
        try? await services.dataStore.deleteChannelMessages(fromSender: senderName, radioID: radioID)

        for contactID in contactIDs {
            do {
                try await services.contactService.updateContactPreferences(
                    contactID: contactID,
                    isBlocked: true
                )
            } catch {
                logger.error("Failed to block contact \(contactID): \(error)")
            }
        }

        await services.syncCoordinator.refreshBlockedContactsCache(
            radioID: radioID,
            dataStore: services.dataStore
        )

        if !contactIDs.isEmpty {
            services.syncCoordinator.notifyContactsChanged()
        }

        if case .channel(let channel) = conversationType {
            await chatViewModel.loadChannelMessages(for: channel)
        }
        services.syncCoordinator.notifyConversationsChanged()
    }
}

// MARK: - Previews

#Preview("DM") {
    NavigationStack {
        ChatConversationView(
            conversationType: .dm(ContactDTO(from: Contact(
                radioID: UUID(),
                publicKey: Data(repeating: 0x42, count: 32),
                name: "Alice"
            )))
        )
    }
    .environment(\.appState, AppState())
}

#Preview("Channel") {
    NavigationStack {
        ChatConversationView(
            conversationType: .channel(ChannelDTO(from: Channel(
                radioID: UUID(),
                index: 1,
                name: "General"
            )))
        )
    }
    .environment(\.appState, AppState())
}
