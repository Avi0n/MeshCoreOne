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

    let logger = PersistentLogger(subsystem: "com.mc1.services", category: "SyncCoordinator")

    /// Actor-local guard against concurrent sync execution.
    /// Checked and set synchronously (no `await`) to eliminate the TOCTOU window
    /// that existed when guarding via the `@MainActor`-isolated `state` property.
    var isSyncInProgress = false

    /// Cached blocked names (contacts + channel senders) for O(1) lookup in message handlers
    private var blockedNames: Set<String> = []

    /// Tracks unresolved channel indices that generated notifications in this connection session.
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
    var onCleanChannelSync: (@Sendable (_ deviceID: UUID) async -> Void)?

    /// Sets the callback for clean channel sync completion.
    public func setCleanChannelSyncCallback(_ callback: @escaping @Sendable (_ deviceID: UUID) async -> Void) {
        onCleanChannelSync = callback
    }

    /// Callback when non-message sync activity starts
    var onSyncActivityStarted: (@Sendable () async -> Void)?

    /// Callback when non-message sync activity ends
    private var onSyncActivityEnded: (@Sendable (_ succeeded: Bool) async -> Void)?

    /// Tracks whether onSyncActivityEnded has been called for the current sync cycle.
    /// Prevents double-callback when disconnect occurs mid-sync (both onDisconnected
    /// and error path would otherwise call onSyncActivityEnded).
    var hasEndedSyncActivity = true

    /// Watchdog task that force-clears notification suppression after 120s.
    /// Prevents stuck suppression if sync completes abnormally without clearing it.
    private var suppressionWatchdogTask: Task<Void, Never>?

    /// Callback when sync phase changes (for SwiftUI observation).
    @MainActor private var onPhaseChanged: (@Sendable @MainActor (_ phase: SyncPhase?) -> Void)?

    /// Callback when contacts data changes (for SwiftUI observation).
    @MainActor private var onContactsChanged: (@Sendable @MainActor () -> Void)?

    /// Callback when conversations data changes (for SwiftUI observation).
    @MainActor private var onConversationsChanged: (@Sendable @MainActor () -> Void)?

    /// Callback when a direct message is received (for MessageEventBroadcaster)
    var onDirectMessageReceived: (@Sendable (_ message: MessageDTO, _ contact: ContactDTO) async -> Void)?

    /// Callback when a channel message is received (for MessageEventBroadcaster)
    var onChannelMessageReceived: (@Sendable (_ message: MessageDTO, _ channelIndex: UInt8) async -> Void)?

    /// Callback when a room message is received (for MessageEventBroadcaster)
    var onRoomMessageReceived: (@Sendable (_ message: RoomMessageDTO) async -> Void)?

    /// Callback when a reaction is received for a channel message
    var onReactionReceived: (@Sendable (_ messageID: UUID, _ summary: String) async -> Void)?

    // MARK: - Test Seams

    #if DEBUG
    /// Test override for `performResync`. When set, bypasses the real sync path.
    var performResyncOverride: ((_ deviceID: UUID, _ services: ServiceContainer) async -> Bool)?

    /// Sets the test override for `performResync`.
    public func setPerformResyncOverride(_ override: @escaping @Sendable (_ deviceID: UUID, _ services: ServiceContainer) async -> Bool) {
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

    /// Sets callbacks for data change notifications (used by AppState for SwiftUI observation)
    public func setDataChangeCallbacks(
        onContactsChanged: @escaping @Sendable @MainActor () -> Void,
        onConversationsChanged: @escaping @Sendable @MainActor () -> Void
    ) async {
        await MainActor.run {
            self.onContactsChanged = onContactsChanged
            self.onConversationsChanged = onConversationsChanged
        }
    }

    /// Sets callbacks for message events (used by AppState for MessageEventBroadcaster)
    public func setMessageEventCallbacks(
        onDirectMessageReceived: @escaping @Sendable (_ message: MessageDTO, _ contact: ContactDTO) async -> Void,
        onChannelMessageReceived: @escaping @Sendable (_ message: MessageDTO, _ channelIndex: UInt8) async -> Void,
        onRoomMessageReceived: @escaping @Sendable (_ message: RoomMessageDTO) async -> Void,
        onReactionReceived: @escaping @Sendable (_ messageID: UUID, _ summary: String) async -> Void
    ) {
        self.onDirectMessageReceived = onDirectMessageReceived
        self.onChannelMessageReceived = onChannelMessageReceived
        self.onRoomMessageReceived = onRoomMessageReceived
        self.onReactionReceived = onReactionReceived
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

    func startSuppressionWatchdog(services: ServiceContainer) {
        suppressionWatchdogTask?.cancel()
        suppressionWatchdogTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(120))
            guard !Task.isCancelled, let self else { return }
            let isSuppressing = await services.notificationService.isSuppressingNotifications
            guard isSuppressing else { return }
            self.logger.warning("[Sync] Notification suppression watchdog fired after 120s - force clearing")
            await MainActor.run {
                services.notificationService.isSuppressingNotifications = false
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
        onContactsChanged?()
    }

    /// Notify that conversations data changed (triggers UI refresh)
    @MainActor
    public func notifyConversationsChanged() {
        conversationsVersion += 1
        onConversationsChanged?()
    }

    // MARK: - Blocked Contacts Cache

    /// Refresh the blocked names cache from the data store (contacts + channel senders)
    public func refreshBlockedContactsCache(deviceID: UUID, dataStore: any PersistenceStoreProtocol) async {
        do {
            let blockedContacts = try await dataStore.fetchBlockedContacts(deviceID: deviceID)
            let blockedSenders = try await dataStore.fetchBlockedChannelSenders(deviceID: deviceID)
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
    func deleteBlockedSenderMessages(deviceID: UUID, dataStore: any PersistenceStoreProtocol) async {
        let names = blockedNames
        guard !names.isEmpty else { return }

        for name in names {
            try? await dataStore.deleteChannelMessages(fromSender: name, deviceID: deviceID)
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
}
