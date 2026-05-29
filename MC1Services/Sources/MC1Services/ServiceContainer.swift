import Foundation
import OSLog
import SwiftData
import MeshCore

/// Dependency injection container for MC1Services.
///
/// `ServiceContainer` creates and manages all services needed by the MeshCore One app,
/// handling the dependency graph between services. It provides a single point of
/// initialization for the service layer.
///
/// ## Usage
///
/// ```swift
/// // Create container with session and model container
/// let container = ServiceContainer(
///     session: meshCoreSession,
///     modelContainer: modelContainer
/// )
///
/// // Wire up inter-service dependencies
/// await container.wireServices()
///
/// // Start event monitoring when device is connected
/// await container.startEventMonitoring(radioID: radioUUID)
/// ```
///
/// ## Service Dependencies
///
/// Services are initialized in dependency order:
/// 1. Independent services (KeychainService, NotificationService)
/// 2. Core services (ContactService, MessageService, ChannelService, etc.)
/// 3. Higher-level services (RemoteNodeService, RepeaterAdminService, RoomServerService)
@MainActor
@Observable
public final class ServiceContainer {

    // MARK: - Core Infrastructure

    /// The MeshCore session for device communication
    public let session: MeshCoreSession

    /// The persistence store for SwiftData operations
    public let dataStore: PersistenceStore

    /// Persists inline-image aspect ratios for chat link previews. Built early
    /// because downstream caches and the prefetcher depend on it.
    public let inlineImageDimensionsStore: InlineImageDimensionsStore

    // MARK: - Independent Services

    /// Keychain service for secure credential storage
    public let keychainService: KeychainService

    /// Notification service for local notifications
    public let notificationService: NotificationService

    // MARK: - Core Services

    /// Service for managing contacts
    public let contactService: ContactService

    /// Service for sending and receiving messages
    public let messageService: MessageService

    /// Service for managing channels (groups)
    public let channelService: ChannelService

    /// Service for device settings management
    public let settingsService: SettingsService

    /// Service for device data persistence
    public let deviceService: DeviceService

    /// Service for advertisements and path discovery
    public let advertisementService: AdvertisementService

    /// Service for polling and routing messages
    public let messagePollingService: MessagePollingService

    /// Service for binary protocol operations (telemetry, status, etc.)
    public let binaryProtocolService: BinaryProtocolService

    /// Service for RX log packet capture
    public let rxLogService: RxLogService

    /// Service for tracking heard repeats of sent messages
    public let heardRepeatsService: HeardRepeatsService

    /// Buffer for batching debug log entries to persistence
    public let debugLogBuffer: DebugLogBuffer

    /// Service for handling emoji reactions on channel messages
    public let reactionService: ReactionService

    /// Service for exporting/importing node configuration
    public let nodeConfigService: NodeConfigService

    /// Service for node status history snapshots
    public let nodeSnapshotService: NodeSnapshotService

    // MARK: - Remote Node Services

    /// Service for remote node session management
    public let remoteNodeService: RemoteNodeService

    /// Service for repeater administration
    public let repeaterAdminService: RepeaterAdminService

    /// Service for room server administration (telemetry, settings)
    public let roomAdminService: RoomAdminService

    /// Service for room server operations
    public let roomServerService: RoomServerService

    // MARK: - Sync Coordination

    /// Sync coordinator for managing sync lifecycle
    public let syncCoordinator: SyncCoordinator

    // MARK: - Chat Send Queue

    /// Service-layer outbound chat queue. Replaces the per-view-model
    /// `dmSendQueue` / `channelSendQueue` instances. Hydrates from
    /// `PendingSend` on construction; drain gated on `ConnectionManager`
    /// transport state via `BLETransportOpenedSignal`.
    ///
    /// Constructed eagerly in `ServiceContainer.init` because `radioID`
    /// is known at container-build time (`buildServicesAndSaveDevice`
    /// resolves the device record before instantiating the container).
    /// Eager construction closes the visibility window where the
    /// container exists but the service is `nil`.
    public let chatSendQueueService: ChatSendQueueService

    // MARK: - App State

    /// Provider for checking app foreground/background state
    /// Used to determine sync behavior (full vs incremental)
    public let appStateProvider: AppStateProvider?

    // MARK: - State

    /// Whether services have been wired together
    private var isWired = false

    /// Whether event monitoring is active
    private var isMonitoringEvents = false

    /// Whether service event listeners are currently active.
    public var isEventMonitoringActive: Bool {
        isMonitoringEvents
    }

    // MARK: - Initialization

    /// Creates a new service container.
    ///
    /// - Parameters:
    ///   - session: The MeshCoreSession for device communication
    ///   - modelContainer: The SwiftData model container for persistence
    ///   - radioID: The connected device's radio ID. Used to scope the
    ///     chat send queue's pending-send rows so two radios cannot share
    ///     drain state across reconnects.
    ///   - appStateProvider: Optional provider for app foreground/background state
    public init(
        session: MeshCoreSession,
        modelContainer: ModelContainer,
        radioID: UUID,
        appStateProvider: AppStateProvider? = nil
    ) {
        self.session = session
        self.appStateProvider = appStateProvider
        self.dataStore = PersistenceStore(modelContainer: modelContainer)
        self.inlineImageDimensionsStore = InlineImageDimensionsStore()

        // Independent services (no dependencies)
        self.keychainService = KeychainService()
        self.notificationService = NotificationService()

        // Core services (depend on session and/or dataStore)
        self.contactService = ContactService(session: session, dataStore: dataStore)
        self.messageService = MessageService(session: session, dataStore: dataStore)
        self.channelService = ChannelService(session: session, dataStore: dataStore)
        self.settingsService = SettingsService(session: session)
        self.deviceService = DeviceService(dataStore: dataStore)
        self.advertisementService = AdvertisementService(session: session, dataStore: dataStore)
        self.messagePollingService = MessagePollingService(session: session, dataStore: dataStore)
        self.binaryProtocolService = BinaryProtocolService(session: session, dataStore: dataStore)
        self.rxLogService = RxLogService(session: session, dataStore: dataStore)
        self.heardRepeatsService = HeardRepeatsService(dataStore: dataStore)
        self.debugLogBuffer = DebugLogBuffer(dataStore: dataStore)
        DebugLogBuffer.shared = debugLogBuffer
        self.reactionService = ReactionService()
        self.nodeConfigService = NodeConfigService(
            session: session,
            settingsService: settingsService,
            channelService: channelService,
            dataStore: dataStore
        )
        self.nodeSnapshotService = NodeSnapshotService(dataStore: dataStore)

        // Higher-level services (depend on other services)
        self.remoteNodeService = RemoteNodeService(
            session: session,
            dataStore: dataStore,
            keychainService: keychainService
        )
        self.repeaterAdminService = RepeaterAdminService(
            session: session,
            remoteNodeService: remoteNodeService,
            dataStore: dataStore
        )
        self.roomAdminService = RoomAdminService(
            remoteNodeService: remoteNodeService,
            dataStore: dataStore
        )
        self.roomServerService = RoomServerService(
            session: session,
            remoteNodeService: remoteNodeService,
            dataStore: dataStore
        )

        // Sync coordinator (no dependencies on other services)
        self.syncCoordinator = SyncCoordinator()

        self.chatSendQueueService = ChatSendQueueService(
            radioID: radioID,
            dataStore: dataStore,
            messageService: messageService,
            channelService: channelService,
            reactionService: reactionService
        )
    }

    // MARK: - Service Wiring

    /// Wires up inter-service dependencies.
    ///
    /// Call this after initialization to establish connections between services
    /// that need to communicate with each other.
    public func wireServices() async {
        guard !isWired else { return }

        // Wire message service to contact service for path management during retry
        await messageService.setContactService(contactService)

        // Wire contact service to sync coordinator for UI refresh notifications
        await contactService.setSyncCoordinator(syncCoordinator)

        // Wire node config service to sync coordinator for contact import notifications
        await nodeConfigService.setSyncCoordinator(syncCoordinator)

        // Wire contact service cleanup handler for notification/badge/cache/session updates
        await contactService.setCleanupHandler { [weak self] contactID, reason, publicKey in
            guard let self else { return }

            // Refresh blocked names cache and delete channel messages on block
            if reason == .blocked || reason == .unblocked {
                if let contact = try? await self.dataStore.fetchContact(id: contactID) {
                    if reason == .blocked {
                        try? await self.dataStore.deleteChannelMessages(
                            fromSender: contact.name, radioID: contact.radioID
                        )
                    }
                    await self.syncCoordinator.refreshBlockedContactsCache(
                        radioID: contact.radioID, dataStore: self.dataStore
                    )
                    await self.syncCoordinator.notifyConversationsChanged()
                }
            }

            // Remove delivered notifications for this contact (only on block/delete)
            if reason == .blocked || reason == .deleted {
                await self.notificationService.removeDeliveredNotifications(forContactID: contactID)
            }

            // Update badge count
            await self.notificationService.updateBadgeCount()

            // Clean up any associated remote node session on delete
            if reason == .deleted {
                if let session = try? await self.dataStore.fetchRemoteNodeSession(publicKey: publicKey) {
                    try? await self.remoteNodeService.removeSession(id: session.id, publicKey: publicKey)
                }
                await self.syncCoordinator.notifyConversationsChanged()
            }
        }

        // Wire channel updates to RxLogService for decryption cache
        await channelService.setChannelUpdateHandler { [weak self] channels in
            guard let self else { return }
            let secrets: [UInt8: Data] = Dictionary(
                uniqueKeysWithValues: channels.map { ($0.index, $0.secret) }
            )
            let names: [UInt8: String] = Dictionary(
                uniqueKeysWithValues: channels.map { ($0.index, $0.name) }
            )
            Task { await self.rxLogService.updateChannels(secrets: secrets, names: names) }
        }

        // Wire HeardRepeatsService to RxLogService for repeat detection
        await rxLogService.setHeardRepeatsService(heardRepeatsService)

        isWired = true
    }

    // MARK: - Event Monitoring

    /// Starts event monitoring for all services.
    ///
    /// Call this after a device is connected to begin processing events
    /// from the MeshCoreSession.
    ///
    /// - Parameters:
    ///   - radioID: The connected device's radio ID for data scoping
    ///   - enableAutoFetch: Whether to start message auto-fetch immediately (default true)
    ///   - enableAdvertisementMonitoring: Whether to start advertisement monitoring immediately (default true)
    public func startEventMonitoring(
        radioID: UUID,
        enableAutoFetch: Bool = true,
        enableAdvertisementMonitoring: Bool = true
    ) async {
        guard !isMonitoringEvents else { return }

        let logger = Logger(subsystem: "com.mc1.services", category: "ServiceContainer")

        // Configure HeardRepeatsService with device info
        do {
            if let device = try await dataStore.fetchDevice(radioID: radioID) {
                await heardRepeatsService.configure(
                    radioID: radioID,
                    localNodeName: device.nodeName
                )
            } else {
                logger.warning("Device not found for HeardRepeatsService configuration")
            }
        } catch {
            logger.warning("Failed to fetch device for HeardRepeatsService: \(error)")
        }

        // Start event monitoring for services that need it
        if enableAdvertisementMonitoring {
            await advertisementService.startEventMonitoring(radioID: radioID)
        }
        await rxLogService.startEventMonitoring(radioID: radioID)
        await messageService.startEventMonitoring()
        await messageService.startAckExpiryChecking()
        await remoteNodeService.startEventMonitoring()

        // Always start message event monitoring so handlers are ready for polled messages
        await messagePollingService.startMessageEventMonitoring(radioID: radioID)
        if enableAutoFetch {
            await messagePollingService.startAutoFetch(radioID: radioID)
        }

        // Prune debug logs on connection
        Task {
            try? await dataStore.pruneDebugLogEntries(keepCount: 1000)
        }

        // Prune node status snapshots older than 1 year
        Task {
            let oneYearAgo = Calendar.current.date(byAdding: .year, value: -1, to: .now)!
            await nodeSnapshotService.pruneOldSnapshots(olderThan: oneYearAgo)
        }

        isMonitoringEvents = true
    }

    /// Stops event monitoring for all services.
    ///
    /// Call this when disconnecting from a device.
    public func stopEventMonitoring() async {
        guard isMonitoringEvents else { return }

        await advertisementService.stopEventMonitoring()
        await rxLogService.stopEventMonitoring()
        await messageService.stopEventMonitoring()
        // Do not fail in-flight DMs on disconnect. The firmware retains the
        // expected ACK and re-emits the delivery confirmation whenever it
        // returns, so a routine BLE cycle must not mark a delivered message
        // `.failed`. Stop only the expiry checker; pending entries resolve on
        // reconnect within the same session or expire via `ackGiveUpWindow`.
        await messageService.stopAckExpiryChecking()
        await messagePollingService.stopMessageEventMonitoring()
        // RemoteNodeService event monitoring is per-session, handled internally

        // Flush debug log buffer
        await debugLogBuffer.shutdown()

        isMonitoringEvents = false
    }

    /// Full container teardown. Must be awaited before nulling the container
    /// so chat send queue drains and chat coordinator off-main builds release
    /// the strong references they hold on `MessageService` and `dataStore`.
    /// `stopEventMonitoring()` alone does not cover those.
    public func tearDown() async {
        await stopEventMonitoring()
        await chatSendQueueService.shutdown()
    }

    // MARK: - Initial Sync

    /// Performs initial sync of contacts and channels from the device.
    ///
    /// This method checks for task cancellation between sync operations.
    /// Call after connection is established to ensure device data is current.
    ///
    /// - Parameter radioID: The connected device's radio ID for data scoping
    public func performInitialSync(radioID: UUID) async {
        let logger = Logger(subsystem: "com.mc1.services", category: "ServiceContainer")

        // Migrate app favorites to device BEFORE sync (one-time on upgrade)
        // Must run first because sync overwrites isFavorite with device flags
        guard !Task.isCancelled else { return }
        do {
            let migrated = try await contactService.migrateAppFavoritesToDevice(radioID: radioID)
            if migrated > 0 {
                logger.info("Initial sync: \(migrated) favorites migrated to device")
            }
        } catch {
            logger.warning("Initial sync: favorites migration failed: \(error)")
        }

        // Sync contacts from device
        guard !Task.isCancelled else { return }
        do {
            let result = try await contactService.syncContacts(radioID: radioID)
            if result.contactsReceived > 0 {
                logger.info("Initial sync: \(result.contactsReceived) contacts synced")
            }
        } catch {
            logger.warning("Initial sync: contact sync failed: \(error)")
        }

        // Sync channels
        guard !Task.isCancelled else { return }
        do {
            // Fetch device to get maxChannels
            guard let device = try await dataStore.fetchDevice(radioID: radioID) else {
                logger.warning("Initial sync: device not found for channel sync")
                return
            }

            let result = try await channelService.syncChannels(radioID: radioID, maxChannels: device.maxChannels)
            if result.channelsSynced > 0 {
                logger.info("Initial sync: \(result.channelsSynced) channels synced")
            }

            // Update RxLogService with channel data for decryption
            await updateRxLogChannels(radioID: radioID)
        } catch {
            logger.warning("Initial sync: channel sync failed: \(error)")
        }
    }

    /// Updates RxLogService with current channel data for message decryption.
    private func updateRxLogChannels(radioID: UUID) async {
        do {
            let channels = try await dataStore.fetchChannels(radioID: radioID)
            let secrets: [UInt8: Data] = Dictionary(
                uniqueKeysWithValues: channels.map { ($0.index, $0.secret) }
            )
            let names: [UInt8: String] = Dictionary(
                uniqueKeysWithValues: channels.map { ($0.index, $0.name) }
            )
            await rxLogService.updateChannels(secrets: secrets, names: names)
        } catch {
            let logger = Logger(subsystem: "com.mc1.services", category: "ServiceContainer")
            logger.warning("Failed to update RX log channels: \(error)")
        }
    }

    // MARK: - Convenience Methods

    /// Performs initial database warm-up.
    ///
    /// Call this early during app launch to avoid lazy initialization delays.
    public func warmUp() async throws {
        try await dataStore.warmUp()
    }

    /// Resets all remote node session connections.
    ///
    /// Call this on app launch since connections don't persist across app restarts.
    public func resetRemoteNodeConnections() async throws {
        try await dataStore.resetAllRemoteNodeSessionConnections()
    }
}

// MARK: - Factory Methods

extension ServiceContainer {

    /// Creates a service container with a new in-memory model container.
    ///
    /// Useful for testing and previews. By default, inter-service dependencies
    /// are wired via `wireServices()` so the container matches production behavior.
    ///
    /// - Parameters:
    ///   - session: The MeshCoreSession for device communication
    ///   - wired: Whether to call `wireServices()` after creation (default `true`)
    ///   - radioID: Radio ID to scope the chat send queue (default: synthesized `UUID()`)
    /// - Returns: A configured ServiceContainer with in-memory storage
    public static func forTesting(
        session: MeshCoreSession,
        wired: Bool = true,
        radioID: UUID = UUID()
    ) async throws -> ServiceContainer {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let services = ServiceContainer(
            session: session,
            modelContainer: container,
            radioID: radioID
        )
        if wired {
            await services.wireServices()
        }
        return services
    }
}
