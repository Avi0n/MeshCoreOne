import MC1Services
import OSLog
import SwiftUI
import UIKit // UIPasteboard for .copy action

private let logger = Logger(subsystem: "com.mc1", category: "ChatConversationView")

/// Quiet period after the last keystroke before the composer draft is persisted,
/// so rapid typing coalesces into a single write.
private let draftSaveDebounce: Duration = .milliseconds(500)

/// Unified chat conversation view supporting both DMs and Channels.
struct ChatConversationView: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass
  @Environment(\.linkPreviewCache) private var linkPreviewCache
  @Environment(\.scenePhase) private var scenePhase

  @State private var conversationType: ChatConversationType
  let parentViewModel: ChatViewModel?

  @State private var chatViewModel = ChatViewModel()

  // MARK: - Scroll State

  @State private var isAtBottom = true
  @State private var unreadCount = 0
  @State private var scrollToBottomRequest = 0
  @State private var scrollToMentionRequest = 0
  @State private var unseenMentionIDs: [UUID] = []
  /// The subset of `unseenMentionIDs` currently off screen, reported up by the chat table.
  /// Drives the scroll-to-mention button and is the source for its tap target.
  @State private var offscreenMentionIDs: [UUID] = []
  @State private var scrollToTargetID: UUID?
  @State private var mentionScrollTask: Task<Void, Never>?
  @State private var scrollToDividerRequest = 0
  @State private var isDividerVisible = false

  /// Pending debounced draft persist; cancelled and restarted on each keystroke,
  /// cancelled-then-flushed synchronously on view teardown and app suspension.
  @State private var draftSaveTask: Task<Void, Never>?

  // MARK: - Sheet State

  @State private var showingInfo = false
  @State private var selectedMessageForActions: MessageDTO?
  @State private var blockSenderContext: BlockSenderContext?
  @State private var sendDMContext: SendDMContext?
  @State private var imageViewerData: ImageViewerData?

  // MARK: - Other State

  @State private var recentEmojisStore = RecentEmojisStore()
  @State private var mentionSenderOrder: [String: UInt32]?
  /// Focus-request token: each increment asks the composer to raise the
  /// keyboard once. See `ChatComposerTextView` for why a token, not `@FocusState`.
  @State private var inputFocusRequest = 0

  // MARK: - AppStorage

  @AppStorage(AppStorageKey.autoPlayGIFs.rawValue) private var autoPlayGIFs = AppStorageKey.defaultAutoPlayGIFs
  @AppStorage(AppStorageKey.showIncomingPath.rawValue) private var showIncomingPath = AppStorageKey.defaultShowIncomingPath
  @AppStorage(AppStorageKey.showIncomingHopCount.rawValue) private var showIncomingHopCount = AppStorageKey.defaultShowIncomingHopCount
  @AppStorage(AppStorageKey.showIncomingRegion.rawValue) private var showIncomingRegion = AppStorageKey.defaultShowIncomingRegion
  @AppStorage(AppStorageKey.showIncomingSendTime.rawValue) private var showIncomingSendTime = AppStorageKey.defaultShowIncomingSendTime
  @AppStorage(AppStorageKey.linkPreviewsEnabled.rawValue) private var previewsEnabled = AppStorageKey.defaultLinkPreviewsEnabled
  @AppStorage(AppStorageKey.replyWithQuote.rawValue) private var replyWithQuote = AppStorageKey.defaultReplyWithQuote
  @AppStorage(AppStorageKey.showMapPreviewThumbnails.rawValue) private var showMapPreviewThumbnails = AppStorageKey.defaultShowMapPreviewThumbnails

  // MARK: - Environment

  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize
  @Environment(\.appTheme) private var theme

  /// Snapshot of env-derived inputs the view model needs to construct
  /// MessageItems at write time. Recomputed on every render — Equatable
  /// drives `.onChange(of: envInputs)` in ChatMessagesTableView.
  private var currentEnvInputs: EnvInputs {
    EnvInputs(
      autoPlayGIFs: autoPlayGIFs,
      showIncomingPath: showIncomingPath,
      showIncomingHopCount: showIncomingHopCount,
      showIncomingRegion: showIncomingRegion,
      showIncomingSendTime: showIncomingSendTime,
      previewsEnabled: previewsEnabled,
      isHighContrast: colorSchemeContrast == .increased,
      isDark: colorScheme == .dark,
      showMapPreviews: showMapPreviewThumbnails && !conversationType.suppressesMapPreviews,
      isOffline: !appState.offlineMapService.isNetworkAvailable,
      currentUserName: appState.localNodeName,
      themeID: theme.id,
      contentSizeCategory: AppearanceToken.contentSizeCategoryToken(dynamicTypeSize)
    )
  }

  // MARK: - Init

  init(conversationType: ChatConversationType, parentViewModel: ChatViewModel? = nil) {
    _conversationType = State(initialValue: conversationType)
    self.parentViewModel = parentViewModel
  }

  // MARK: - Body

  var body: some View {
    ChatConversationMessagesContent(
      conversationType: conversationType,
      viewModel: chatViewModel,
      deviceName: appState.localNodeName,
      recentEmojisStore: recentEmojisStore,
      envInputs: currentEnvInputs,
      isAtBottom: $isAtBottom,
      unreadCount: $unreadCount,
      scrollToBottomRequest: $scrollToBottomRequest,
      scrollToMentionRequest: $scrollToMentionRequest,
      scrollToDividerRequest: $scrollToDividerRequest,
      isDividerVisible: $isDividerVisible,
      unseenMentionIDs: unseenMentionIDs,
      offscreenMentionIDs: $offscreenMentionIDs,
      scrollToTargetID: scrollToTargetID,
      newMessagesDividerMessageID: chatViewModel.newMessagesDividerMessageID,
      selectedMessageForActions: $selectedMessageForActions,
      imageViewerData: $imageViewerData,
      onMentionSeen: { await markMentionSeen(messageID: $0) },
      onScrollToMention: { scrollToNextMention() },
      onRetryMessage: { retryMessage($0) }
    )
    .mentionTapHandling(
      contacts: chatViewModel.allContacts,
      radioID: conversationType.radioID,
      shouldSuppressOpen: { selectedMessageForActions != nil }
    )
    // Banner is applied innermost so its safe-area inset stacks above the
    // input bar inset that follows, placing the strip between content and
    // the input bar (and lifting it with the keyboard).
    .chatErrorBanner(chatViewModel: chatViewModel)
    .safeAreaInset(edge: .bottom, spacing: 8) {
      ChatConversationInputBar(
        conversationType: conversationType,
        composingText: $chatViewModel.composingText,
        focusRequest: $inputFocusRequest,
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
        Button(L10n.Chats.Chats.Common.info, systemImage: "info.circle") {
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
          guard case let .channel(channel) = conversationType else { return }
          await chatViewModel.loadChannelMessages(for: channel)
          parentViewModel?.requestConversationReload()
        },
        onClearDirectMessages: {
          guard case let .dm(contact) = conversationType else { return }
          await chatViewModel.loadMessages(for: contact)
          parentViewModel?.requestConversationReload()
        },
        onDeleteChannel: {
          // Clear the composer so the teardown flush doesn't re-persist a draft for the
          // freed slot — a channel later reusing that slot would otherwise surface it.
          chatViewModel.composingText = ""
          dismiss()
        }
      )
    })
    // Long-press / secondary-click actions sheet. Bound to a captured value, so
    // messages arriving behind it never re-anchor it to a different bubble.
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
        radioID: context.radioID,
        unverifiedNickname: context.unverifiedNickname
      ) { contact in
        appState.navigation.navigateToChat(with: contact)
      }
    }
    .fullScreenCover(item: $imageViewerData) { data in
      FullScreenImageViewer(data: data)
    }
    .task(id: appState.servicesVersion) {
      await performInitialLoad()
    }
    .onDisappear {
      // Load-bearing on iPad: MainSidebarView pins the Chats detail stack with
      // `.id(chatsSelectedRoute.conversationID)`, so a detail swap tears down this view's
      // @State (including draftSaveTask) before the debounce fires — flush here.
      flushDraft()
      performCleanup()
    }
    .onChange(of: chatViewModel.composingText) { _, _ in
      scheduleDraftSave()
    }
    .onChange(of: scenePhase) { _, newPhase in
      // Notifications usually arrive while the app is backgrounded with this
      // chat already on screen, so re-clear the tray when we return to the
      // foreground — the open hook alone never re-fires in that case.
      switch newPhase {
      case .active:
        Task { await clearDeliveredNotifications() }
      case .background, .inactive:
        // The OS can suspend the process before the debounce fires, so flush
        // the draft synchronously here.
        flushDraft()
      @unknown default:
        break
      }
    }
    .onChange(of: activeMentionQuery != nil) { _, isActive in
      if isActive {
        mentionSenderOrder = chatViewModel.channelSenderOrder
      } else {
        mentionSenderOrder = nil
      }
    }
    .task {
      for await event in appState.messageEventStream.events() {
        await chatViewModel.handle(event)
      }
    }
    .onChange(of: chatViewModel.contactRefreshSignal) { _, _ in
      Task { await refreshContact() }
    }
    .onChange(of: chatViewModel.lastIncomingMention) { _, newMention in
      guard let mention = newMention else { return }
      handleIncomingMentionIfNeeded(mention.messageID)
    }
    .onChange(of: appState.contactsVersion) { _, _ in
      // Keep the mention-resolution snapshot fresh: a contact added after the
      // chat opened must be tappable without reopening the screen.
      Task { await chatViewModel.loadAllContacts(radioID: conversationType.radioID) }
    }
    .chatErrorAlerts(chatViewModel: chatViewModel)
    // Chrome theming comes from the stack-level themedChrome on the TabView. Re-declaring it
    // on this pushed destination makes the nav bar appearance re-install after the push, which
    // reflows the flipped table's top rows.
    .background {
      if let canvas = theme.surfaces?.canvas {
        canvas.ignoresSafeArea()
      }
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

    chatViewModel.configure(
      dependencies: ChatViewModel.Dependencies(
        dataStore: { appState.offlineDataStore },
        messageService: { appState.services?.messageService },
        notificationService: { appState.services?.notificationService },
        channelService: { appState.services?.channelService },
        roomServerService: { appState.services?.roomServerService },
        contactService: { appState.services?.contactService },
        syncCoordinator: { appState.syncCoordinator },
        connectionState: { appState.connectionState },
        connectedDevice: { appState.connectedDevice },
        currentRadioID: { appState.currentRadioID },
        session: { appState.services?.session },
        reactionService: { appState.services?.reactionService },
        chatSendQueueService: { appState.services?.chatSendQueueService },
        inlineImageDimensionsStore: { appState.services?.inlineImageDimensionsStore },
        prefetchDataStore: { appState.services?.dataStore }
      ),
      onNavigateToMap: { appState.navigation.navigateToMap(coordinate: $0) },
      linkPreviewCache: linkPreviewCache,
      chatCoordinatorRegistry: appState.ensureChatCoordinatorRegistry(),
      conversation: conversationType
    )
    chatViewModel.applyEnvInputs(currentEnvInputs)

    switch conversationType {
    case let .dm(contact):
      await chatViewModel.loadMessages(for: contact)
      await chatViewModel.loadConversations(radioID: contact.radioID)
      await chatViewModel.loadAllContacts(radioID: contact.radioID)
      chatViewModel.restoreComposerDraft(from: appState.draftStore, id: conversationType.draftConversationID)

    case let .channel(channel):
      // Load contacts first so contactNameSet is populated before buildChannelSenders runs
      await chatViewModel.loadAllContacts(radioID: channel.radioID)
      await chatViewModel.loadChannelMessages(for: channel)
      await chatViewModel.loadConversations(radioID: channel.radioID)
      chatViewModel.restoreComposerDraft(from: appState.draftStore, id: conversationType.draftConversationID)
    }

    await loadUnseenMentions()

    // Trigger scroll to target message if pending (notification deeplink)
    if let targetID = pendingTarget {
      scrollToTargetID = targetID
      scrollToMentionRequest += 1
    }

    // Clear any notifications for this conversation still sitting in the tray
    // (delivered while the app was backgrounded). The load above already
    // cleared the unread count, so the recomputed badge stays correct.
    await clearDeliveredNotifications()
  }

  /// Removes delivered lock-screen / Notification Center notifications for the
  /// conversation the user just opened, then recomputes the app badge.
  private func clearDeliveredNotifications() async {
    guard let notificationService = appState.services?.notificationService else { return }
    switch conversationType {
    case let .dm(contact):
      await notificationService.removeDeliveredNotifications(forContactID: contact.id)
    case let .channel(channel):
      await notificationService.removeDeliveredNotifications(
        forChannelIndex: channel.index,
        radioID: channel.radioID
      )
    }
    await notificationService.updateBadgeCount()
  }

  // MARK: - Draft Persistence

  /// Restarts the debounced draft persist. The post-sleep cancellation guard is
  /// required: `Task.cancel()` only sets a flag and `try?` swallows
  /// `Task.sleep`'s `CancellationError`, so without it a synchronous flush that
  /// already saved could be overwritten by the resuming task re-saving stale text.
  private func scheduleDraftSave() {
    draftSaveTask?.cancel()
    let id = conversationType.draftConversationID
    draftSaveTask = Task {
      try? await Task.sleep(for: draftSaveDebounce)
      guard !Task.isCancelled else { return }
      chatViewModel.saveDraft(to: appState.draftStore, id: id)
    }
  }

  /// Cancels any pending debounce and persists the current draft synchronously, reading the
  /// store from the view's non-optional `@Environment(\.appState)` (matching `performCleanup`).
  private func flushDraft() {
    draftSaveTask?.cancel()
    draftSaveTask = nil
    chatViewModel.saveDraft(to: appState.draftStore, id: conversationType.draftConversationID)
  }

  // MARK: - Cleanup (.onDisappear)

  private func performCleanup() {
    mentionScrollTask?.cancel()
    mentionScrollTask = nil

    // Clear notification suppression only if this conversation still owns the
    // active slot; a newer conversation's open may have already claimed it
    // before this view tears down.
    let service = appState.services?.notificationService
    switch conversationType {
    case let .dm(contact):
      if service?.activeContactID == contact.id {
        service?.activeContactID = nil
      }
    case let .channel(channel):
      if service?.activeChannelIndex == channel.index,
         service?.activeChannelRadioID == channel.radioID {
        service?.activeChannelIndex = nil
        service?.activeChannelRadioID = nil
      }
    }

    // Refresh parent conversation list when leaving
    parentViewModel?.requestConversationReload()
  }

  private func handleIncomingMentionIfNeeded(_ messageID: UUID) {
    // Self-mention gating happens upstream in
    // `ChatViewModel.recordIncomingMentionIfNeeded`, which only assigns
    // `lastIncomingMention` when `containsSelfMention` is true.
    Task {
      if isAtBottom {
        await markNewArrivalMentionSeen(messageID: messageID)
      } else {
        await loadUnseenMentions()
      }
    }
  }

  // MARK: - Conversation Refresh

  private func refreshContact() async {
    guard case let .dm(contact) = conversationType else { return }
    if let updated = try? await appState.services?.dataStore.fetchContact(id: contact.id) {
      conversationType = conversationType.replacingContact(updated)
      chatViewModel.currentContact = updated
    }
  }

  private func refreshChannel() async {
    guard case let .channel(channel) = conversationType else { return }
    if let updated = try? await appState.offlineDataStore?.fetchChannel(id: channel.id) {
      conversationType = conversationType.replacingChannel(updated)
    }
  }

  // MARK: - Mention Tracking

  private func loadUnseenMentions() async {
    switch conversationType {
    case let .dm(contact):
      guard let dataStore = appState.services?.dataStore else { return }
      do {
        unseenMentionIDs = try await dataStore.fetchUnseenMentionIDs(contactID: contact.id)
      } catch {
        logger.error("Failed to load unseen mentions: \(error)")
      }

    case let .channel(channel):
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

  /// Marks a mention seen and reports whether the result is settled. Returns true when the
  /// mention was already seen or the persist succeeded; false only when the persist failed,
  /// so the caller can re-attempt rather than treat the id as handled.
  @discardableResult
  private func markMentionSeen(messageID: UUID) async -> Bool {
    guard unseenMentionIDs.contains(messageID) else { return true }
    guard await persistMentionSeen(messageID: messageID) else { return false }
    unseenMentionIDs.removeAll { $0 == messageID }
    return true
  }

  private func markNewArrivalMentionSeen(messageID: UUID) async {
    _ = await persistMentionSeen(messageID: messageID)
  }

  private func persistMentionSeen(messageID: UUID) async -> Bool {
    guard let dataStore = appState.services?.dataStore else { return false }
    do {
      try await dataStore.markMentionSeen(messageID: messageID)
      switch conversationType {
      case let .dm(contact):
        try await dataStore.decrementUnreadMentionCount(contactID: contact.id)
        parentViewModel?.requestConversationReload()
      case let .channel(channel):
        try await dataStore.decrementChannelUnreadMentionCount(channelID: channel.id)
        parentViewModel?.requestConversationReload()
      }
      return true
    } catch {
      logger.error("Failed to mark mention seen: \(error)")
      return false
    }
  }

  // MARK: - Mention Navigation

  private func scrollToNextMention() {
    // Target the newest off-screen mention so the first tap lands on the latest unread the
    // user hasn't reached; repeated taps walk upward through older mentions, showing the
    // earliest last. An on-screen mention is already in view and would make the tap a no-op,
    // so the off-screen subset is the right source.
    guard let targetID = ChatScrollToMentionPolicy.nextTarget(offscreenMentions: offscreenMentionIDs) else { return }

    if chatViewModel.items.contains(where: { $0.id == targetID }) {
      issueMentionScroll(to: targetID)
      return
    }

    mentionScrollTask?.cancel()
    mentionScrollTask = Task {
      do {
        let deadline = ContinuousClock.now + .seconds(10)
        // Page on the authoritative coordinator-backed messages, not the lagging
        // rendered items. loadOlderMessages mutates messages synchronously but only
        // schedules the off-main items rebuild, so gating on items overshoots a page
        // per spin and can exhaust history before a render lands, tripping the
        // destructive not-found branch that hides a real unread mention.
        while !chatViewModel.messages.contains(where: { $0.id == targetID }) {
          guard chatViewModel.hasMoreMessages else {
            logger.warning("Mention \(targetID) not found after exhausting history, removing")
            if let dataStore = appState.services?.dataStore {
              try? await dataStore.markMentionSeen(messageID: targetID)
            }
            unseenMentionIDs.removeAll { $0 == targetID }
            break
          }
          // offscreenMentionIDs is an async mirror; a target marked seen mid-paging is
          // already gone from unseenMentionIDs, so stop rather than page for a read mention.
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

        // Target is in the model; wait (bounded) for the rebuild to surface it in the
        // rendered items, which scrollToItem addresses by row, before scrolling.
        while chatViewModel.messages.contains(where: { $0.id == targetID }),
              !chatViewModel.items.contains(where: { $0.id == targetID }),
              ContinuousClock.now < deadline {
          try await Task.sleep(for: .milliseconds(50))
          try Task.checkCancellation()
        }

        if chatViewModel.items.contains(where: { $0.id == targetID }) {
          issueMentionScroll(to: targetID)
        }
      } catch is CancellationError {
        // Expected when view disappears during paging
      } catch {
        logger.error("Failed to scroll to mention: \(error)")
      }
    }
  }

  /// Scrolls to a mention and advances the queue. The seen-transition is driven directly
  /// rather than waiting for the row's visibility tick, which never fires when the target
  /// is already centered (scrollToRow does not move contentOffset), leaving the tap a no-op.
  private func issueMentionScroll(to targetID: UUID) {
    scrollToTargetID = targetID
    scrollToMentionRequest += 1
    Task { await markMentionSeen(messageID: targetID) }
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

  /// Builds the drift-proof message actions sheet for a captured message value.
  /// Presented via `.sheet(item:)`, which binds to the value rather than a cell,
  /// so incoming messages reorder the table behind the modal without re-anchoring
  /// it to a different bubble.
  private func messageActionsSheet(for message: MessageDTO) -> MessageActionsSheet {
    let resolution = senderResolution(for: message)
    return MessageActionsSheet(
      message: message,
      senderResolution: resolution,
      recentEmojis: recentEmojisStore.recentEmojis,
      onAction: { action in
        dispatch(action, for: message)
      }
    )
  }

  private func senderResolution(for message: MessageDTO) -> NodeNameResolution {
    if message.isOutgoing {
      return NodeNameResolution(displayName: appState.localNodeName, matchKind: .exact)
    }
    switch conversationType {
    case let .dm(contact):
      return NodeNameResolution(displayName: contact.displayName, matchKind: .exact)
    case .channel:
      return MessageBubbleConfiguration.resolveSenderName(
        for: message,
        contacts: chatViewModel.allContacts,
        nicknamesByLoweredName: chatViewModel.nicknamesByLoweredName
      )
    }
  }

  // MARK: - Message Action Handling

  /// Dispatches a MessageAction by routing to the appropriate handler. The
  /// `switch action` body preserves compile-time exhaustiveness — adding a new
  /// MessageAction case forces this method to handle it. Each case calls an
  /// extracted private method that captures the view-local context it needs
  /// (focus state, AppStorage flags, sheet-presentation contexts).
  private func dispatch(_ action: MessageAction, for message: MessageDTO) {
    switch action {
    case let .react(emoji):
      handleReact(emoji: emoji, for: message)
    case .reply:
      handleReply(for: message)
    case .copy:
      handleCopy(for: message)
    case .sendAgain:
      handleSendAgain(for: message)
    case .blockSender:
      handleBlockSender(for: message)
    case .sendDM:
      handleSendDM(for: message)
    case .delete:
      handleDelete(for: message)
    }
  }

  private func handleReact(emoji: String, for message: MessageDTO) {
    recentEmojisStore.recordUsage(emoji)
    Task { await chatViewModel.sendReaction(emoji: emoji, to: message) }
  }

  private func handleReply(for message: MessageDTO) {
    let mentionName: String = switch conversationType {
    case let .dm(contact):
      contact.name
    case .channel:
      message.senderNodeName ?? L10n.Chats.Chats.Message.Sender.unknown
    }
    if replyWithQuote {
      chatViewModel.composingText = MentionUtilities.buildReplyText(mentionName: mentionName, messageText: message.text)
    } else {
      chatViewModel.composingText = MentionUtilities.appendMention(
        for: mentionName,
        to: chatViewModel.composingText
      )
    }
    // Raise the keyboard only after the actions sheet has finished dismissing;
    // a focus request issued while the sheet is still animating away is lost.
    Task {
      try? await Task.sleep(for: MessageActionsPresentation.dismissalDelay)
      inputFocusRequest += 1
    }
  }

  private func handleCopy(for message: MessageDTO) {
    UIPasteboard.general.string = message.text
  }

  private func handleSendAgain(for message: MessageDTO) {
    Task { await chatViewModel.sendAgain(message) }
  }

  private func handleBlockSender(for message: MessageDTO) {
    guard case let .channel(channel) = conversationType,
          let name = message.senderNodeName else { return }
    Task {
      try? await Task.sleep(for: MessageActionsPresentation.dismissalDelay)
      blockSenderContext = BlockSenderContext(senderName: name, radioID: channel.radioID)
    }
  }

  private func handleSendDM(for message: MessageDTO) {
    guard case let .channel(channel) = conversationType,
          let name = message.senderNodeName else { return }
    let nickname = chatViewModel.nicknamesByLoweredName[name.lowercased()]
    Task {
      try? await Task.sleep(for: MessageActionsPresentation.dismissalDelay)
      sendDMContext = SendDMContext(senderName: name, radioID: channel.radioID, unverifiedNickname: nickname)
    }
  }

  private func handleDelete(for message: MessageDTO) {
    Task { await chatViewModel.deleteMessage(message) }
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

    if case let .channel(channel) = conversationType {
      await chatViewModel.loadChannelMessages(for: channel)
    }
    services.syncCoordinator.notifyConversationsChanged()
  }
}

// MARK: - Error Alerts

private extension View {
  /// Applies the chat modal-alert surfaces in one modifier so the conversation
  /// view body stays within the type-checker's expression budget. Two modal
  /// alerts: generic "Error" for open-conversation load failures (so a
  /// re-open failure cannot be missed), and "Unable to Send" for queue drain
  /// failures. The passive banner for pagination failures is mounted
  /// separately via `chatErrorBanner` so it can sit above the input bar.
  func chatErrorAlerts(chatViewModel: ChatViewModel) -> some View {
    @Bindable var vm = chatViewModel
    return errorAlert($vm.errorMessage)
      .errorAlert($vm.sendErrorMessage, title: L10n.Chats.Chats.Alert.UnableToSend.title)
  }

  /// Mounts the passive error banner used for background failures (e.g.
  /// older-message pagination). Applied before the input-bar safe-area inset
  /// so the banner appears between the message list and the input bar, and
  /// rises with the keyboard alongside the input bar.
  func chatErrorBanner(chatViewModel: ChatViewModel) -> some View {
    @Bindable var vm = chatViewModel
    return errorBanner($vm.errorBannerMessage)
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
