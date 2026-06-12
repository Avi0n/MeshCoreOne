// SyncCoordinator.swift
import Foundation

// MARK: - Sync Types

/// Current state of the sync coordinator
public enum SyncState: Sendable, Equatable {
    case idle
    case syncing(progress: SyncProgress)
    case synced
    case failed(SyncCoordinatorError)

    /// Whether currently syncing
    public var isSyncing: Bool {
        if case .syncing = self { return true }
        return false
    }

    public static func == (lhs: SyncState, rhs: SyncState) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle): return true
        case (.synced, .synced): return true
        case (.syncing(let a), .syncing(let b)): return a == b
        case (.failed, .failed): return true  // Simplified equality
        default: return false
        }
    }
}

/// Progress information during sync
public struct SyncProgress: Sendable, Equatable {
    public let phase: SyncPhase
    public let current: Int
    public let total: Int

    public init(phase: SyncPhase, current: Int, total: Int) {
        self.phase = phase
        self.current = current
        self.total = total
    }
}

/// Phases of the sync process
public enum SyncPhase: Sendable, Equatable {
    case contacts
    case channels
    case messages
}

/// Phase-level sync outcome.
public enum SyncPhaseStatus: Sendable, Equatable {
    case clean
    case partial
    case skipped
    case failed(String)

    public var isClean: Bool {
        self == .clean
    }
}

/// Structured result for an initial or full sync.
public struct FullSyncResult: Sendable, Equatable {
    public let contacts: SyncPhaseStatus
    public let channels: SyncPhaseStatus
    public let messages: SyncPhaseStatus
    public let channelRetryIndices: [UInt8]

    public var isConnectionUsable: Bool {
        contacts == .clean
    }

    public init(
        contacts: SyncPhaseStatus,
        channels: SyncPhaseStatus,
        messages: SyncPhaseStatus,
        channelRetryIndices: [UInt8] = []
    ) {
        self.contacts = contacts
        self.channels = channels
        self.messages = messages
        self.channelRetryIndices = channelRetryIndices
    }

    public static let skipped = FullSyncResult(
        contacts: .skipped,
        channels: .skipped,
        messages: .skipped
    )
}

/// Errors from SyncCoordinator operations
public enum SyncCoordinatorError: Error, Sendable {
    case notConnected
    case syncFailed(String)
    case alreadySyncing
}

extension SyncCoordinatorError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .notConnected: "Not connected to device."
        case .syncFailed(let msg): "Sync failed: \(msg)"
        case .alreadySyncing: "A sync is already in progress."
        }
    }
}

// MARK: - SyncCoordinator Actor

/// Coordinates data synchronization between MeshCore device and local database.
///
/// SyncCoordinator owns:
/// - Handler wiring (before event monitoring starts)
/// - Event monitoring lifecycle
/// - Full sync (contacts, channels, messages)
/// - UI refresh notifications
public actor SyncCoordinator {

    // MARK: - Logging

    let logger = PersistentLogger(subsystem: "com.mc1", category: "SyncCoordinator")

    /// Actor-local guard against concurrent sync execution.
    /// Checked and set synchronously (no `await`) to eliminate the TOCTOU window
    /// that existed when guarding via the `@MainActor`-isolated `state` property.
    var isSyncInProgress = false

    /// Cached blocked names (contacts + channel senders) for O(1) lookup in message handlers
    private var blockedNames: Set<String> = []

    /// Tracks channel indices received for slots with no local channel (notifications suppressed)
    /// in this connection session.
    var unresolvedChannelIndices: Set<UInt8> = []
    var lastUnresolvedChannelSummaryAt: Date?
    let unresolvedChannelSummaryIntervalSeconds: TimeInterval = 60

    /// Timestamp window size (in seconds) for matching reactions to messages.
    /// Allows for clock drift and delayed delivery within a 5-minute window.
    let reactionTimestampWindowSeconds: UInt32 = 300

    // MARK: - Observable State (@MainActor for SwiftUI)

    /// Current sync state
    @MainActor public private(set) var state: SyncState = .idle

    /// Incremented when contacts data changes
    @MainActor public private(set) var contactsVersion: Int = 0

    /// Incremented when conversations data changes
    @MainActor public private(set) var conversationsVersion: Int = 0

    /// Last successful sync date
    @MainActor public private(set) var lastSyncDate: Date?

    /// Called when channel sync completes with zero errors (including retries).
    /// Used by ConnectionManager to track clean channel completions for smart resync.
    /// Installed by `ConnectionManager.wireCleanChannelSyncCallback`.
    var onCleanChannelSync: (@Sendable (_ radioID: UUID) async -> Void)?

    /// Called when a channel sync attempt starts, clean or partial.
    /// Used by ConnectionManager to cool down immediate channel retry loops.
    /// Installed by `ConnectionManager.wireCleanChannelSyncCallback`.
    var onChannelSyncAttempted: (@Sendable (_ radioID: UUID) async -> Void)?

    /// Sets the callback for clean channel sync completion.
    public func setCleanChannelSyncCallback(_ callback: @escaping @Sendable (_ radioID: UUID) async -> Void) {
        onCleanChannelSync = callback
    }

    /// Sets the callback for any channel sync attempt.
    public func setChannelSyncAttemptedCallback(_ callback: @escaping @Sendable (_ radioID: UUID) async -> Void) {
        onChannelSyncAttempted = callback
    }

    /// Callback when non-message sync activity starts.
    /// Installed by `ConnectionUIState.wireCallbacks` via `setSyncActivityCallbacks`.
    var onSyncActivityStarted: (@Sendable () async -> Void)?

    /// Callback when non-message sync activity ends.
    /// Installed by `ConnectionUIState.wireCallbacks` via `setSyncActivityCallbacks`.
    var onSyncActivityEnded: (@Sendable (_ succeeded: Bool) async -> Void)?

    /// Tracks whether onSyncActivityEnded has been called for the current sync cycle.
    /// Prevents double-callback when disconnect occurs mid-sync (both onDisconnected
    /// and error path would otherwise call onSyncActivityEnded).
    var hasEndedSyncActivity = true

    /// Watchdog task that force-clears notification suppression after 120s.
    /// Prevents stuck suppression if sync completes abnormally without clearing it.
    private var suppressionWatchdogTask: Task<Void, Never>?

    /// Callback when sync phase changes (for SwiftUI observation).
    /// Installed by `ConnectionUIState.wireCallbacks` via `setSyncActivityCallbacks`.
    @MainActor private var onPhaseChanged: (@Sendable @MainActor (_ phase: SyncPhase?) -> Void)?

    /// Multicast broadcaster for data-change and incoming-message events.
    /// Producers yield synchronously from any isolation; consumers subscribe
    /// via `dataEvents()`.
    nonisolated let dataEventBroadcaster = EventBroadcaster<SyncDataEvent>()

    /// Task consuming `AdvertisementService.events()` for ongoing contact
    /// discovery. Started by `startDiscoveryEventMonitoring` only after the
    /// initial sync so adverts arriving during sync do not spam notifications;
    /// cancelled by `ServiceContainer.tearDown()`.
    var discoveryEventsTask: Task<Void, Never>?

    // MARK: - Test Seams

    #if DEBUG
    /// Test override for `performResync`. When set, bypasses the real sync path.
    var performResyncOverride: ((_ radioID: UUID, _ dependencies: SyncDependencies) async -> Bool)?

    /// Sets the test override for `performResync`.
    public func setPerformResyncOverride(_ override: @escaping @Sendable (_ radioID: UUID, _ dependencies: SyncDependencies) async -> Bool) {
        performResyncOverride = override
    }
    #endif

    // MARK: - Initialization

    public init() {}

    // MARK: - State Setters

    @MainActor
    func setState(_ newState: SyncState) {
        state = newState
        if case .syncing(let progress) = newState {
            onPhaseChanged?(progress.phase)
        } else {
            onPhaseChanged?(nil)
        }
    }

    @MainActor
    func setLastSyncDate(_ date: Date) {
        lastSyncDate = date
    }

    /// Sets callbacks for sync activity tracking (used by UI to show syncing pill)
    /// Only called for contacts and channels phases, NOT for messages.
    public func setSyncActivityCallbacks(
        onStarted: @escaping @Sendable () async -> Void,
        onEnded: @escaping @Sendable (_ succeeded: Bool) async -> Void,
        onPhaseChanged: @escaping @Sendable @MainActor (_ phase: SyncPhase?) -> Void
    ) async {
        onSyncActivityStarted = onStarted
        onSyncActivityEnded = onEnded
        await MainActor.run { self.onPhaseChanged = onPhaseChanged }
    }

    // MARK: - Data Events

    /// Returns a fresh stream of data-change and incoming-message events.
    /// Registration is synchronous, so events yielded after this call are
    /// never dropped. Consumers must re-subscribe per connection because the
    /// owning `ServiceContainer` is rebuilt on every connection.
    public nonisolated func dataEvents() -> AsyncStream<SyncDataEvent> {
        dataEventBroadcaster.subscribe()
    }

    /// Ends every `dataEvents()` subscriber's for-await loop. Called by
    /// `ServiceContainer.tearDown()` so consumer tasks release the service
    /// references they hold.
    nonisolated func finishDataEvents() {
        dataEventBroadcaster.finish()
    }

    // MARK: - Sync Activity Tracking

    /// Calls onSyncActivityEnded at most once per sync cycle.
    /// Guards against double-callback when disconnect occurs mid-sync.
    func endSyncActivityOnce(succeeded: Bool = false) async {
        guard !hasEndedSyncActivity else { return }
        hasEndedSyncActivity = true
        logger.info("[Sync] Calling onSyncActivityEnded (succeeded: \(succeeded))")
        await onSyncActivityEnded?(succeeded)
    }

    /// Called by ConnectionManager when a resync loop starts.
    /// Increments the activity count so "Syncing" pill stays visible across retries.
    func beginResyncActivity() async {
        await onSyncActivityStarted?()
    }

    /// Called by ConnectionManager when a resync loop ends (success or exhausted retries).
    /// Decrements the activity count. Only triggers "Ready" toast on success.
    func endResyncActivity(succeeded: Bool) async {
        await onSyncActivityEnded?(succeeded)
    }

    // MARK: - Notification Suppression Watchdog

    func startSuppressionWatchdog(notificationService: NotificationService) {
        suppressionWatchdogTask?.cancel()
        suppressionWatchdogTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(120))
            guard !Task.isCancelled, let self else { return }
            let isSuppressing = await notificationService.isSuppressingNotifications
            guard isSuppressing else { return }
            self.logger.warning("[Sync] Notification suppression watchdog fired after 120s - force clearing")
            await MainActor.run {
                notificationService.isSuppressingNotifications = false
            }
        }
    }

    func cancelSuppressionWatchdog() {
        suppressionWatchdogTask?.cancel()
        suppressionWatchdogTask = nil
    }

    // MARK: - Notifications

    /// Notify that contacts data changed (triggers UI refresh)
    @MainActor
    public func notifyContactsChanged() {
        logger.info("notifyContactsChanged: version \(self.contactsVersion) → \(self.contactsVersion + 1)")
        contactsVersion += 1
        dataEventBroadcaster.yield(.contactsChanged)
    }

    /// Notify that conversations data changed (triggers UI refresh)
    @MainActor
    public func notifyConversationsChanged() {
        conversationsVersion += 1
        dataEventBroadcaster.yield(.conversationsChanged)
    }

    // MARK: - Blocked Contacts Cache

    /// Refresh the blocked names cache from the data store (contacts + channel senders)
    public func refreshBlockedContactsCache(radioID: UUID, dataStore: any ContactPersisting) async {
        do {
            let blockedContacts = try await dataStore.fetchBlockedContacts(radioID: radioID)
            let blockedSenders = try await dataStore.fetchBlockedChannelSenders(radioID: radioID)
            blockedNames = Set(blockedContacts.map(\.name))
                .union(Set(blockedSenders.map(\.name)))
            logger.debug("Refreshed blocked names cache: \(self.blockedNames.count) entries")
        } catch {
            logger.error("Failed to refresh blocked names cache: \(error)")
            blockedNames = []
        }
    }

    /// Check if a sender name is blocked (O(1) lookup)
    public func isBlockedSender(_ name: String?) -> Bool {
        guard let name else { return false }
        return blockedNames.contains(name)
    }

    /// Returns a snapshot of blocked sender names for synchronous filtering
    public func blockedSenderNames() -> Set<String> {
        blockedNames
    }

    /// Delete any channel messages from blocked senders still in the DB.
    /// Runs on every connection. After the first pass, delete queries match zero rows
    /// and are effectively free (indexed predicate, no mutations). This handles legacy
    /// data from app versions that filtered at read time instead of deleting at block time.
    func deleteBlockedSenderMessages(radioID: UUID, dataStore: any PersistenceStoreProtocol) async {
        let names = blockedNames
        guard !names.isEmpty else { return }

        for name in names {
            try? await dataStore.deleteChannelMessages(fromSender: name, radioID: radioID)
        }
    }

    // MARK: - Timestamp Correction

    /// Maximum acceptable time in the future for a sender timestamp (5 minutes)
    private static let timestampToleranceFuture: TimeInterval = 5 * 60

    /// Maximum acceptable time in the past for a sender timestamp (6 months)
    private static let timestampTolerancePast: TimeInterval = 6 * 30 * 24 * 60 * 60

    /// Corrects invalid timestamps from senders with broken clocks.
    ///
    /// MeshCore protocol does not specify timestamp validation. This is a client-side
    /// policy to prevent timeline corruption when devices have severely incorrect clocks
    /// (a common issue per MeshCore FAQ 6.1, 6.2). Original timestamps are preserved
    /// for ACK deduplication (per payloads.md:65).
    ///
    /// Returns the corrected timestamp and whether correction was applied.
    /// Timestamps are considered invalid if:
    /// - More than 5 minutes in the future (relative to receive time)
    /// - More than 6 months in the past (relative to receive time)
    ///
    /// - Parameters:
    ///   - timestamp: The sender's claimed timestamp
    ///   - receiveTime: When the message was received (defaults to now)
    /// - Returns: Tuple of (corrected timestamp, was corrected flag)
    nonisolated static func correctTimestampIfNeeded(
        _ timestamp: UInt32,
        receiveTime: Date = Date()
    ) -> (correctedTimestamp: UInt32, wasCorrected: Bool) {
        let receiveSeconds = receiveTime.timeIntervalSince1970
        let timestampSeconds = TimeInterval(timestamp)

        let isTooFarInFuture = timestampSeconds > receiveSeconds + timestampToleranceFuture
        let isTooFarInPast = timestampSeconds < receiveSeconds - timestampTolerancePast

        if isTooFarInFuture || isTooFarInPast {
            return (UInt32(receiveSeconds), true)
        }
        return (timestamp, false)
    }

    /// Computes the persisted sort date for an incoming message based on its delivery context.
    ///
    /// Live messages sort by receive time so a just-arrived message stays at the bottom of the
    /// transcript. Backlog messages drained during sync sort by the drain `anchor`, so a whole
    /// batch lands as one contiguous block at delivery time — recent, never buried — ordered
    /// within the block by the secondary `timestamp` fetch key. The sort key no longer reads
    /// sender time, so a skewed sender clock cannot scatter backlog into deep scrollback;
    /// `correctTimestampIfNeeded` still governs the persisted `Message.timestamp` used for
    /// dedup, display, and within-block ordering.
    ///
    /// - Parameters:
    ///   - context: Whether the message arrived live or via a backlog drain (carrying the anchor).
    ///   - receiveTime: When a live message was received. Ignored for `initialSync`.
    /// - Returns: The date to persist as the message's sort key.
    nonisolated static func sortDate(
        for context: DeliveryContext,
        receiveTime: Date
    ) -> Date {
        switch context {
        case .live:
            return receiveTime
        case .initialSync(let anchor):
            return anchor
        }
    }
}
