import CoreLocation
import MC1Services
import OSLog
import SwiftUI
import UIKit

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
  struct MentionEvent: Equatable {
    let messageID: UUID
    let sequence: UInt64
  }

  // MARK: - Properties

  let logger = Logger(subsystem: "com.mc1", category: "ChatViewModel")

  /// Observed source of truth the list diffs against. Stays observed because that
  /// observation drives the diff; assigned only by `recomputeSnapshot()`.
  var conversationSnapshot: ConversationSnapshot = .empty

  /// Cheap Equatable surrogate for `onChange(of:)`; bumped only in `recomputeSnapshot()`.
  var snapshotGeneration: Int = 0

  /// Non-observed fetch buffer feeding `recomputeSnapshot()`; `internal` so tests can seed it.
  @ObservationIgnored var conversations: [ContactDTO] = []

  /// All contacts for mention autocomplete (includes contacts without messages)
  var allContacts: [ContactDTO] = []

  /// `loweredName -> nickname` for channel sender matching, rebuilt whenever
  /// `allContacts` changes so per-message resolution stays O(1).
  var nicknamesByLoweredName: [String: String] = [:]

  /// Synthetic contacts for channel senders not in contacts
  var channelSenders: [ContactDTO] = []

  /// O(1) lookup for channel sender names
  var channelSenderNames: Set<String> = []

  /// Sender name → latest message timestamp (for mention sort order)
  var channelSenderOrder: [String: UInt32] = [:]

  /// O(1) lookup for contact names
  var contactNameSet: Set<String> = []

  /// Current channels with messages. Non-observed fetch buffer feeding `recomputeSnapshot()`.
  @ObservationIgnored var channels: [ChannelDTO] = []

  /// Current room sessions. Non-observed fetch buffer feeding `recomputeSnapshot()`.
  @ObservationIgnored var roomSessions: [RemoteNodeSessionDTO] = []

  /// Ids hidden optimistically on delete until a DB-confirming reload drops them.
  /// `reconcilePendingRemovals()` self-heals this once the fetch confirms the row is gone.
  @ObservationIgnored var pendingRemovalIDs: Set<UUID> = []

  /// Rows showing a delete spinner during a radio-backed delete (channel clear, room leave).
  /// Observed presentation state read by the rows; never filters the snapshot. Distinct from
  /// `pendingRemovalIDs`, the optimistic-hide mask.
  var deletingIDs: Set<UUID> = []

  /// Cancel-and-replace token for the serialized reload funnel. No view reads it.
  @ObservationIgnored var reloadTask: Task<Void, Never>?

  #if DEBUG
    /// Test-only interleave hook, awaited once mid-reload so a test can suspend reload #1
    /// between fetches and commit reload #2 first. Compiled out of release builds.
    @ObservationIgnored var reloadInterleaveHook: (@MainActor () async -> Void)?
  #endif

  // MARK: - Conversation Cache Storage

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
  var messages: [MessageDTO] {
    coordinator?.messages ?? []
  }

  /// Immutable snapshot of the timeline as the view sees it. Forwards
  /// to the bound coordinator.
  var renderState: ChatRenderState {
    coordinator?.renderState ?? .empty
  }

  /// Environment-derived inputs (seven `@AppStorage` toggles, contrast,
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
    if envInputs.isOffline, !new.isOffline {
      MapSnapshotStore.shared.clearFailures()
    }
    envInputs = new
    // The environment feeds every formatting input, so its cached output is
    // now stale for all rows and must be rebuilt under the new appearance.
    bake.formattedTextCache.removeAll()
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
  var items: [MessageItem] {
    renderState.items
  }

  /// O(1) item-index lookup by message ID, forwarded from the render state.
  var itemIndexByID: [UUID: Int] {
    renderState.itemIndexByID
  }

  /// O(1) message lookup by ID (used by views to get full DTO when needed).
  /// Forwards to the bound coordinator.
  var messagesByID: [UUID: MessageDTO] {
    coordinator?.messagesByID ?? [:]
  }

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

  /// Scope preferences (master + per-conversation-type auto-resolve) read by
  /// the image fetch sites so inline images honor the same DM/channel gate the
  /// card path applies inside `LinkPreviewCache`. Internal so tests can inject
  /// a scratch `UserDefaults` suite instead of leaking into `.standard`.
  var linkPreviewPreferences = LinkPreviewPreferences()

  /// Per-message bake state (preview/image caches, divider) and the item-build
  /// pipeline reading it. Not observed: redraw is decided by `MessageItem`.
  ///
  /// Per-VM state, not shared across view models bound to the same
  /// `ChatCoordinator`. On iPad split view each rebuilds its own caches, so the
  /// two can diverge until the next rebuild on each side;
  /// `ChatCoordinatorRegistry.coordinator(for:)` carries the trade-off context.
  @ObservationIgnored let bake = ChatMessageBakeState()

  /// In-flight preview fetch tasks (prevents duplicate fetches)
  var previewFetchTasks: [UUID: Task<Void, Never>] = [:]

  /// In-flight image fetch tasks
  var imageFetchTasks: [UUID: Task<Void, Never>] = [:]

  /// In-flight reaction sends (prevents duplicate reactions on rapid taps)
  /// Key format: "{messageID}-{emoji}"
  var inFlightReactions: Set<String> = []

  // MARK: - Pagination State

  /// Whether currently fetching older messages (exposed for UI binding)
  var isLoadingOlder: Bool {
    renderState.isLoadingOlder
  }

  /// Whether more messages exist beyond what's loaded
  var hasMoreMessages: Bool {
    renderState.hasMoreMessages
  }

  /// Total messages fetched from database (unfiltered, for accurate offset calculation)
  var totalFetchedCount: Int {
    renderState.totalFetchedCount
  }

  /// Snapshot of observed contact tables for the item bake.
  func currentSenderTables() -> ChatSenderTables {
    ChatSenderTables(contacts: allContacts, nicknamesByLoweredName: nicknamesByLoweredName)
  }

  // MARK: - Dependencies

  /// Groups all provider closures for the `configure` call. Provider closures
  /// are re-evaluated at every use so a disconnect (or a reconnect's fresh
  /// per-connection services) is picked up live; a nil read means disconnected.
  struct Dependencies {
    var dataStore: @MainActor () -> DataStore?
    var messageService: @MainActor () -> MessageService?
    var notificationService: @MainActor () -> NotificationService?
    var channelService: @MainActor () -> ChannelService?
    var roomServerService: @MainActor () -> RoomServerService?
    var contactService: @MainActor () -> ContactService?
    var syncCoordinator: @MainActor () -> SyncCoordinator?
    var connectionState: @MainActor () -> DeviceConnectionState
    var connectedDevice: @MainActor () -> DeviceDTO?
    var currentRadioID: @MainActor () -> UUID?
    var session: @MainActor () -> MeshCoreSession?
    var reactionService: @MainActor () -> ReactionService?
    var chatSendQueueService: @MainActor () -> ChatSendQueueService?
    var inlineImageDimensionsStore: @MainActor () -> InlineImageDimensionsStore?
    var prefetchDataStore: @MainActor () -> (any PersistenceStoreProtocol)?
  }

  @ObservationIgnored private var dataStoreProvider: @MainActor () -> DataStore? = { nil }
  var dataStore: DataStore? {
    dataStoreProvider()
  }

  @ObservationIgnored private var messageServiceProvider: @MainActor () -> MessageService? = { nil }
  var messageService: MessageService? {
    messageServiceProvider()
  }

  @ObservationIgnored private var notificationServiceProvider: @MainActor () -> NotificationService? = { nil }
  var notificationService: NotificationService? {
    notificationServiceProvider()
  }

  @ObservationIgnored private var channelServiceProvider: @MainActor () -> ChannelService? = { nil }
  private var channelService: ChannelService? {
    channelServiceProvider()
  }

  @ObservationIgnored private var roomServerServiceProvider: @MainActor () -> RoomServerService? = { nil }
  private var roomServerService: RoomServerService? {
    roomServerServiceProvider()
  }

  @ObservationIgnored private var contactServiceProvider: @MainActor () -> ContactService? = { nil }
  var contactService: ContactService? {
    contactServiceProvider()
  }

  @ObservationIgnored private var syncCoordinatorProvider: @MainActor () -> SyncCoordinator? = { nil }
  var syncCoordinator: SyncCoordinator? {
    syncCoordinatorProvider()
  }

  @ObservationIgnored var connectionStateProvider: @MainActor () -> DeviceConnectionState = { .disconnected }
  @ObservationIgnored var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  @ObservationIgnored var currentRadioIDProvider: @MainActor () -> UUID? = { nil }
  @ObservationIgnored var sessionProvider: @MainActor () -> MeshCoreSession? = { nil }
  @ObservationIgnored var reactionServiceProvider: @MainActor () -> ReactionService? = { nil }
  @ObservationIgnored var chatSendQueueServiceProvider: @MainActor () -> ChatSendQueueService? = { nil }

  var inlineImageDimensionsStore: InlineImageDimensionsStore? {
    bake.inlineImageDimensionsStore
  }

  @ObservationIgnored private var prefetchDataStoreProvider: @MainActor () -> (any PersistenceStoreProtocol)? = { nil }

  /// App-lifetime cache for link previews. Passed directly from the environment;
  /// held for the screen's lifetime and used in `fetchPreview` and `manualFetchPreview`.
  @ObservationIgnored var linkPreviewCache: (any LinkPreviewCaching)?

  /// Navigation sink for map-thumbnail taps; nil makes the tap a no-op
  /// (the always-present text link remains the baseline).
  @ObservationIgnored var onNavigateToMap: ((CLLocationCoordinate2D) -> Void)?

  /// Drives receive-time prefetch of inline image dimensions and link
  /// preview metadata so message bubbles render at final size on first
  /// paint. Constructed in `configure(...)` when services are available;
  /// nil while disconnected (offline browse never receives new messages).
  @ObservationIgnored var prefetcher: InlineImagePrefetcher?

  /// Long-running subscription to `InlineImageDimensionsStore.resolutionUpdates()`.
  /// On each emitted URL, every message whose body contains that URL is
  /// rebuilt so the bubble picks up the now-known `cachedAspect`. Speculative
  /// primes use `ChatTimelinePrimer` and never subscribe; this path is for the
  /// interactive conversation only.
  @ObservationIgnored var dimensionResolutionTask: Task<Void, Never>?

  /// Long-running subscription to `MapSnapshotStore.shared.resolutionStream`.
  /// Started once (the store is a process-lifetime singleton). Interactive only;
  /// `ChatTimelinePrimer` does not subscribe.
  @ObservationIgnored var snapshotResolutionTask: Task<Void, Never>?

  /// Per-instance override of the receive-time prefetch timeout. Production
  /// callers leave this at `defaultPrefetchTimeout` (3s); tests can shorten
  /// it to bound their wall-clock budget.
  @ObservationIgnored var prefetchTimeout: Duration = ChatViewModel.defaultPrefetchTimeout

  /// Write capability for the bound coordinator, minted by `bindWriter` in
  /// `bindCoordinator`. Every timeline mutation goes through this; when a
  /// newer owner binds the same coordinator (live open superseding a prime,
  /// BFU scene rebuild), this writer goes stale and its writes no-op, so a
  /// cold view model can never bake its state over the live timeline.
  /// Reads keep using `coordinator` directly. `nil` when unbound.
  @ObservationIgnored var timelineWriter: ChatTimelineWriter?

  /// Contact ID currently having its favorite status toggled (for loading UI)
  var togglingFavoriteID: UUID?

  // MARK: - Initialization

  init() {}

  /// Forwards a map-thumbnail tap to the same navigation sink the coordinate
  /// text link uses. `onNavigateToMap` is optional; if nil, the tap is a
  /// no-op (the always-present text link remains the baseline).
  func navigateToMap(_ coordinate: CLLocationCoordinate2D) {
    onNavigateToMap?(coordinate)
  }

  /// Configure the chat view model for a conversation list or a specific conversation.
  /// Conversation views also pass `linkPreviewCache`, `chatCoordinatorRegistry`, and
  /// `conversation` so the per-conversation `ChatCoordinator` is bound before the
  /// first view body evaluates; list views omit them.
  func configure(
    dependencies: Dependencies,
    onNavigateToMap: ((CLLocationCoordinate2D) -> Void)?,
    linkPreviewCache: (any LinkPreviewCaching)?,
    chatCoordinatorRegistry: ChatCoordinatorRegistry?,
    conversation: ChatConversationType?
  ) {
    dataStoreProvider = dependencies.dataStore
    messageServiceProvider = dependencies.messageService
    notificationServiceProvider = dependencies.notificationService
    channelServiceProvider = dependencies.channelService
    roomServerServiceProvider = dependencies.roomServerService
    contactServiceProvider = dependencies.contactService
    syncCoordinatorProvider = dependencies.syncCoordinator
    connectionStateProvider = dependencies.connectionState
    connectedDeviceProvider = dependencies.connectedDevice
    currentRadioIDProvider = dependencies.currentRadioID
    sessionProvider = dependencies.session
    reactionServiceProvider = dependencies.reactionService
    chatSendQueueServiceProvider = dependencies.chatSendQueueService
    bake.bindInlineImageDimensionsStore(dependencies.inlineImageDimensionsStore)
    prefetchDataStoreProvider = dependencies.prefetchDataStore
    self.onNavigateToMap = onNavigateToMap
    lastSetRegionScope = .unknown
    if let linkPreviewCache {
      self.linkPreviewCache = linkPreviewCache
      configurePrefetcher(
        linkPreviewCache: linkPreviewCache,
        dimensionsStore: dependencies.inlineImageDimensionsStore(),
        prefetchDataStore: prefetchDataStoreProvider()
      )
    }
    bindCoordinator(registry: chatCoordinatorRegistry, conversation: conversation)
  }

  /// Eagerly attaches the shared coordinator so a warm (prefetched or previously
  /// opened) conversation renders its messages on the first frame, before the
  /// load task runs. Sets only this view model's `coordinator` reference — never
  /// the coordinator's rebuild hooks, which belong to the persistent view model
  /// and are installed by `configure`/`bindCoordinator`. That omission is what
  /// makes it safe to call from `init`, where transient view-model instances may
  /// be created and discarded.
  func attachCoordinator(_ coordinator: ChatCoordinator) {
    self.coordinator = coordinator
  }

  private func bindCoordinator(registry: ChatCoordinatorRegistry?, conversation: ChatConversationType?) {
    guard let conversation else { return }
    guard let registry else { return }
    let resolved = registry.coordinator(for: conversation.coordinatorID)
    // The interactive view model owns both the rebuild hooks and the write
    // capability; `bindWriter` installs them as one atomic act. An
    // `.interactive` bind always succeeds and revokes any prior writer (a
    // stale prime's in-flight bakes then no-op at the coordinator). With two
    // observers on the same conversation the rendered state stays consistent
    // because both read the shared coordinator's `renderState`; only the
    // current writer bakes items.
    timelineWriter = resolved.bindWriter(
      owner: self,
      role: .interactive,
      renderItemRebuilder: { [weak self] messageID in
        self?.rebuildDisplayItem(for: messageID)
      },
      renderStateInvalidated: { [weak self] in
        self?.handleRenderStateInvalidated()
      }
    )
    coordinator = resolved
  }

  private func handleRenderStateInvalidated() {
    buildItems()
  }

  #if DEBUG
    /// Test seam mirroring `bindCoordinator` for suites that construct a
    /// coordinator directly instead of resolving one through a registry:
    /// installs the interactive writer and the rebuild hooks in one act,
    /// exactly like a live open.
    func bindCoordinatorForTesting(_ coordinator: ChatCoordinator) {
      timelineWriter = coordinator.bindWriter(
        owner: self,
        role: .interactive,
        renderItemRebuilder: { [weak self] messageID in
          self?.rebuildDisplayItem(for: messageID)
        },
        renderStateInvalidated: { [weak self] in
          self?.handleRenderStateInvalidated()
        }
      )
      self.coordinator = coordinator
    }
  #endif

  /// Build the receive-time prefetcher and start (or restart) the
  /// dimension-resolution subscription. Called from `configure(...)` when a
  /// `linkPreviewCache` is supplied. The per-connection inputs are nil while
  /// disconnected (offline browse), which tears the prefetcher down.
  private func configurePrefetcher(
    linkPreviewCache: any LinkPreviewCaching,
    dimensionsStore: InlineImageDimensionsStore?,
    prefetchDataStore: (any PersistenceStoreProtocol)?
  ) {
    // The snapshot-resolution stream is a process-lifetime singleton with no
    // dependency on per-connection services, so subscribe once regardless of
    // connection state: offline browse of cached coordinate messages still
    // needs late snapshot resolutions to refresh their thumbnails.
    if snapshotResolutionTask == nil {
      snapshotResolutionTask = Task { [weak self] in
        for await request in MapSnapshotStore.shared.resolutionStream() {
          guard !Task.isCancelled else { return }
          self?.handleSnapshotResolution(request)
        }
      }
    }

    guard let dimensionsStore, let prefetchDataStore else {
      prefetcher = nil
      dimensionResolutionTask?.cancel()
      dimensionResolutionTask = nil
      return
    }
    prefetcher = InlineImagePrefetcher(
      imageCache: InlineImageCache.shared,
      linkPreviewCache: linkPreviewCache,
      dimensionsStore: dimensionsStore,
      dataStore: prefetchDataStore
    )
    startObservingDimensionResolutions(store: dimensionsStore)
  }

  private func startObservingDimensionResolutions(store: InlineImageDimensionsStore) {
    dimensionResolutionTask?.cancel()
    dimensionResolutionTask = Task { [weak self] in
      for await resolvedURL in store.resolutionUpdates() {
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
  @Entry var chatViewModel: ChatViewModel?
}
