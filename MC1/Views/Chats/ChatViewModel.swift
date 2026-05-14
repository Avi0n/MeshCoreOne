import SwiftUI
import UIKit
import MC1Services
import OSLog

/// ViewModel for chat operations
@Observable
@MainActor
final class ChatViewModel {

    /// Tracks the last flood scope we pushed to the device for the current channel.
    /// Keyed by the input pair (per-channel preference, device default) so that a
    /// change in either triggers a fresh push next time `loadChannelMessages` runs.
    enum RegionScopeState: Equatable {
        case unknown
        case pushed(ChannelFloodScope, deviceDefault: String?)
    }

    // MARK: - Properties

    let logger = Logger(subsystem: "com.mc1", category: "ChatViewModel")

    /// Current conversations (contacts with messages)
    var conversations: [ContactDTO] = []

    /// All contacts for mention autocomplete (includes contacts without messages)
    var allContacts: [ContactDTO] = []

    /// Synthetic contacts for channel senders not in contacts
    var channelSenders: [ContactDTO] = []

    /// O(1) lookup for channel sender names
    var channelSenderNames: Set<String> = []

    /// Sender name → latest message timestamp (for mention sort order)
    var channelSenderOrder: [String: UInt32] = [:]

    /// O(1) lookup for contact names
    var contactNameSet: Set<String> = []

    /// Current channels with messages
    var channels: [ChannelDTO] = []

    /// Current room sessions
    var roomSessions: [RemoteNodeSessionDTO] = []

    /// Combined conversations (contacts + channels + rooms) - favorites first
    var allConversations: [Conversation] {
        favoriteConversations + nonFavoriteConversations
    }

    /// Favorite conversations sorted by last message date
    var favoriteConversations: [Conversation] {
        rebuildConversationCacheIfNeeded()
        touchObservationDependencies()
        return cachedFavoriteConversations
    }

    /// Non-favorite conversations sorted by last message date
    var nonFavoriteConversations: [Conversation] {
        rebuildConversationCacheIfNeeded()
        touchObservationDependencies()
        return cachedNonFavoriteConversations
    }

    // MARK: - Conversation Cache

    @ObservationIgnored private var cachedFavoriteConversations: [Conversation] = []
    @ObservationIgnored private var cachedNonFavoriteConversations: [Conversation] = []
    @ObservationIgnored private var conversationCacheValid = false
    @ObservationIgnored var urlDetectionTask: Task<Void, Never>?
    /// Bumped on every buildItems rebuild. Only the URL-detection writer
    /// checks this before mutating cachedURLs; single-row rebuilds via
    /// rebuildDisplayItem do not write cachedURLs and do not need gating.
    @ObservationIgnored var urlDetectionGeneration: UInt64 = 0
    /// Monotonic counter bumped on every mutation of `messages` and every
    /// single-row write to `renderState`. Captured at `buildItems()` entry
    /// and re-checked inside the off-main apply step so a stale batch build
    /// does not clobber fresher state. Bump via `bumpBuildGeneration()`.
    @ObservationIgnored private var buildGeneration: UInt64 = 0
    /// In-flight off-main batch build. Cancelled before each new
    /// `buildItems()` call so successive rebuilds do not pile up concurrent
    /// work. Mirrors the `urlDetectionTask` cancel-and-reassign pattern.
    @ObservationIgnored var buildItemsTask: Task<Void, Never>?
    /// Tracks the last region scope sent to the device via setFloodScope.
    @ObservationIgnored var lastSetRegionScope: RegionScopeState = .unknown

    /// Bump the build generation. Call from every site that mutates
    /// `messages` or writes a single-row update into `renderState`.
    func bumpBuildGeneration() {
        buildGeneration &+= 1
    }

    /// Current build generation. Captured at `buildItems()` entry and
    /// validated inside `MainActor.run` before the off-main apply.
    func currentBuildGeneration() -> UInt64 {
        buildGeneration
    }

    /// Fallback date for conversations with no messages, used to sort them to the end.
    private static let noMessageSentinel = Date.distantPast

    /// Invalidates the conversation cache, forcing rebuild on next access
    func invalidateConversationCache() {
        conversationCacheValid = false
    }

    /// Touch source arrays to maintain observation dependencies even when cache is valid.
    /// Without this, SwiftUI won't track changes after initial render because
    /// @ObservationIgnored cache properties don't register dependencies.
    private func touchObservationDependencies() {
        _ = conversations.count
        _ = channels.count
        _ = roomSessions.count
    }

    private func rebuildConversationCacheIfNeeded() {
        guard !conversationCacheValid else { return }

        let contactConversations = conversations
            .filter { $0.type != .repeater && !$0.isBlocked }
            .map { Conversation.direct($0) }
        let channelConversations = channels
            .filter { !$0.name.isEmpty || $0.hasSecret }
            .map { Conversation.channel($0) }
        let roomConversations = roomSessions.map { Conversation.room($0) }
        let all = contactConversations + channelConversations + roomConversations

        cachedFavoriteConversations = sortedByLastMessage(all.filter { $0.isFavorite })
        cachedNonFavoriteConversations = sortedByLastMessage(all.filter { !$0.isFavorite })

        conversationCacheValid = true
    }

    /// Sorts conversations by last message date, most recent first.
    private func sortedByLastMessage(_ items: [Conversation]) -> [Conversation] {
        items.sorted { ($0.lastMessageDate ?? Self.noMessageSentinel) > ($1.lastMessageDate ?? Self.noMessageSentinel) }
    }

    /// Messages for the current conversation
    var messages: [MessageDTO] = []

    /// Immutable snapshot of the timeline as the view sees it.
    /// Rebuilt on every load or mutation via `renderState = renderState.with(...)`;
    /// views read through the computed accessors below.
    var renderState: ChatRenderState = .empty

    /// Environment-derived inputs (six `@AppStorage` toggles, contrast,
    /// current user name) that feed `MessageItem` construction.
    /// `ChatConversationView` pushes via `applyEnvInputs(_:)` before
    /// `loadMessages` and on subsequent toggle changes.
    var envInputs: EnvInputs = .default

    /// Update env-derived inputs and trigger a full rebuild when the value
    /// changes and there are messages to rebuild. Idempotent on no-change.
    func applyEnvInputs(_ new: EnvInputs) {
        guard envInputs != new else { return }
        envInputs = new
        guard !messages.isEmpty else { return }
        buildItems()
    }

    /// Monotonic counter bumped when an incoming `MessageEvent` indicates the
    /// timeline needs a reload from the data store. Observed by
    /// `ChatConversationView` via `.onChange`; the chase-the-counter pattern in
    /// `coalescedReload(for:)` debounces racing reloads during event bursts.
    var reloadSignal: UInt64 = 0

    /// Monotonic counter bumped when a `MessageEvent` indicates the current
    /// contact (route, profile) should be re-fetched. Observed by the view.
    var contactRefreshSignal: UInt64 = 0

    /// Most recent incoming self-mention, with a per-mention sequence number
    /// so consecutive mentions of the same message still fire `.onChange`.
    var lastIncomingMention: MentionEvent?

    /// Internal mention counter; never observed by the view, so it stays out
    /// of the `@Observable` graph.
    @ObservationIgnored var mentionSequence: UInt64 = 0

    /// Latch for the chase-the-counter reload coalescer in `coalescedReload(for:)`.
    @ObservationIgnored var reloadInFlight = false

    /// Stored timeline rows. The cell-content closure consumes this directly;
    /// the bubble view reads `MessageItem` fields without any per-render rebuild.
    var items: [MessageItem] { renderState.items }

    /// O(1) item-index lookup by message ID, forwarded from the render state.
    var itemIndexByID: [UUID: Int] { renderState.itemIndexByID }

    /// O(1) message lookup by ID (used by views to get full DTO when needed)
    var messagesByID: [UUID: MessageDTO] = [:]

    /// Current contact being chatted with
    var currentContact: ContactDTO?

    /// Current channel being viewed
    var currentChannel: ChannelDTO?

    /// Radio ID currently in scope for persisted pending sends. Prefers the
    /// currently selected conversation's radio; falls back to AppState's
    /// current radio so resends fire correctly even between conversation
    /// switches.
    var currentRadioID: UUID? {
        currentContact?.radioID ?? currentChannel?.radioID ?? appState?.currentRadioID
    }

    /// Loading state
    var isLoading = false

    /// Whether data has been loaded at least once (prevents empty state flash)
    var hasLoadedOnce = false

    /// Error message if any
    var errorMessage: String?

    /// Error state for send-only failures (queue drains, retry call site).
    /// Separate from `errorMessage`, which surfaces load and fetch errors
    /// with the generic "Error" alert title. `sendErrorMessage` surfaces
    /// with the "Unable to Send" title so retry-context errors read as
    /// user-actionable rather than generic.
    ///
    /// Routing:
    /// - Send-queue drain errors are assigned post-`loadMessages` so the
    ///   load reset of `errorMessage = nil` does not clobber the alert.
    /// - Load errors continue to flow to `errorMessage`.
    var sendErrorMessage: String?

    /// Message text being composed
    var composingText = ""

    /// Shared service box the chat send queues read on each drain step.
    /// Lives for the view-model's lifetime; `configure*()` mutates fields
    /// in place so a BLE reconnect rebinds services without recreating
    /// the queues. The send closures capture this by reference.
    @ObservationIgnored let sendContext = ChatSendContext()

    /// Serial drain for outgoing DMs. Constructed on first `configure*()`
    /// and reused thereafter — never recreated across reconnects because
    /// it captures `sendContext` (which is mutated in place instead).
    @ObservationIgnored var dmSendQueue: SendQueue<DirectMessageEnvelope>?

    /// Serial drain for outgoing channel messages. Constructed on first
    /// `configure*()` and reused thereafter — see `dmSendQueue` for the
    /// rebind-across-reconnect rationale.
    @ObservationIgnored var channelSendQueue: SendQueue<ChannelMessageEnvelope>?

    /// Whether a channel message retry is in progress
    @ObservationIgnored var isRetryingChannelMessage = false

    /// Whether a DM retry is in progress (prevents double-tap reentry)
    @ObservationIgnored var isRetryingMessage = false

    /// Set of `radioID`s already hydrated since the view model was created.
    /// `hydrateSendQueues(radioID:)` consults this set and short-circuits if
    /// the radio has already been hydrated — prevents double-replay across
    /// repeated `configure(...)` calls (e.g., reconnect to the same radio
    /// while the previous drain is still in flight).
    @ObservationIgnored var hydratedRadios: Set<UUID> = []

    /// In-flight hydration `Task`. Stored so tests can `await
    /// vm.hydrationTask?.value` instead of `Task.sleep(...)` and so a future
    /// teardown path can cancel hydration cleanly.
    @ObservationIgnored var hydrationTask: Task<Void, Never>?

    /// Last message previews cache
    var lastMessageCache: [UUID: MessageDTO] = [:]

    /// Preview state per message (keyed by message ID)
    var previewStates: [UUID: PreviewLoadState] = [:]

    /// Loaded preview data per message (keyed by message ID)
    var loadedPreviews: [UUID: LinkPreviewDataDTO] = [:]

    /// In-flight preview fetch tasks (prevents duplicate fetches)
    var previewFetchTasks: [UUID: Task<Void, Never>] = [:]

    /// Raw image data per message (keyed by message ID)
    var loadedImageData: [UUID: Data] = [:]

    /// Pre-decoded UIImage per message (avoids decoding in view body)
    var decodedImages: [UUID: UIImage] = [:]

    /// Pre-decoded link preview assets (single dictionary to batch Observable notifications)
    var decodedPreviewAssets: [UUID: DecodedPreviewAssets] = [:]

    /// Tracks in-flight legacy preview decode tasks to prevent duplicates
    var legacyPreviewDecodeInFlight: Set<UUID> = []

    /// Whether each image message is a GIF (computed once during decode)
    var imageIsGIF: [UUID: Bool] = [:]

    /// In-flight image fetch tasks
    var imageFetchTasks: [UUID: Task<Void, Never>] = [:]

    /// In-flight reaction sends (prevents duplicate reactions on rapid taps)
    /// Key format: "{messageID}-{emoji}"
    var inFlightReactions: Set<String> = []

    /// Cached URL detection results to avoid re-running NSDataDetector on rebuilds
    var cachedURLs: [UUID: URL?] = [:]

    // MARK: - Pagination State

    /// Whether currently fetching older messages (exposed for UI binding)
    var isLoadingOlder: Bool { renderState.isLoadingOlder }

    /// Whether more messages exist beyond what's loaded
    var hasMoreMessages: Bool { renderState.hasMoreMessages }

    /// Number of messages to fetch per page
    let pageSize = 50

    /// Total messages fetched from database (unfiltered, for accurate offset calculation)
    var totalFetchedCount: Int { renderState.totalFetchedCount }

    /// Message ID that should show the "New Messages" divider above it
    var newMessagesDividerMessageID: UUID?

    /// Whether the divider position has been computed for the current conversation
    var dividerComputed = false

    /// Minimum unread count before showing the "New Messages" divider
    private let newMessagesDividerMinUnreadCount = 10

    /// Computes the divider message ID from a fetched (unfiltered) message array.
    /// Must be called before filtering. Sets `dividerComputed = true`.
    func computeDividerPosition(from messages: [MessageDTO], unreadCount: Int) {
        guard !dividerComputed, unreadCount > newMessagesDividerMinUnreadCount else { return }
        let dividerIndex = max(0, messages.count - unreadCount)
        if dividerIndex < messages.count {
            newMessagesDividerMessageID = messages[dividerIndex].id
        }
        dividerComputed = true
    }

    // MARK: - Dependencies

    var dataStore: DataStore?
    var linkPreviewCache: (any LinkPreviewCaching)?
    var messageService: MessageService?
    var notificationService: NotificationService?
    private var channelService: ChannelService?
    private var roomServerService: RoomServerService?
    var contactService: ContactService?
    var syncCoordinator: SyncCoordinator?
    weak var appState: AppState?

    /// Contact ID currently having its favorite status toggled (for loading UI)
    var togglingFavoriteID: UUID?

    // MARK: - Initialization

    init() {}

    /// Configure with services from AppState (with link preview cache for message views)
    func configure(appState: AppState, linkPreviewCache: any LinkPreviewCaching) {
        self.appState = appState
        self.dataStore = appState.offlineDataStore
        self.messageService = appState.services?.messageService
        self.notificationService = appState.services?.notificationService
        self.channelService = appState.services?.channelService
        self.roomServerService = appState.services?.roomServerService
        self.contactService = appState.services?.contactService
        self.syncCoordinator = appState.syncCoordinator
        self.linkPreviewCache = linkPreviewCache
        self.lastSetRegionScope = .unknown
        rebindSendContext(
            dataStore: appState.offlineDataStore,
            messageService: appState.services?.messageService,
            reactionService: appState.services?.reactionService
        )
        if let radioID = appState.currentRadioID {
            hydrateSendQueues(radioID: radioID)
        }
    }

    /// Configure with services from AppState (for conversation list views that don't show previews)
    func configure(appState: AppState) {
        self.appState = appState
        self.dataStore = appState.offlineDataStore
        self.messageService = appState.services?.messageService
        self.notificationService = appState.services?.notificationService
        self.channelService = appState.services?.channelService
        self.roomServerService = appState.services?.roomServerService
        self.contactService = appState.services?.contactService
        self.syncCoordinator = appState.syncCoordinator
        self.lastSetRegionScope = .unknown
        rebindSendContext(
            dataStore: appState.offlineDataStore,
            messageService: appState.services?.messageService,
            reactionService: appState.services?.reactionService
        )
        if let radioID = appState.currentRadioID {
            hydrateSendQueues(radioID: radioID)
        }
    }

    /// Configure with services (for testing)
    func configure(
        dataStore: DataStore,
        messageService: MessageService,
        linkPreviewCache: any LinkPreviewCaching,
        activeRadioID: UUID? = nil
    ) {
        self.dataStore = dataStore
        self.messageService = messageService
        self.linkPreviewCache = linkPreviewCache
        rebindSendContext(
            dataStore: dataStore,
            messageService: messageService,
            reactionService: nil
        )
        if let activeRadioID {
            hydrateSendQueues(radioID: activeRadioID)
        }
    }

    /// Rebind the send-queue service references and lazily construct the
    /// queues on first configure. The queues themselves persist across
    /// reconnects because they capture `sendContext` by reference — only
    /// the fields mutate, not the queue instances.
    private func rebindSendContext(
        dataStore: DataStore?,
        messageService: MessageService?,
        reactionService: ReactionService?
    ) {
        sendContext.dataStore = dataStore
        sendContext.messageService = messageService
        sendContext.reactionService = reactionService

        if dmSendQueue == nil {
            dmSendQueue = makeDMSendQueue()
        }
        if channelSendQueue == nil {
            channelSendQueue = makeChannelSendQueue()
        }
    }

}

// MARK: - Environment Key

extension EnvironmentValues {
    @Entry var chatViewModel: ChatViewModel? = nil
}
