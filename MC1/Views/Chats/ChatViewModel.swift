import SwiftUI
import UIKit
import MC1Services
import OSLog
import CoreLocation

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

    /// Identifies an incoming self-mention with a per-mention sequence number so
    /// `.onChange(of:)` fires for consecutive mentions of the same message.
    /// `Equatable` is auto-synthesised from `UUID` + `UInt64`; adding a non-Equatable
    /// field would break `.onChange` propagation.
    struct MentionEvent: Equatable, Sendable {
        let messageID: UUID
        let sequence: UInt64
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

    // MARK: - Conversation Cache Storage

    /// Stored backing for the conversation-cache layer. Methods live in a
    /// dedicated extension; storage stays here because Swift requires
    /// stored properties on the type, not in extensions.
    @ObservationIgnored var cachedFavoriteConversations: [Conversation] = []
    @ObservationIgnored var cachedNonFavoriteConversations: [Conversation] = []
    @ObservationIgnored var conversationCacheValid = false
    @ObservationIgnored var urlDetectionTask: Task<Void, Never>?
    /// Bumped on every buildItems rebuild. Only the URL-detection writer
    /// checks this before mutating cachedURLs; single-row rebuilds via
    /// rebuildDisplayItem do not write cachedURLs and do not need gating.
    @ObservationIgnored var urlDetectionGeneration: UInt64 = 0
    /// Tracks the last region scope sent to the device via setFloodScope.
    @ObservationIgnored var lastSetRegionScope: RegionScopeState = .unknown

    /// Per-(radio, conversation) coordinator that owns the canonical chat
    /// state for this view model. Bound by `configure(...)` once the
    /// conversation identity is known. Two `ChatViewModel`s on the same
    /// conversation share one coordinator via the registry, so an update
    /// applied from one view is visible to the other.
    var coordinator: ChatCoordinator?

    /// Messages for the current conversation. Forwards to the bound
    /// coordinator; empty when no coordinator is bound (e.g., conversation
    /// list views).
    var messages: [MessageDTO] { coordinator?.messages ?? [] }

    /// Immutable snapshot of the timeline as the view sees it. Forwards
    /// to the bound coordinator.
    var renderState: ChatRenderState { coordinator?.renderState ?? .empty }

    /// Environment-derived inputs (six `@AppStorage` toggles, contrast,
    /// current user name) that feed `MessageItem` construction.
    /// `ChatConversationView` pushes via `applyEnvInputs(_:)` before
    /// `loadMessages` and on subsequent toggle changes.
    var envInputs: EnvInputs = .default

    /// Update env-derived inputs and trigger a full rebuild when the value
    /// changes and there are messages to rebuild. Idempotent on no-change.
    func applyEnvInputs(_ new: EnvInputs) {
        guard envInputs != new else { return }
        // When the network transitions from unavailable to available, drop the
        // sticky map-snapshot failures so renders that failed during the outage
        // retry on the next rebuild. Without this, an offline-pack miss stays
        // poisoned until a memory warning evicts the failed set.
        if envInputs.isOffline && !new.isOffline {
            MapSnapshotStore.shared.clearFailures()
        }
        envInputs = new
        guard !messages.isEmpty else { return }
        buildItems()
    }

    /// Monotonic counter bumped when a `MessageEvent` indicates the current
    /// contact (route, profile) should be re-fetched. Observed by the view.
    var contactRefreshSignal: UInt64 = 0

    /// Most recent incoming self-mention, with a per-mention sequence number
    /// so consecutive mentions of the same message still fire `.onChange`.
    var lastIncomingMention: MentionEvent?

    /// Internal mention counter; never observed by the view, so it stays out
    /// of the `@Observable` graph.
    @ObservationIgnored var mentionSequence: UInt64 = 0

    /// Stored timeline rows. The cell-content closure consumes this directly;
    /// the bubble view reads `MessageItem` fields without any per-render rebuild.
    var items: [MessageItem] { renderState.items }

    /// O(1) item-index lookup by message ID, forwarded from the render state.
    var itemIndexByID: [UUID: Int] { renderState.itemIndexByID }

    /// O(1) message lookup by ID (used by views to get full DTO when needed).
    /// Forwards to the bound coordinator.
    var messagesByID: [UUID: MessageDTO] { coordinator?.messagesByID ?? [:] }

    /// Current contact being chatted with
    var currentContact: ContactDTO?

    /// Current channel being viewed
    var currentChannel: ChannelDTO?

    /// Loading state
    var isLoading = false

    /// True once a list-level conversation fetch has completed. Gates the
    /// list-level empty-state placeholder. The per-conversation timeline
    /// has its own gate on `ChatRenderState.LoadPhase`.
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
    /// - Passive load and prefetch failures route to `errorBannerMessage` for
    ///   the non-modal banner surface.
    var sendErrorMessage: String?

    /// Error message for passive failure surfaces. Driven by `errorBanner(_:)`
    /// and rendered as a tap-to-dismiss strip between the message list and the
    /// input bar. Use this instead of `errorMessage` for failures the user did
    /// not initiate (prefetch, scroll-triggered pagination). User-initiated
    /// failures and initial-open load failures continue to surface via
    /// `errorMessage` (modal "Error" alert) or `sendErrorMessage` (modal
    /// "Unable to Send" alert).
    var errorBannerMessage: String?

    /// Message text being composed
    var composingText = ""

    /// Reentry guard for retry actions. A single `ChatViewModel` is bound to one
    /// conversation at a time (`currentChannel` xor `currentContact`), so this
    /// flag covers both DM and channel retry paths — they can never overlap.
    @ObservationIgnored var retryInFlight = false

    /// Last message previews cache
    var lastMessageCache: [UUID: MessageDTO] = [:]

    // The preview-cache block below (`previewStates`, `cachedURLs`,
    // `decodedImages`, `loadedImageData`) is per-VM state, not shared
    // across multiple VMs binding to the same `ChatCoordinator`. On iPad
    // split view each VM rebuilds its own cache; the two views can
    // diverge until the next rebuild on each side. See
    // `ChatCoordinatorRegistry.coordinator(for:)` for the accepted
    // trade-off context.

    /// Preview state per message (keyed by message ID)
    var previewStates: [UUID: PreviewLoadState] = [:]

    /// Loaded preview data per message (keyed by message ID)
    var loadedPreviews: [UUID: LinkPreviewDataDTO] = [:]

    /// In-flight preview fetch tasks (prevents duplicate fetches)
    var previewFetchTasks: [UUID: Task<Void, Never>] = [:]

    /// Total cost limit for `loadedImageData`. `NSCache` evicts entries to
    /// stay under this byte budget and also responds to system memory
    /// pressure on its own.
    private static let imageDataCacheLimitBytes = 50 * 1024 * 1024

    /// Raw image data per message (keyed by message ID). Backed by
    /// `NSCache` so memory pressure and the configured cost limit drive
    /// eviction instead of an unbounded dictionary. `@ObservationIgnored`
    /// because mutations should not trigger SwiftUI redraws — consumers
    /// read this via explicit method calls when an image is tapped.
    @ObservationIgnored
    let loadedImageData: NSCache<NSUUID, NSData> = {
        let cache = NSCache<NSUUID, NSData>()
        cache.totalCostLimit = ChatViewModel.imageDataCacheLimitBytes
        return cache
    }()

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

    /// Maps a snapshot request to the messages that show its thumbnail, so a late
    /// `resolutionStream` event rebuilds only those rows (O(matches)) instead of
    /// regex-scanning every loaded message. Populated in `makeBuildInputs`,
    /// cleared on conversation switch.
    @ObservationIgnored var mapPreviewRequestIndex: [MapSnapshotRequest: Set<UUID>] = [:]

    // MARK: - Pagination State

    /// Whether currently fetching older messages (exposed for UI binding)
    var isLoadingOlder: Bool { renderState.isLoadingOlder }

    /// Whether more messages exist beyond what's loaded
    var hasMoreMessages: Bool { renderState.hasMoreMessages }

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
    ///
    /// Positional: the divider sits `unreadCount` rows from the end. This relies on unread
    /// messages occupying the array tail, which block-at-reconnect upholds — every unread row
    /// (live or drained) takes a sortDate at or after its receive/drain time, later than any
    /// already-read row, so unread always sorts to the tail. Do not switch this to a
    /// `first(where: { !$0.isRead })` scan: per-message `isRead` is not maintained on chat open
    /// (only the unread counter is cleared), so the scan would land on the first row of the page.
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

    /// Drives receive-time prefetch of inline image dimensions and link
    /// preview metadata so message bubbles render at final size on first
    /// paint. Constructed in `configure(...)` when services are available;
    /// nil while disconnected (offline browse never receives new messages).
    @ObservationIgnored var prefetcher: InlineImagePrefetcher?

    /// Backing dimensions store for the prefetcher's image probes. Held so
    /// the subscription task can read the resolution stream without a
    /// services round-trip on every emission.
    @ObservationIgnored var inlineImageDimensionsStore: InlineImageDimensionsStore?

    /// Long-running subscription to `InlineImageDimensionsStore.resolutionStream`.
    /// On each emitted URL, every message whose body contains that URL is
    /// rebuilt so the bubble picks up the now-known `cachedAspect`.
    @ObservationIgnored var dimensionResolutionTask: Task<Void, Never>?

    /// Long-running subscription to `MapSnapshotStore.shared.resolutionStream`.
    /// Started once (the store is a process-lifetime singleton).
    @ObservationIgnored var snapshotResolutionTask: Task<Void, Never>?

    /// Per-instance override of the receive-time prefetch timeout. Production
    /// callers leave this at `defaultPrefetchTimeout` (3s); tests can shorten
    /// it to bound their wall-clock budget.
    @ObservationIgnored var prefetchTimeout: Duration = ChatViewModel.defaultPrefetchTimeout

    /// Contact ID currently having its favorite status toggled (for loading UI)
    var togglingFavoriteID: UUID?

    // MARK: - Initialization

    init() {}

    /// Forwards a map-thumbnail tap to the same navigation sink the coordinate
    /// text link uses. `appState` is a `weak` optional; if nil, the tap is a
    /// no-op (the always-present text link remains the baseline).
    func navigateToMap(_ coordinate: CLLocationCoordinate2D) {
        appState?.navigation.navigateToMap(coordinate: coordinate)
    }

    /// Conversation-aware configure. Resolves the per-conversation
    /// `ChatCoordinator` from the registry before the first view body
    /// evaluates so the bound coordinator is observed by SwiftUI from
    /// frame zero. Pass `nil` for views that do not render a single
    /// conversation (chat list, info sheet).
    func configure(
        appState: AppState,
        linkPreviewCache: any LinkPreviewCaching,
        conversation: ChatConversationType?
    ) {
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
        configurePrefetcher(appState: appState, linkPreviewCache: linkPreviewCache)
        bindCoordinator(appState: appState, conversation: conversation)
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
        bindCoordinator(appState: appState, conversation: nil)
    }

    private func bindCoordinator(appState: AppState, conversation: ChatConversationType?) {
        guard let conversation else { return }
        guard let registry = appState.ensureChatCoordinatorRegistry() else { return }
        let id: ChatConversationID
        switch conversation {
        case .dm(let contact):
            id = .dm(radioID: contact.radioID, contactID: contact.id)
        case .channel(let channel):
            id = .channel(radioID: channel.radioID, channelIndex: channel.index)
        }
        let resolved = registry.coordinator(for: id)
        // The most recently bound view model owns the per-ID rebuild hook
        // the coordinator invokes after `applyReloadedIDs`. With two view
        // models on the same conversation (iPad split view) the rendered
        // state stays consistent because both observe the shared
        // coordinator's `renderState` — only the per-view-model snapshot
        // inputs (preview state, decoded images) come from the bound
        // rebuilder.
        resolved.renderItemRebuilder = { [weak self] messageID in
            self?.rebuildDisplayItem(for: messageID)
        }
        resolved.renderStateInvalidated = { [weak self] in
            self?.handleRenderStateInvalidated()
        }
        coordinator = resolved
    }

    private func handleRenderStateInvalidated() {
        buildItems()
    }

    /// Build the receive-time prefetcher and start (or restart) the
    /// dimension-resolution subscription. Called from both `configure(...)`
    /// variants. No-op when services are unavailable (offline browse).
    private func configurePrefetcher(
        appState: AppState,
        linkPreviewCache: any LinkPreviewCaching
    ) {
        // The snapshot-resolution stream is a process-lifetime singleton with no
        // dependency on `services`, so subscribe once regardless of connection
        // state — offline browse of cached coordinate messages still needs late
        // snapshot resolutions to refresh their thumbnails.
        if snapshotResolutionTask == nil {
            snapshotResolutionTask = Task { [weak self] in
                for await request in MapSnapshotStore.shared.resolutionStream() {
                    guard !Task.isCancelled else { return }
                    self?.handleSnapshotResolution(request)
                }
            }
        }

        guard let services = appState.services else {
            prefetcher = nil
            inlineImageDimensionsStore = nil
            dimensionResolutionTask?.cancel()
            dimensionResolutionTask = nil
            return
        }
        let store = services.inlineImageDimensionsStore
        inlineImageDimensionsStore = store
        prefetcher = InlineImagePrefetcher(
            imageCache: InlineImageCache.shared,
            linkPreviewCache: linkPreviewCache,
            dimensionsStore: store,
            dataStore: services.dataStore
        )
        startObservingDimensionResolutions(store: store)
    }

    private func startObservingDimensionResolutions(store: InlineImageDimensionsStore) {
        dimensionResolutionTask?.cancel()
        dimensionResolutionTask = Task { [weak self] in
            for await resolvedURL in store.resolutionStream {
                guard !Task.isCancelled else { return }
                await self?.handleDimensionResolution(resolvedURL)
            }
        }
    }

    deinit {
        dimensionResolutionTask?.cancel()
        snapshotResolutionTask?.cancel()
    }

    static func copyForEnqueueFailure(_ error: Error) -> String {
        if case ChatSendQueueServiceError.notConnected = error {
            return L10n.Chats.Chats.Alert.UnableToSend.message
        }
        return L10n.Chats.Chats.Error.sendQueuePersistFailed
    }

}

// MARK: - Environment Key

extension EnvironmentValues {
    @Entry var chatViewModel: ChatViewModel? = nil
}
