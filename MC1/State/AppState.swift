import CoreLocation
import SwiftUI
import SwiftData
import UserNotifications
import MC1Services
import MeshCore
import OSLog
import TipKit


/// Simplified app-wide state management.
/// Composes ConnectionManager for connection lifecycle.
/// Handles only UI state, navigation, and notification wiring.
@Observable
@MainActor
public final class AppState {

    // MARK: - Logging

    private let logger = Logger(subsystem: "com.mc1", category: "AppState")

    // MARK: - Location

    /// App-wide location service for permission management
    public let locationService = LocationService()

    // MARK: - Offline Maps

    /// Offline map pack management and network monitoring
    let offlineMapService = OfflineMapService()

    /// Best available location for proximity-based disambiguation.
    public var bestAvailableLocation: CLLocation? {
        if let phoneLocation = locationService.currentLocation {
            return phoneLocation
        }
        guard let device = connectedDevice, device.hasLocation else {
            return nil
        }
        return CLLocation(latitude: device.latitude, longitude: device.longitude)
    }

    // MARK: - Region preference

    @ObservationIgnored lazy var regionResolver = RegionResolver(location: locationService)

    /// Suppresses the `regionSelection` `didSet` write-back during cold-start load. Without
    /// this, `loadPersistedRegionSelection()` would re-encode the just-read JSON and rewrite
    /// the same bytes to UserDefaults on every launch.
    @ObservationIgnored private var suppressRegionPersist = false

    public var regionSelection: RegionSelection? {
        didSet {
            guard !suppressRegionPersist else { return }
            persistRegionSelection()
        }
    }

    private func persistRegionSelection() {
        BackupUserDefaults.persistRegionSelection(regionSelection)
    }

    private func loadPersistedRegionSelection() {
        guard UserDefaults.standard.data(forKey: BackupUserDefaults.regionSelectionKey) != nil else { return }
        guard let decoded = BackupUserDefaults.loadRegionSelection() else {
            logger.warning("Failed to decode persisted region selection — clearing key")
            UserDefaults.standard.removeObject(forKey: BackupUserDefaults.regionSelectionKey)
            return
        }
        suppressRegionPersist = true
        defer { suppressRegionPersist = false }
        self.regionSelection = decoded
    }

    // MARK: - Connection (via ConnectionManager)

    /// The connection manager for device lifecycle
    public let connectionManager: ConnectionManager
    private let bootstrapDebugLogBuffer: DebugLogBuffer

    // Convenience accessors
    public var connectionState: MC1Services.ConnectionState { connectionManager.connectionState }
    public var connectedDevice: DeviceDTO? { connectionManager.connectedDevice }
    public var services: ServiceContainer? { connectionManager.services }

    /// Local node name with fallback for display purposes.
    public var localNodeName: String { connectedDevice?.nodeName ?? "Me" }

    /// The sync coordinator for data synchronization
    public private(set) var syncCoordinator: SyncCoordinator?

    /// Incremented when services change (device switch, reconnect). Views observe this to reload.
    public private(set) var servicesVersion: Int = 0

    // MARK: - Offline Data Access

    /// Per-conversation coordinator registry. Lives at AppState scope so
    /// chat detail screens render stored messages while disconnected. The
    /// registry's dataStore rebinds to services.dataStore on connect and is
    /// torn down on services-left.
    private(set) var chatCoordinatorRegistry: ChatCoordinatorRegistry?

    /// Cached standalone persistence store for offline browsing
    private var cachedOfflineStore: PersistenceStore?

    /// Radio ID for data access - returns connected device's radio ID or last-connected radio ID for offline browsing
    public var currentRadioID: UUID? {
        connectedDevice?.radioID ?? connectionManager.lastConnectedRadioID
    }

    /// Data store that works regardless of connection state - uses services when connected,
    /// cached standalone store when disconnected
    public var offlineDataStore: PersistenceStore? {
        if let services {
            cachedOfflineStore = nil  // Clear cache when services available
            return services.dataStore
        }
        guard connectionManager.lastConnectedDeviceID != nil else {
            cachedOfflineStore = nil
            return nil
        }
        if cachedOfflineStore == nil {
            cachedOfflineStore = connectionManager.createStandalonePersistenceStore()
        }
        return cachedOfflineStore
    }

    /// Ensures the chat coordinator registry exists, lazy-building one bound
    /// to the offline data store if none has been built yet. Used by
    /// `ChatViewModel` for the cold-launch-while-offline path where
    /// `wireServicesIfConnected` has not yet run but
    /// `connectionManager.lastConnectedDeviceID` is set so `offlineDataStore`
    /// is non-nil.
    func ensureChatCoordinatorRegistry() -> ChatCoordinatorRegistry? {
        if let chatCoordinatorRegistry { return chatCoordinatorRegistry }
        guard let store = offlineDataStore else { return nil }
        chatCoordinatorRegistry = ChatCoordinatorRegistry(dataStore: store)
        return chatCoordinatorRegistry
    }

    /// Incremented when contacts data changes. Views observe this to reload contact lists.
    public private(set) var contactsVersion: Int = 0

    /// Incremented when conversations data changes. Views observe this to reload chat lists.
    public private(set) var conversationsVersion: Int = 0

    /// Incremented when a remote-node session changes connection state.
    /// `RoomConversationView` observes this counter via `.onChange` to refresh
    /// its session DTO when re-authentication completes.
    public private(set) var sessionStateChangeCount: Int = 0

    /// Called by `MessageEventDispatcher` when a remote-node session changes
    /// connection state. Bundles refreshing conversations and bumping the
    /// state-change counter so the dispatcher only needs one entry point.
    func handleSessionStateChange() {
        refreshConversations()
        sessionStateChangeCount += 1
    }

    /// Bumps `conversationsVersion` and drives
    /// `liveActivityManager.handleUnreadCountChanged`. Used by both data-change
    /// callbacks (sync coordinator) and session-state callbacks
    /// (`setSessionStateChangedHandler` / `setConnectionRecoveryHandler`) to
    /// keep the conversations list and unread badge in lockstep.
    private func refreshConversations() {
        conversationsVersion += 1
        Task { @MainActor [weak self] in
            guard let self, let services = self.services else { return }
            let total = await self.totalUnreadCount(from: services)
            await self.liveActivityManager.handleUnreadCountChanged(unreadCount: total)
        }
    }

    /// Signals views observing `contactsVersion` / `conversationsVersion` to reload after
    /// a backup restore writes directly to the persistence store. The normal sync-path
    /// callbacks don't fire for batch imports, so without this bump any currently-mounted
    /// tabs keep showing their pre-restore snapshot until reconnect or relaunch.
    /// Also re-reads the persisted region selection — the import wrote it to UserDefaults,
    /// but `regionSelection` is only loaded once during `init`, so Settings → Region and
    /// the radio-preset views would otherwise show pre-import data until next launch.
    public func notifyDataRestored() {
        contactsVersion += 1
        conversationsVersion += 1
        loadPersistedRegionSelection()
    }

    // MARK: - Connection UI State

    /// Connection UI state (status pills, sync activity, alerts, pairing)
    let connectionUI = ConnectionUIState()

    /// Battery monitoring (polling, thresholds, low-battery notifications)
    let batteryMonitor = BatteryMonitor()

    /// Live Activity lifecycle (start/update/stop on Lock Screen and Dynamic Island)
    let liveActivityManager = LiveActivityManager()

    /// Task chain that serializes BLE lifecycle transitions across scene-phase changes.
    /// Do not cancel this task externally -- cancelling breaks the serialization
    /// guarantee because Task<Void, Never>.value returns immediately on cancellation.
    private var bleLifecycleTransitionTask: Task<Void, Never>?

    /// Fallback task that re-runs foreground recovery shortly after activation when the
    /// app is still disconnected. Covers edge cases where scene-phase callbacks are missed.
    private var activeRecoveryFallbackTask: Task<Void, Never>?

    /// Task consuming SettingsService event stream, canceled on disconnect
    private var settingsEventsTask: Task<Void, Never>?

#if DEBUG
    /// Optional test-only hooks for deterministic lifecycle ordering tests.
    private var bleEnterBackgroundOverride: (@MainActor () async -> Void)?
    private var bleBecomeActiveOverride: (@MainActor () async -> Void)?
#endif

    // MARK: - Onboarding State

    /// Onboarding state (completion flag, navigation path)
    let onboarding = OnboardingState()

    // MARK: - Navigation State

    /// Navigation coordinator (tab selection, pending targets, cross-tab navigation)
    let navigation = NavigationCoordinator()

    // MARK: - UI Coordination

    /// AsyncStream-based distribution of `MessageEvent` to chat and room
    /// consumers. Fed by service callbacks wired in `wireMessageEvents`.
    let messageEventStream = MessageEventStream()

    /// Routes service callbacks to `messageEventStream` and the session-state
    /// counter. Lazy because it captures `self` and `messageEventStream`.
    @ObservationIgnored
    private lazy var messageEventDispatcher = MessageEventDispatcher(
        appState: self,
        stream: messageEventStream
    )

    // MARK: - CLI Tool

    /// Persistent CLI tool view model (survives tab switches, reset on device disconnect)
    var cliToolViewModel: CLIToolViewModel?

    /// Tracks the device ID for CLI state - reset CLI when device changes
    private var lastConnectedDeviceIDForCLI: UUID?

    // MARK: - Status Pill

    /// The current status pill state, computed from all relevant conditions
    /// Priority: failed > syncing > ready > connecting > disconnected > hidden
    var statusPillState: StatusPillState {
        if connectionUI.syncFailedPillVisible {
            return .failed(message: L10n.Localizable.StatusPill.syncFailed)
        }
        if connectionUI.syncActivityCount > 0 || connectionState == .syncing {
            return .syncing
        }
        if connectionUI.showReadyToast {
            return .ready
        }
        if connectionState == .connecting {
            return .connecting
        }
        if connectionUI.disconnectedPillVisible {
            return .disconnected
        }
        return .hidden
    }

    /// Whether Settings startup reads should run right now.
    var canRunSettingsStartupReads: Bool {
        if connectionState == .ready { return true }
        return connectionState == .connected && connectionUI.currentSyncPhase == .messages
    }

    // MARK: - Initialization

    init(modelContainer: ModelContainer) {
        let bootstrapStore = PersistenceStore(modelContainer: modelContainer)
        let bootstrapBuffer = DebugLogBuffer(dataStore: bootstrapStore)
        self.bootstrapDebugLogBuffer = bootstrapBuffer
        DebugLogBuffer.shared = bootstrapBuffer

        self.connectionManager = ConnectionManager(modelContainer: modelContainer)

        // Provide LiveActivityManager with current radio connection state so
        // its restart/recovery/stale paths consult ground truth instead of
        // the LA's last cached `isConnected`. Read from connectionState (a
        // transport-link predicate) rather than connectedDevice: during iOS
        // auto-reconnect connectedDevice is intentionally retained while the
        // transport link is down, and using identity as a liveness signal
        // would resurrect a "Connected" LA mid-reconnect.
        liveActivityManager.connectionStateProvider = { [weak self] in
            self?.connectionState.isConnected ?? false
        }

        // Wire app state provider for incremental sync support
        connectionManager.appStateProvider = AppStateProviderImpl()

        // Wire connection ready callback - automatically updates UI when connection completes
        connectionManager.onConnectionReady = { [weak self] in
            await self?.wireServicesIfConnected()
        }

        // Wire connection lost callback - updates UI when connection is lost
        connectionManager.onConnectionLost = { [weak self] in
            await self?.wireServicesIfConnected()
        }

        // Wire device synced callback - runs after sync completes and state is .ready
        connectionManager.onDeviceSynced = { [weak self] in
            self?.performStaleNodeCleanup()
        }

        loadPersistedRegionSelection()

        Task { [regionAlreadySet = regionSelection != nil] in
            let suggested = await onboarding.suggestedStartingPath(
                connectionManager: connectionManager,
                locationAuthorizationStatus: locationService.authorizationStatus,
                regionAlreadySet: regionAlreadySet
            )
            if !suggested.isEmpty {
                onboarding.onboardingPath = suggested
            }
        }
    }

    // MARK: - Lifecycle

    /// Initialize on app launch
    func initialize() async {
        // Recover any existing Live Activity before activate() so that onConnectionReady
        // (which fires during activate) finds currentActivity populated and can update it.
        await liveActivityManager.recoverExistingActivity()
        liveActivityManager.startObservingEnablement()
        await connectionManager.activate()
        // Check if disconnected pill should show (for fresh launch after termination)
        connectionUI.updateDisconnectedPillState(
            connectionState: connectionState,
            lastConnectedDeviceID: connectionManager.lastConnectedDeviceID,
            shouldSuppressDisconnectedPill: connectionManager.shouldSuppressDisconnectedPill
        )
    }

    /// Wire services-dependent callbacks after a successful connection.
    func wireServicesIfConnected() async {
        guard let services else {
            settingsEventsTask?.cancel()
            settingsEventsTask = nil
            syncCoordinator = nil
            connectionUI.handleDisconnect(
                connectionState: connectionState,
                lastConnectedDeviceID: connectionManager.lastConnectedDeviceID,
                shouldSuppressDisconnectedPill: connectionManager.shouldSuppressDisconnectedPill
            )
            cliToolViewModel?.reset()
            batteryMonitor.stop()
            batteryMonitor.clearThresholds()
            await liveActivityManager.handleConnectionLost()
            chatCoordinatorRegistry?.tearDown()
            chatCoordinatorRegistry = nil
            navigation.clearPendingLinks()
            return
        }

        // Wire ConnectionUI callbacks (sync activity, node storage, pills, VoiceOver)
        // IMPORTANT: Must be set before onConnectionEstablished to avoid race condition
        await connectionUI.wireCallbacks(
            syncCoordinator: services.syncCoordinator,
            advertisementService: services.advertisementService,
            contactService: services.contactService,
            connectionManager: connectionManager
        )

        // Reset CLI if device changed (handles device switch where onConnectionLost doesn't fire)
        if let newDeviceID = connectedDevice?.id,
           let oldDeviceID = lastConnectedDeviceIDForCLI,
           newDeviceID != oldDeviceID {
            cliToolViewModel?.reset()
        }
        lastConnectedDeviceIDForCLI = connectedDevice?.id

        // Store syncCoordinator reference
        syncCoordinator = services.syncCoordinator

        // Process-wide inline image cache learns where to persist probed dims.
        await InlineImageCache.shared.attachDimensionsStore(services.inlineImageDimensionsStore)

        if let existing = chatCoordinatorRegistry {
            existing.rebind(dataStore: services.dataStore)
        } else {
            chatCoordinatorRegistry = ChatCoordinatorRegistry(dataStore: services.dataStore)
        }

        await wireDataChangeCallbacks(services: services)
        wireSettingsEventStream(services: services)
        await wireDeviceUpdateCallbacks(services: services)
        await wireMessageEvents(services: services)
        await wireLiveActivityCallbacks(services: services)

        // Increment version to trigger UI refresh in views observing this
        servicesVersion += 1

        // Set up notification center delegate, wire localized strings, then register categories
        UNUserNotificationCenter.current().delegate = services.notificationService
        services.notificationService.setStringProvider(NotificationStringProviderImpl())
        await services.notificationService.setup()

        // Configure badge count callback
        services.notificationService.getBadgeCount = { [weak self, dataStore = services.dataStore] in
            let radioID = await MainActor.run { self?.currentRadioID }
            guard let radioID else {
                return (contacts: 0, channels: 0, rooms: 0)
            }
            do {
                return try await dataStore.getTotalUnreadCounts(radioID: radioID)
            } catch {
                return (contacts: 0, channels: 0, rooms: 0)
            }
        }

        // Configure notification interaction handlers
        configureNotificationHandlers()

        // Defer battery bootstrap so connection setup is not blocked by device request timeouts.
        batteryMonitor.start(services: services, device: connectedDevice)

    }

    // MARK: - Service Wiring Helpers

    /// Wire data change callbacks for SwiftUI observation
    /// (actors don't participate in SwiftUI's observation system, so we need callbacks)
    private func wireDataChangeCallbacks(services: ServiceContainer) async {
        await services.syncCoordinator.setDataChangeCallbacks(
            onContactsChanged: { @MainActor [weak self] in
                self?.contactsVersion += 1
            },
            onConversationsChanged: { @MainActor [weak self] in
                self?.refreshConversations()
            }
        )
    }

    /// Consume settings service event stream.
    /// Updates connectedDevice when settings are changed via SettingsService.
    private func wireSettingsEventStream(services: ServiceContainer) {
        settingsEventsTask?.cancel()
        settingsEventsTask = Task { [weak self] in
            guard let self else { return }
            for await event in await services.settingsService.events() {
                switch event {
                case .deviceUpdated(let selfInfo):
                    await MainActor.run {
                        self.connectionManager.updateDevice(from: selfInfo)
                    }
                case .autoAddConfigUpdated(let config):
                    await MainActor.run {
                        self.connectionManager.updateAutoAddConfig(config)
                        // Clear storage full flag when overwrite oldest is enabled (bit 0x01)
                        if config.bitmask & 0x01 != 0 {
                            self.connectionUI.isNodeStorageFull = false
                        }
                    }
                case .clientRepeatUpdated(let enabled):
                    await MainActor.run {
                        self.connectionManager.updateClientRepeat(enabled)
                    }
                case .pathHashModeUpdated(let mode):
                    await MainActor.run {
                        self.connectionManager.updatePathHashMode(mode)
                    }
                case .allowedRepeatFreqUpdated(let ranges):
                    await MainActor.run {
                        self.connectionManager.allowedRepeatFreqRanges = ranges
                    }
                case .defaultFloodScopeUpdated(let name):
                    await MainActor.run {
                        self.connectionManager.updateDefaultFloodScopeName(name)
                    }
                }
            }
        }
    }

    /// Wire device update and contact change callbacks.
    /// Updates connectedDevice when local device settings (like OCV) are changed via DeviceService,
    /// and handles contact updates/deletions for real-time Discover page updates.
    private func wireDeviceUpdateCallbacks(services: ServiceContainer) async {
        await services.deviceService.setDeviceUpdateCallback { [weak self] deviceDTO in
            await MainActor.run {
                self?.connectionManager.updateDevice(with: deviceDTO)
            }
        }

        // Wire contact updated callback for real-time Discover page updates
        await services.advertisementService.setContactUpdatedHandler { @MainActor [weak self] in
            self?.contactsVersion += 1
        }

        // Wire contact deleted cleanup callback
        // Removes notifications and updates badge when device auto-deletes a contact via 0x8F
        await services.advertisementService.setContactDeletedCleanupHandler { [weak self] contactID, _ in
            guard let self else { return }
            self.logger.info("Overwrite oldest: running cleanup for deleted contact \(contactID) - removing notifications and updating badge")
            await self.services?.notificationService.removeDeliveredNotifications(forContactID: contactID)
            await self.services?.notificationService.updateBadgeCount()
        }
    }

    /// Wire all message event service callbacks. Delegates to
    /// `MessageEventDispatcher`, which fans the nine service callbacks out to
    /// `messageEventStream` and bumps `sessionStateChangeCount`.
    private func wireMessageEvents(services: ServiceContainer) async {
        await messageEventDispatcher.wire(services: services)
    }

    /// Wire Live Activity callbacks for RX freshness, battery, and connection lifecycle.
    private func wireLiveActivityCallbacks(services: ServiceContainer) async {
        await services.rxLogService.setPacketReceivedHandler { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                await self.liveActivityManager.handlePacketReceived()
                if self.liveActivityManager.hasActiveActivity {
                    await self.batteryMonitor.fetchBatteryIfOverdue(
                        services: self.services, device: self.connectedDevice
                    )
                }
            }
        }

        batteryMonitor.onBatteryChanged = { [weak self] battery in
            Task { @MainActor [weak self] in
                await self?.liveActivityManager.handleBatteryChanged(battery: battery)
            }
        }

        let device = connectedDevice
        let ocvArray = batteryMonitor.activeBatteryOCVArray(for: device)
        let unreadCount = await totalUnreadCount(from: services)

        if let device {
            await liveActivityManager.handleConnectionReady(
                device: device,
                ocvArray: ocvArray,
                unreadCount: unreadCount
            )
        }
    }

    /// `Activity.request` only succeeds in the foreground, so a `startActivity`
    /// triggered from background (e.g. BLE auto-reconnect after the disconnect
    /// grace timer fired) throws and leaves `currentActivity` nil. iOS may also
    /// end an activity from background — 8-hour active cap, 12-hour total, or
    /// memory pressure — in which case the `.dismissed` branch in
    /// `validateActivityState` clears the reference without restarting. Both
    /// leave the radio connected with no LA on screen.
    private func restartLiveActivityIfMissing() async {
        guard connectionState.isConnected,
              liveActivityManager.isEnabled,
              !liveActivityManager.hasActiveActivity,
              let device = connectedDevice,
              let services else { return }

        let ocvArray = batteryMonitor.activeBatteryOCVArray(for: device)
        let unreadCount = await totalUnreadCount(from: services)
        await liveActivityManager.handleConnectionReady(
            device: device,
            ocvArray: ocvArray,
            unreadCount: unreadCount
        )
    }

    private func totalUnreadCount(from services: ServiceContainer) async -> Int {
        guard let radioID = currentRadioID else { return 0 }
        let counts = (try? await services.dataStore.getTotalUnreadCounts(radioID: radioID))
            ?? (contacts: 0, channels: 0, rooms: 0)
        return counts.contacts + counts.channels + counts.rooms
    }

    // MARK: - Stale Node Cleanup

    /// Runs automatic cleanup of stale non-favorite nodes if the threshold is configured.
    /// - Parameter force: When `true`, skips the 6-hour cooldown (used when the user changes the setting).
    func performStaleNodeCleanup(force: Bool = false) {
        let threshold = UserDefaults.standard.integer(forKey: "autoDeleteStaleNodesDays")
        guard threshold > 0 else { return }

        if !force {
            let lastRunTimestamp = UserDefaults.standard.double(forKey: "lastStaleCleanupDate")
            let lastRun = lastRunTimestamp > 0 ? Date(timeIntervalSinceReferenceDate: lastRunTimestamp) : Date.distantPast
            guard Date().timeIntervalSince(lastRun) >= 3 * 3600 else {
                logger.debug("Stale node cleanup skipped — cooldown not expired")
                return
            }
        }

        Task {
            do {
                let result = try await connectionManager.removeStaleNodes(olderThanDays: threshold)
                UserDefaults.standard.set(Date().timeIntervalSinceReferenceDate, forKey: "lastStaleCleanupDate")
                if result.total > 0 {
                    logger.info("Stale node cleanup: removed \(result.removed) of \(result.total) nodes older than \(threshold) days")
                } else {
                    logger.debug("Stale node cleanup: no stale nodes found")
                }
            } catch {
                logger.warning("Stale node cleanup failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Device Actions

    /// Start device scan/pairing
    func startDeviceScan() {
        // Hide disconnected pill when starting new connection
        connectionUI.hideDisconnectedPill()
        // Clear any previous pairing failure state
        connectionUI.failedPairingDeviceID = nil
        connectionUI.isBusy = true

        Task {
            defer { connectionUI.isBusy = false }

            do {
                // pairNewDevice() triggers onConnectionReady callback on success
                try await connectionManager.pairNewDevice()
                await wireServicesIfConnected()

                // If still in onboarding, navigate to region step; otherwise mark complete
                if !onboarding.hasCompletedOnboarding {
                    onboarding.onboardingPath.append(.region)
                }
            } catch AccessorySetupKitError.pickerDismissed {
                // User cancelled - no error
            } catch AccessorySetupKitError.pickerAlreadyActive {
                // Picker is already showing - ignore
            } catch let pairingError as PairingError {
                connectionUI.presentPairingFailure(pairingError)
            } catch {
                connectionUI.presentConnectionFailure(message: error.localizedDescription)
            }
        }
    }

    /// Remove a device that failed pairing (wrong PIN) and automatically retry
    func removeFailedPairingAndRetry() {
        guard let deviceID = connectionUI.failedPairingDeviceID else { return }

        Task {
            await connectionManager.removeFailedPairing(deviceID: deviceID)
            connectionUI.failedPairingDeviceID = nil
            // Set flag - View observing scenePhase will trigger startDeviceScan when active
            connectionUI.shouldShowPickerOnForeground = true
        }
    }

    /// Retry connecting to the device that just failed without removing the bond.
    /// Used for transient pairing failures where the bond is still good — radio out of range,
    /// brief BLE flap, etc. Auth-failure paths route through `removeFailedPairingAndRetry`
    /// because the bond itself needs to be torn down before retrying.
    func retryFailedPairingConnect() async {
        guard let deviceID = connectionUI.failedPairingDeviceID else { return }
        connectionUI.isBusy = true
        defer { connectionUI.isBusy = false }

        do {
            try await connectionManager.connect(to: deviceID, forceReconnect: true)
            connectionUI.failedPairingDeviceID = nil
            await wireServicesIfConnected()
        } catch BLEError.deviceConnectedToOtherApp {
            connectionUI.failedPairingDeviceID = nil
            connectionUI.presentPairingFailure(.deviceConnectedToOtherApp(deviceID: deviceID))
        } catch {
            connectionUI.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: error))
        }
    }

    /// Called by View when scenePhase becomes active and shouldShowPickerOnForeground is true
    func handleBecameActive() {
        if connectionUI.shouldShowPickerOnForeground {
            connectionUI.shouldShowPickerOnForeground = false
            startDeviceScan()
        }

        activeRecoveryFallbackTask?.cancel()
        activeRecoveryFallbackTask = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            guard self.connectionState == .disconnected,
                  self.connectionManager.lastConnectedDeviceID != nil else { return }

            self.logger.info("[BLE] Active fallback: disconnected after activation, running foreground reconciliation")
            await self.handleReturnToForeground()
        }
    }

    /// Disconnect from device
    /// - Parameter reason: The reason for disconnecting (for debugging)
    func disconnect(reason: DisconnectReason = .userInitiated) async {
        await connectionManager.disconnect(reason: reason)
        await liveActivityManager.endActivity()
    }

    /// Connect to a device via WiFi/TCP
    func connectViaWiFi(host: String, port: UInt16, forceFullSync: Bool = false) async throws {
        // Hide disconnected pill when starting new connection
        connectionUI.hideDisconnectedPill()
        try await connectionManager.connectViaWiFi(host: host, port: port, forceFullSync: forceFullSync)
        await wireServicesIfConnected()
    }

    // MARK: - App Lifecycle

    private enum BLELifecycleTransition {
        case enterBackground
        case becomeActive
    }

    @discardableResult
    private func enqueueBLELifecycleTransition(_ transition: BLELifecycleTransition) -> Task<Void, Never> {
        let priorTask = bleLifecycleTransitionTask
        let manager = connectionManager

        let transitionTask = Task { @MainActor in
            await priorTask?.value

#if DEBUG
            switch transition {
            case .enterBackground:
                if let override = bleEnterBackgroundOverride {
                    await override()
                    return
                }
            case .becomeActive:
                if let override = bleBecomeActiveOverride {
                    await override()
                    return
                }
            }
#endif

            switch transition {
            case .enterBackground:
                await manager.appDidEnterBackground()
            case .becomeActive:
                await manager.appDidBecomeActive()
            }
        }

        bleLifecycleTransitionTask = transitionTask
        return transitionTask
    }

    /// Called when app enters background
    func handleEnterBackground() {
        activeRecoveryFallbackTask?.cancel()
        activeRecoveryFallbackTask = nil

        liveActivityManager.handleEnterBackground()

        // Keep battery polling alive when the live activity is visible on the lock screen
        if !liveActivityManager.hasActiveActivity {
            batteryMonitor.stop()
        }

        // Stop room keepalives to save battery/bandwidth
        Task {
            await services?.remoteNodeService.stopAllKeepAlives()
        }

        // Queue BLE lifecycle transition so background/foreground hooks stay ordered.
        enqueueBLELifecycleTransition(.enterBackground)
    }

    /// Called when app returns to foreground
    func handleReturnToForeground() async {
        // Update badge count from database
        await services?.notificationService.updateBadgeCount()

        // Room keepalives are managed by RoomConversationView lifecycle
        // (started on view appear, stopped on disappear, restarted via scenePhase)

        // Reconcile transport state first (WiFi check + BLE lifecycle transition,
        // which internally fires checkBLEConnectionHealth) so any stale "connected"
        // state gets cleaned up via
        // handleConnectionLoss → onConnectionLost → liveActivityManager.handleConnectionLost
        // before the Live Activity tries to validate or restart.
        await connectionManager.checkWiFiConnectionHealth()
        await enqueueBLELifecycleTransition(.becomeActive).value

        liveActivityManager.handleReturnToForeground()
        await liveActivityManager.validateActivityState()
        await restartLiveActivityIfMissing()

        // Check for expired ACKs
        if connectionState == .ready {
            try? await services?.messageService.checkExpiredAcks()
        }

        // Trigger resync if sync failed while connected
        await connectionManager.checkSyncHealth()

        // Check for missed battery thresholds and restart polling if connected
        if let services {
            await batteryMonitor.checkMissedBatteryThreshold(device: connectedDevice, services: services)
            batteryMonitor.startRefreshLoop(services: services, device: connectedDevice)
        }

        offlineMapService.resumeAllPacks()
    }

    // MARK: - Onboarding

    func completeOnboarding() {
        onboarding.completeOnboarding()
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            await donateDeviceMenuTipIfOnValidTab()
        }
    }

    /// Donates the tip if on a valid tab, otherwise marks it pending.
    /// Thin coordinator that reads from both navigation and onboarding concerns.
    func donateDeviceMenuTipIfOnValidTab() async {
        if navigation.isOnValidTabForDeviceMenuTip {
            navigation.pendingDeviceMenuTipDonation = false
            await DeviceMenuTip.hasCompletedOnboarding.donate()
        } else {
            navigation.pendingDeviceMenuTipDonation = true
        }
    }

#if DEBUG
    /// Test helper: Overrides BLE lifecycle operations for deterministic ordering tests.
    func setBLELifecycleOverridesForTesting(
        enterBackground: (@MainActor () async -> Void)? = nil,
        becomeActive: (@MainActor () async -> Void)? = nil
    ) {
        bleEnterBackgroundOverride = enterBackground
        bleBecomeActiveOverride = becomeActive
    }
#endif

    // MARK: - Notification Handlers

    /// Configure notification handlers once services are available
    func configureNotificationHandlers() {
        guard let services else { return }

        // Navigation-related notification tap handlers (delegated to NavigationCoordinator)
        navigation.configureNotificationHandlers(
            notificationService: services.notificationService,
            dataStore: services.dataStore,
            connectedDevice: { [weak self] in self?.connectedDevice }
        )

        services.notificationService.onQuickReply = { [weak self] contactID, text in
            guard let self else { return }
            await self.handleQuickReply(services: services, contactID: contactID, text: text)
        }

        services.notificationService.onChannelQuickReply = { [weak self] radioID, channelIndex, text in
            guard let self else { return }
            await self.handleChannelQuickReply(services: services, radioID: radioID, channelIndex: channelIndex, text: text)
        }

        services.notificationService.onMarkAsRead = { [weak self] contactID, messageID in
            guard let self else { return }
            await self.handleMarkAsRead(services: services, contactID: contactID, messageID: messageID)
        }

        services.notificationService.onChannelMarkAsRead = { [weak self] radioID, channelIndex, messageID in
            guard let self else { return }
            await self.handleChannelMarkAsRead(services: services, radioID: radioID, channelIndex: channelIndex, messageID: messageID)
        }

        services.notificationService.onRoomMarkAsRead = { [weak self] sessionID, messageID in
            guard let self else { return }
            await self.handleRoomMarkAsRead(services: services, sessionID: sessionID, messageID: messageID)
        }
    }

    private func handleQuickReply(services: ServiceContainer, contactID: UUID, text: String) async {
        guard let contact = try? await services.dataStore.fetchContact(id: contactID) else { return }

        if connectionState == .ready {
            do {
                _ = try await services.messageService.sendDirectMessage(text: text, to: contact)

                // Clear unread state - user replied so they've seen the chat
                try? await services.dataStore.clearUnreadCount(contactID: contactID)
                await services.notificationService.removeDeliveredNotifications(forContactID: contactID)
                await services.notificationService.updateBadgeCount()
                syncCoordinator?.notifyConversationsChanged()
                return
            } catch {
                // Fall through to draft handling
            }
        }

        services.notificationService.saveDraft(for: contactID, text: text)
        await services.notificationService.postQuickReplyFailedNotification(
            contactName: contact.displayName,
            contactID: contactID
        )
    }

    private func handleChannelQuickReply(services: ServiceContainer, radioID: UUID, channelIndex: UInt8, text: String) async {
        // Fetch channel for display name in failure notification
        let channel = try? await services.dataStore.fetchChannel(radioID: radioID, index: channelIndex)
        let channelName = channel?.name ?? "Channel \(channelIndex)"

        guard connectionState == .ready else {
            await services.notificationService.postChannelQuickReplyFailedNotification(
                channelName: channelName,
                radioID: radioID,
                channelIndex: channelIndex
            )
            return
        }

        do {
            _ = try await services.messageService.sendChannelMessage(
                text: text,
                channelIndex: channelIndex,
                radioID: radioID
            )

            // Clear unread state - user replied so they've seen the channel
            try? await services.dataStore.clearChannelUnreadCount(radioID: radioID, index: channelIndex)
            await services.notificationService.removeDeliveredNotifications(
                forChannelIndex: channelIndex,
                radioID: radioID
            )
            await services.notificationService.updateBadgeCount()
            syncCoordinator?.notifyConversationsChanged()
        } catch {
            await services.notificationService.postChannelQuickReplyFailedNotification(
                channelName: channelName,
                radioID: radioID,
                channelIndex: channelIndex
            )
        }
    }

    private func handleMarkAsRead(services: ServiceContainer, contactID: UUID, messageID: UUID) async {
        do {
            try await services.dataStore.markMessageAsRead(id: messageID)
            try await services.dataStore.clearUnreadCount(contactID: contactID)
            services.notificationService.removeDeliveredNotification(messageID: messageID)
            await services.notificationService.updateBadgeCount()
            syncCoordinator?.notifyConversationsChanged()
        } catch {
            // Silently ignore
        }
    }

    private func handleChannelMarkAsRead(services: ServiceContainer, radioID: UUID, channelIndex: UInt8, messageID: UUID) async {
        do {
            try await services.dataStore.markMessageAsRead(id: messageID)
            try await services.dataStore.clearChannelUnreadCount(radioID: radioID, index: channelIndex)
            services.notificationService.removeDeliveredNotification(messageID: messageID)
            await services.notificationService.updateBadgeCount()
            syncCoordinator?.notifyConversationsChanged()
        } catch {
            // Silently ignore
        }
    }

    private func handleRoomMarkAsRead(services: ServiceContainer, sessionID: UUID, messageID: UUID) async {
        do {
            try await services.roomServerService.markAsRead(sessionID: sessionID)
            services.notificationService.removeDeliveredNotification(messageID: messageID)
            await services.notificationService.updateBadgeCount()
            syncCoordinator?.notifyConversationsChanged()
        } catch {
            // Silently ignore
        }
    }

    /// Handle posting a notification when someone reacts to the user's message
    func handleReactionNotification(messageID: UUID) async {
        guard let services else { return }

        // Fetch the message to check if it's outgoing
        guard let message = try? await services.dataStore.fetchMessage(id: messageID),
              message.direction == .outgoing else {
            return
        }

        // Fetch the latest reaction for this message
        guard let reactions = try? await services.dataStore.fetchReactions(for: messageID, limit: 1),
              let latestReaction = reactions.first else {
            return
        }

        // Check if this is a self-reaction (user reacting to their own message)
        if let localNodeName = connectedDevice?.nodeName,
           latestReaction.senderName == localNodeName {
            return
        }

        // Check mute status based on message type
        let isMuted: Bool
        if let contactID = message.contactID {
            let contact = try? await services.dataStore.fetchContact(id: contactID)
            isMuted = contact?.isMuted ?? false
        } else if let channelIndex = message.channelIndex {
            let channel = try? await services.dataStore.fetchChannel(radioID: message.radioID, index: channelIndex)
            isMuted = channel?.isMuted ?? false
        } else {
            isMuted = false
        }

        guard !isMuted else { return }

        // Truncate preview if too long
        let truncatedPreview = message.text.count > 50
            ? String(message.text.prefix(47)) + "..."
            : message.text

        // Post the notification
        await services.notificationService.postReactionNotification(
            reactorName: latestReaction.senderName,
            body: L10n.Localizable.Notifications.Reaction.body(latestReaction.emoji, truncatedPreview),
            messageID: messageID,
            contactID: message.contactID,
            channelIndex: message.channelIndex,
            radioID: message.channelIndex != nil ? message.radioID : nil
        )
    }
}

// MARK: - Preview Support

extension AppState {
    /// Creates an AppState for previews using an in-memory container
    @MainActor
    convenience init() {
        let schema = Schema([
            Device.self,
            Contact.self,
            Message.self,
            Channel.self,
            RemoteNodeSession.self,
            RoomMessage.self
        ])
        let config = ModelConfiguration(isStoredInMemoryOnly: true)
        // swiftlint:disable:next force_try
        let container = try! ModelContainer(for: schema, configurations: [config])
        self.init(modelContainer: container)
    }
}

// MARK: - Environment Key

/// Environment key for AppState with safe default for background snapshot scenarios.
/// MainActor.assumeIsolated asserts we're on the main actor, which is always true
/// for SwiftUI environment access in views.
private struct AppStateKey: EnvironmentKey {
    static var defaultValue: AppState {
        MainActor.assumeIsolated {
            AppState()
        }
    }
}

extension EnvironmentValues {
    /// AppState environment value with safe default for background snapshot scenarios.
    /// Having a default value ensures a value is always available, preventing crashes when
    /// iOS takes app switcher snapshots or launches the app in background.
    var appState: AppState {
        get { self[AppStateKey.self] }
        set { self[AppStateKey.self] = newValue }
    }
}
