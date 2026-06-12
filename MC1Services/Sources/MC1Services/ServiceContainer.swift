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
/// ## Lifetime
///
/// The container is per-connection, not a singleton: `ConnectionManager` builds a
/// fresh `ServiceContainer` (and a fresh session) on every connection in
/// `buildServicesAndSaveDevice`, and tears it down via `tearDown()` on disconnect
/// before nilling its reference. Anything that must survive reconnects (for example
/// detected platform or last-clean-sync state) lives on `ConnectionManager`, not here.
/// `init` also reassigns the `DebugLogBuffer.shared` global to this container's buffer,
/// so a stale container's services must not keep running past teardown.
///
/// ## Usage
///
/// ```swift
/// // Create container with session and model container
/// let container = ServiceContainer(
///     session: meshCoreSession,
///     modelContainer: modelContainer,
///     radioID: radioUUID
/// )
///
/// // Start event monitoring when device is connected
/// await container.startEventMonitoring(radioID: radioUUID)
/// ```
///
/// ## Dependency injection
///
/// `init` constructs services in dependency order, so a fully wired container
/// exists as soon as `init` returns. Stable one-to-one dependencies are
/// constructor-injected. One-to-many notifications flow through typed event
/// streams (`SyncDataEvent`, `AdvertisementEvent`, `MessageStatusEvent`,
/// `HeardRepeatEvent`, `RemoteNodeEvent`, `RoomServerEvent`,
/// `ContactServiceEvent`, and the RX log entry stream), every one of which is
/// finished in `tearDown()` so consumer loops cannot outlive the container.
///
/// Setter injection survives only where ordering or a reference cycle forces it:
/// - `MessagePollingService` ingestion handlers: installed by
///   `SyncCoordinator.wireMessageHandlers` before event monitoring starts,
///   cleared in `tearDown()`.
/// - `SyncCoordinator.setSyncActivityCallbacks`: installed by
///   `ConnectionUIState.wireCallbacks` before `onConnectionEstablished` so the
///   count-paired started/ended events keep the sync pill accurate.
/// - `SyncCoordinator.setCleanChannelSyncCallback` /
///   `setChannelSyncAttemptedCallback`: installed by
///   `ConnectionManager.wireCleanChannelSyncCallback` at container build.
/// - `NotificationService` action closures and `getBadgeCount`: installed by
///   `AppState` and `NavigationCoordinator` when notification handling is configured.
/// - `ChannelService.setDraftClearHandler` and
///   `DeviceService.setDeviceUpdateCallback`: installed by
///   `AppState.wireServicesIfConnected` per connection.
/// - `NodeConfigService.setOnPostIdentityImport`: installed by
///   `ConnectionManager.buildServicesAndSaveDevice`; a cycle-forced upward
///   call into `ConnectionManager`.
@Observable
@MainActor
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
    let keychainService: KeychainService

    /// Notification service for local notifications
    public let notificationService: NotificationService

    // MARK: - Core Services

    /// Service for managing contacts
    public let contactService: ContactService

    /// Service for sending messages, retry logic, and ACK/delivery tracking.
    /// It does not receive: inbound messages arrive through `MessagePollingService`
    /// and the handlers `SyncCoordinator.wireMessageHandlers` installs there.
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
    let messagePollingService: MessagePollingService

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

    // MARK: - Notification Actions

    /// Executes the multi-service transactions behind notification actions
    /// (quick reply, mark-as-read, reactions). `AppState` installs
    /// `NotificationService` forwarders that delegate here and injects the
    /// app-layer inputs via `configure(isConnectionReady:localNodeName:)`.
    public let notificationActionHandler: NotificationActionHandler

    // MARK: - App State

    /// Provider for checking app foreground/background state
    /// Used to determine sync behavior (full vs incremental)
    let appStateProvider: AppStateProvider?

    // MARK: - State

    /// Event-monitoring lifecycle. Tri-state (not a Bool) so start and stop can
    /// claim the transition synchronously before their first await; two callers
    /// interleaving at those suspension points must not double-start or
    /// double-stop every per-service monitor.
    private enum EventMonitoringState {
        case stopped
        case starting
        case active
        case stopping
    }

    private var eventMonitoringState: EventMonitoringState = .stopped

    /// Whether service event listeners are active or currently starting.
    var isEventMonitoringActive: Bool {
        eventMonitoringState == .starting || eventMonitoringState == .active
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
    ///   - connectionStateEvents: Optional broadcaster of connection-state
    ///     changes. When provided, the chat send queue observes it to wake
    ///     parked drains on each disconnected-to-connected edge.
    ///   - initialConnectionState: The connection state at container build
    ///     time. Connect paths reach `.connected` before constructing the
    ///     container, so the queue's edge detection treats an
    ///     already-connected initial value as a fired edge.
    init(
        session: MeshCoreSession,
        modelContainer: ModelContainer,
        radioID: UUID,
        appStateProvider: AppStateProvider? = nil,
        connectionStateEvents: EventBroadcaster<DeviceConnectionState>? = nil,
        initialConnectionState: DeviceConnectionState = .disconnected
    ) {
        self.session = session
        self.appStateProvider = appStateProvider
        self.dataStore = PersistenceStore(modelContainer: modelContainer)
        self.inlineImageDimensionsStore = InlineImageDimensionsStore()

        // Independent services (no dependencies)
        self.keychainService = KeychainService()
        self.notificationService = NotificationService()
        self.syncCoordinator = SyncCoordinator()

        // Core services, constructed so every dependency exists before its consumer
        self.heardRepeatsService = HeardRepeatsService(dataStore: dataStore)
        self.rxLogService = RxLogService(
            session: session,
            dataStore: dataStore,
            heardRepeatsService: heardRepeatsService
        )
        self.remoteNodeService = RemoteNodeService(
            session: session,
            dataStore: dataStore,
            keychainService: keychainService
        )
        let cleanupCoordinator = ContactCleanupCoordinator(
            dataStore: dataStore,
            syncCoordinator: syncCoordinator,
            notificationService: notificationService,
            remoteNodeService: remoteNodeService
        )
        self.contactService = ContactService(
            session: session,
            dataStore: dataStore,
            syncCoordinator: syncCoordinator,
            cleanupCoordinator: cleanupCoordinator
        )
        self.messageService = MessageService(
            session: session,
            dataStore: dataStore,
            contactService: contactService
        )
        self.channelService = ChannelService(
            session: session,
            dataStore: dataStore,
            rxLogService: rxLogService
        )
        self.settingsService = SettingsService(session: session)
        self.deviceService = DeviceService(dataStore: dataStore)
        self.advertisementService = AdvertisementService(session: session, dataStore: dataStore)
        self.messagePollingService = MessagePollingService(session: session, dataStore: dataStore)
        self.binaryProtocolService = BinaryProtocolService(session: session, dataStore: dataStore)
        self.debugLogBuffer = DebugLogBuffer(dataStore: dataStore)
        DebugLogBuffer.shared = debugLogBuffer
        self.reactionService = ReactionService()
        self.nodeConfigService = NodeConfigService(
            session: session,
            settingsService: settingsService,
            channelService: channelService,
            dataStore: dataStore,
            syncCoordinator: syncCoordinator
        )
        self.nodeSnapshotService = NodeSnapshotService(dataStore: dataStore)

        // Higher-level services (depend on other services)
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

        self.chatSendQueueService = ChatSendQueueService(
            radioID: radioID,
            dataStore: dataStore,
            messageService: messageService,
            channelService: channelService,
            reactionService: reactionService
        )
        self.notificationActionHandler = NotificationActionHandler(
            dataStore: dataStore,
            messageService: messageService,
            notificationService: notificationService,
            roomServerService: roomServerService,
            syncCoordinator: syncCoordinator
        )
        if let connectionStateEvents {
            chatSendQueueService.observeConnectionState(
                initial: initialConnectionState,
                events: connectionStateEvents.subscribe()
            )
        }
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
    func startEventMonitoring(
        radioID: UUID,
        enableAutoFetch: Bool = true,
        enableAdvertisementMonitoring: Bool = true
    ) async {
        // Claim synchronously before the awaits below so an overlapping caller
        // (SyncCoordinator.onConnectionEstablished racing the foreground health
        // check) cannot pass the guard and double-start every monitor.
        guard eventMonitoringState == .stopped else { return }
        eventMonitoringState = .starting

        let logger = Logger(subsystem: "com.mc1", category: "ServiceContainer")

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

        eventMonitoringState = .active
    }

    /// Stops event monitoring for all services.
    ///
    /// Call this when disconnecting from a device.
    func stopEventMonitoring() async {
        // Claimed synchronously, mirroring startEventMonitoring, so two teardown
        // paths interleaving at the awaits below cannot double-stop.
        guard eventMonitoringState == .active else { return }
        eventMonitoringState = .stopping

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

        eventMonitoringState = .stopped
    }

    /// Full container teardown. Must be awaited before nulling the container
    /// so chat send queue drains and chat coordinator off-main builds release
    /// the strong references they hold on `MessageService` and `dataStore`.
    /// `stopEventMonitoring()` alone does not cover those.
    func tearDown() async {
        await stopEventMonitoring()

        // Break the retain cycles the wired handlers form. The message
        // closures and the discovery event task capture this container's
        // services strongly (via SyncDependencies), so without this the whole
        // service graph leaks on every reconnect. Cleared after
        // `stopEventMonitoring()` so the event tasks reading them are cancelled.
        await messagePollingService.clearMessageHandlers()
        await syncCoordinator.cancelDiscoveryEventMonitoring()

        // Finishing the event streams ends every consumer's for-await loop,
        // releasing the strong service references those loops hold.
        syncCoordinator.finishDataEvents()
        advertisementService.finishEvents()
        messageService.finishStatusEvents()
        heardRepeatsService.finishEvents()
        remoteNodeService.finishEvents()
        roomServerService.finishEvents()
        contactService.finishEvents()
        rxLogService.finishEntryStream()

        await chatSendQueueService.shutdown()
    }

    // MARK: - Convenience Methods

    /// Performs initial database warm-up.
    ///
    /// Call this early during app launch to avoid lazy initialization delays.
    func warmUp() async throws {
        try await dataStore.warmUp()
    }

    /// Resets all remote node session connections.
    ///
    /// Call this on app launch since connections don't persist across app restarts.
    func resetRemoteNodeConnections() async throws {
        try await dataStore.resetAllRemoteNodeSessionConnections()
    }
}

// MARK: - Factory Methods

extension ServiceContainer {

    /// Creates a service container with a new in-memory model container.
    ///
    /// Useful for testing and previews. The container is fully wired by `init`,
    /// matching production behavior.
    ///
    /// - Parameters:
    ///   - session: The MeshCoreSession for device communication
    ///   - radioID: Radio ID to scope the chat send queue (default: synthesized `UUID()`)
    /// - Returns: A configured ServiceContainer with in-memory storage
    static func forTesting(
        session: MeshCoreSession,
        radioID: UUID = UUID()
    ) async throws -> ServiceContainer {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return ServiceContainer(
            session: session,
            modelContainer: container,
            radioID: radioID
        )
    }
}
