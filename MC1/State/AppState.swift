import CoreLocation
import MapKit
import MC1Services
import MeshCore
import OSLog
import SwiftData
import SwiftUI
import TipKit
import UserNotifications

/// Simplified app-wide state management.
/// Composes ConnectionManager for connection lifecycle.
/// Handles only UI state, navigation, and notification wiring.
@Observable
@MainActor
final class AppState {
  // MARK: - Logging

  let logger = Logger(subsystem: "com.mc1", category: "AppState")

  // MARK: - Location

  /// App-wide location service for permission management
  let locationService = LocationService()

  // MARK: - Offline Maps

  /// Offline map pack management and network monitoring
  let offlineMapService = OfflineMapService()

  // MARK: - Chat Drafts

  /// Disk-backed store for unsent chat-composer text. Recreated on
  /// before-first-unlock, but reads the same `UserDefaults` key, so the fresh
  /// instance recovers every persisted draft — no in-memory-lifetime dependency.
  let draftStore = DraftStore()

  /// Best available location for proximity-based disambiguation.
  var bestAvailableLocation: CLLocation? {
    if let phoneLocation = locationService.currentLocation {
      return phoneLocation
    }
    guard let device = connectedDevice, device.hasLocation else {
      return nil
    }
    return CLLocation(latitude: device.latitude, longitude: device.longitude)
  }

  /// Centers the map on the best available location if one is known, otherwise requests one.
  /// Returns whether the camera was moved, so callers can drive their `isCenteredOnUser` flag.
  @discardableResult
  func centerOnUserLocation(
    span: CLLocationDegrees = 0.02,
    setRegion: (MKCoordinateRegion) -> Void
  ) -> Bool {
    guard let location = bestAvailableLocation else {
      locationService.requestLocation()
      return false
    }
    setRegion(MKCoordinateRegion(
      center: location.coordinate,
      span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span)
    ))
    return true
  }

  // MARK: - Region preference

  @ObservationIgnored lazy var regionResolver = RegionResolver(location: locationService)

  /// Suppresses the `regionSelection` `didSet` write-back during cold-start load. Without
  /// this, `loadPersistedRegionSelection()` would re-encode the just-read JSON and rewrite
  /// the same bytes to UserDefaults on every launch.
  @ObservationIgnored private var suppressRegionPersist = false

  var regionSelection: RegionSelection? {
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
    regionSelection = decoded
  }

  // MARK: - Connection (via ConnectionManager)

  /// The connection manager for device lifecycle
  let connectionManager: ConnectionManager
  let storeState: StoreState
  let themeService: ThemeService
  private let bootstrapDebugLogBuffer: DebugLogBuffer

  /// Convenience accessors
  var connectionState: MC1Services.DeviceConnectionState {
    connectionManager.connectionState
  }

  var connectedDevice: DeviceDTO? {
    connectionManager.connectedDevice
  }

  var services: ServiceContainer? {
    connectionManager.services
  }

  /// Local node name with fallback for display purposes.
  var localNodeName: String {
    connectedDevice?.nodeName ?? "Me"
  }

  /// The sync coordinator for data synchronization
  private(set) var syncCoordinator: SyncCoordinator?

  /// Incremented when services change (device switch, reconnect). Views observe this to reload.
  private(set) var servicesVersion: Int = 0

  /// Identity of the `ServiceContainer` the last `servicesVersion` bump was for,
  /// so redundant re-wires of the same container don't bump it again.
  private var lastBumpedServicesID: ObjectIdentifier?

  // MARK: - Offline Data Access

  /// Per-conversation coordinator registry. Lives at AppState scope so
  /// chat detail screens render stored messages while disconnected. The
  /// registry's dataStore rebinds to services.dataStore on connect and is
  /// torn down on services-left.
  private(set) var chatCoordinatorRegistry: ChatCoordinatorRegistry?

  /// Cached standalone persistence store for offline browsing
  private var cachedOfflineStore: PersistenceStore?

  /// Radio ID for data access - returns connected device's radio ID or last-connected radio ID for offline browsing
  var currentRadioID: UUID? {
    connectedDevice?.radioID ?? connectionManager.lastConnectedRadioID
  }

  /// Data store that works regardless of connection state - uses services when connected,
  /// cached standalone store when disconnected
  var offlineDataStore: PersistenceStore? {
    if let services {
      cachedOfflineStore = nil // Clear cache when services available
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
  var contactsVersion: Int = 0

  /// Incremented when conversations data changes. Views observe this to reload chat lists.
  private(set) var conversationsVersion: Int = 0

  /// Incremented when a remote-node session changes connection state.
  /// `RoomConversationView` observes this counter via `.onChange` to refresh
  /// its session DTO when re-authentication completes.
  private(set) var sessionStateChangeCount: Int = 0

  /// Called by `MessageEventDispatcher` when a remote-node session changes
  /// connection state. Bundles refreshing conversations and bumping the
  /// state-change counter so the dispatcher only needs one entry point.
  func handleSessionStateChange() {
    refreshConversations()
    sessionStateChangeCount += 1
  }

  /// Bumps `conversationsVersion` and drives
  /// `liveActivityManager.handleUnreadCountChanged`. Used by both the sync
  /// coordinator's data event stream and the session-state events
  /// (`RemoteNodeEvent.sessionStateChanged` / `RoomServerEvent.connectionRecovered`)
  /// to keep the conversations list and unread badge in lockstep.
  func refreshConversations() {
    conversationsVersion += 1
    Task { @MainActor [weak self] in
      guard let self, let services else { return }
      let total = await totalUnreadCount(from: services)
      await liveActivityManager.handleUnreadCountChanged(unreadCount: total)
    }
  }

  /// Signals views observing `contactsVersion` / `conversationsVersion` to reload after
  /// a backup restore writes directly to the persistence store. The normal sync-path
  /// events don't fire for batch imports, so without this bump any currently-mounted
  /// tabs keep showing their pre-restore snapshot until reconnect or relaunch.
  /// Also re-reads the persisted region selection — the import wrote it to UserDefaults,
  /// but `regionSelection` is only loaded once during `init`, so Settings → Region and
  /// the radio-preset views would otherwise show pre-import data until next launch.
  func notifyDataRestored() {
    contactsVersion += 1
    conversationsVersion += 1
    loadPersistedRegionSelection()
    themeService.refreshFromUserDefaults()
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
  var bleLifecycleTransitionTask: Task<Void, Never>?

  /// Fallback task that re-runs foreground recovery shortly after activation when the
  /// app is still disconnected. Covers edge cases where scene-phase callbacks are missed.
  var activeRecoveryFallbackTask: Task<Void, Never>?

  /// Task consuming SettingsService event stream, canceled on disconnect
  var settingsEventsTask: Task<Void, Never>?

  /// Task consuming SyncCoordinator's data event stream, canceled on disconnect
  var syncDataEventsTask: Task<Void, Never>?

  /// Task consuming AdvertisementService's event stream, canceled on disconnect
  var advertisementEventsTask: Task<Void, Never>?

  /// Task consuming RxLogService's entry stream for Live Activity freshness, canceled on disconnect
  var rxLogEventsTask: Task<Void, Never>?

  #if DEBUG
    /// Optional test-only hooks for deterministic lifecycle ordering tests.
    var bleEnterBackgroundOverride: (@MainActor () async -> Void)?
    var bleBecomeActiveOverride: (@MainActor () async -> Void)?
  #endif

  // MARK: - Onboarding State

  /// Onboarding state (completion flag, navigation path)
  let onboarding = OnboardingState()

  // MARK: - What's New

  /// What's New presentation gate (device-local baseline, pending release)
  let whatsNew = WhatsNewState()

  // MARK: - Navigation State

  /// Navigation coordinator (tab selection, pending targets, cross-tab navigation)
  let navigation = NavigationCoordinator()

  // MARK: - UI Coordination

  /// AsyncStream-based distribution of `MessageEvent` to chat and room
  /// consumers. Fed by the service event streams wired in `wireMessageEvents`.
  let messageEventStream = MessageEventStream()

  /// Routes service event streams to `messageEventStream` and the
  /// session-state counter. Lazy because it captures `self` and
  /// `messageEventStream`.
  @ObservationIgnored
  lazy var messageEventDispatcher = MessageEventDispatcher(
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

  init(modelContainer: ModelContainer, isPlaceholder: Bool = false) {
    let bootstrapStore = PersistenceStore(modelContainer: modelContainer)
    let bootstrapBuffer = DebugLogBuffer(dataStore: bootstrapStore)
    bootstrapDebugLogBuffer = bootstrapBuffer
    // The inert environment-default placeholder must not publish the process-global buffer,
    // or it would displace the live one and route logs into a discarded in-memory store.
    if !isPlaceholder {
      DebugLogBuffer.shared = bootstrapBuffer
    }

    let store = StoreService()
    let theme = ThemeService(store: store)
    storeState = StoreState(service: store)
    themeService = theme

    connectionManager = ConnectionManager(modelContainer: modelContainer)

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

    // Live radioID for the stale-activity self-heal. Gated on the same
    // transport-link predicate as `connectionStateProvider`, so it returns a
    // radioID only while genuinely connected, never mid-reconnect.
    liveActivityManager.connectedRadioIDProvider = { [weak self] in
      guard let self, self.connectionState.isConnected else { return nil }
      return self.connectedDevice?.radioID
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

    // Wire auto-reconnect entry callback - reflects an out-of-range drop on the
    // Live Activity immediately, while connectionState is still .connecting.
    connectionManager.onAutoReconnectStarted = { [weak self] in
      await self?.liveActivityManager.handleConnectionLost()
    }

    // Wire background auth-failure callback - surfaces guided pairing-failure
    // recovery when an opportunistic reconnect finds the bond invalidated, but
    // only while active so a backgrounded failure can't latch a stale alert.
    connectionManager.onAuthenticationFailure = { [weak self] deviceID in
      self?.handleAuthenticationFailure(
        deviceID: deviceID,
        isAppActive: UIApplication.shared.applicationState == .active
      )
    }

    // Wire device synced callback - runs after sync completes and state is .ready
    connectionManager.onDeviceSynced = { [weak self] in
      self?.performStaleNodeCleanup()
    }

    loadPersistedRegionSelection()

    // The path suggestion drives real navigation; the placeholder has no scene to navigate,
    // so skip the work and the strong self-capture it would keep alive.
    if !isPlaceholder {
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
  }

  /// Releases process-scoped resources held by this `AppState` instance so the caller can
  /// drop it. Currently only cancels `StoreService`'s `Transaction.updates` listener Task,
  /// which otherwise self-retains via the `for await` loop and leaks across an `MC1App` BFU
  /// reassignment of `appState`. Connection-layer teardown stays on `ConnectionManager`'s own
  /// disconnect path and is intentionally not chained here.
  func shutdown() {
    storeState.service.shutdown()
  }

  // MARK: - Lifecycle

  /// Initialize on app launch
  func initialize() async {
    // Decide What's New before activate() can drive any connection alert. The
    // value of `hasCompletedOnboarding` read here is the "was onboarded at
    // launch" signal that distinguishes a brand-new install from an upgrader.
    #if DEBUG
      let isScreenshotMode = ProcessInfo.processInfo.isScreenshotMode
    #else
      let isScreenshotMode = false
    #endif
    whatsNew.evaluate(
      isOnboarded: onboarding.hasCompletedOnboarding,
      isScreenshotMode: isScreenshotMode
    )

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

  /// Per-session teardown shared by the connection-loss path and explicit
  /// disconnect, which does not fire onConnectionLost. Cancels the event tasks
  /// and releases the per-connection coordinators so a suspended task or a
  /// torn-down store reference cannot survive into the next session.
  func tearDownAppStateSessionState() {
    settingsEventsTask?.cancel()
    settingsEventsTask = nil
    syncDataEventsTask?.cancel()
    syncDataEventsTask = nil
    advertisementEventsTask?.cancel()
    advertisementEventsTask = nil
    rxLogEventsTask?.cancel()
    rxLogEventsTask = nil
    messageEventDispatcher.cancelAll()
    chatCoordinatorRegistry?.tearDown()
    chatCoordinatorRegistry = nil
    navigation.clearPendingLinks()
  }

  /// Presents the guided pairing-failure recovery for an invalidated bond, only
  /// while the app is active: a backgrounded failure must not latch a stale alert
  /// for the next foreground, where the reconnect re-surfaces it fresh if still bad.
  func handleAuthenticationFailure(deviceID: UUID, isAppActive: Bool) {
    guard isAppActive else { return }
    connectionUI.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.authenticationFailed))
  }

  /// Wire services-dependent callbacks after a successful connection.
  func wireServicesIfConnected() async {
    guard let services else {
      tearDownAppStateSessionState()
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
      lastBumpedServicesID = nil
      return
    }

    // The link is up, so drop any pairing-failure alert latched while
    // backgrounded before it can present over a working connection.
    connectionUI.clearPairingFailure()

    // Wire ConnectionUI callbacks (sync activity, node storage, pills, VoiceOver)
    // Must be set before onConnectionEstablished to avoid a race condition
    await connectionUI.wireCallbacks(
      syncCoordinator: services.syncCoordinator,
      advertisementService: services.advertisementService,
      contactService: services.contactService,
      connectionManager: connectionManager
    )

    // On a device switch onConnectionLost doesn't fire, so the disconnect teardown that
    // resets per-radio UI state is skipped. Reset the CLI and every per-radio detail selection
    // here so neither carries the previous radio's state into the new session.
    if let newDeviceID = connectedDevice?.id,
       let oldDeviceID = lastConnectedDeviceIDForCLI,
       newDeviceID != oldDeviceID {
      cliToolViewModel?.reset()
      navigation.clearPerRadioSelection()
    }
    lastConnectedDeviceIDForCLI = connectedDevice?.id

    // Store syncCoordinator reference
    syncCoordinator = services.syncCoordinator

    // Process-wide inline image cache learns where to persist probed dims.
    await InlineImageCache.shared.attachDimensionsStore(services.inlineImageDimensionsStore)

    // Demo mode ships an inline image whose bytes are embedded offline; pre-seed the
    // process-wide cache so the seeded DM renders it without a network fetch.
    if connectedDevice?.id == MockDataProvider.simulatorDeviceID {
      DemoInlineImageSeeder.seed()
    }

    if let existing = chatCoordinatorRegistry {
      existing.rebind(dataStore: services.dataStore)
    } else {
      chatCoordinatorRegistry = ChatCoordinatorRegistry(dataStore: services.dataStore)
    }

    wireSyncDataEvents(services: services)
    await wireSettingsEventStream(services: services)
    await wireDeviceUpdateCallbacks(services: services)
    wireMessageEvents(services: services)
    await wireLiveActivityCallbacks(services: services)

    // Drop drafts for channel slots vacated by a delete or sync prune so a
    // reused slot can't surface the prior channel's draft.
    await services.channelService.setDraftClearHandler { [weak self] radioID, indices in
      await MainActor.run {
        self?.draftStore.clearChannelDrafts(radioID: radioID, indices: indices)
      }
    }

    // Bump the version (which drives `.task(id:)` reloads in chat, tools, and
    // room views) only when the services container actually changed. A single
    // connect both fires `onConnectionReady` and is followed by an explicit
    // `wireServicesIfConnected()` call, and the Live Activity toggle re-wires the
    // same container — none of those are a services change, so they must not
    // trigger a reload.
    let servicesID = ObjectIdentifier(services)
    if lastBumpedServicesID != servicesID {
      lastBumpedServicesID = servicesID
      servicesVersion += 1

      // A real services change is the one moment the addressable contact/channel
      // set can differ from what saved shortcuts were resolved against, so re-resolve
      // the App Intents parameter queries here (debounced by this same guard, and past
      // the connection-lost early return so it never fires on disconnect).
      refreshAppShortcutParameters()
    }

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

    // Seed the badge from persisted unread messages now that the callback is wired; otherwise
    // badgeCount stays 0 until a message arrives or a chat opens and recomputes it.
    await services.notificationService.updateBadgeCount()

    // Configure notification interaction handlers
    configureNotificationHandlers()

    // Defer battery bootstrap so connection setup is not blocked by device request timeouts.
    batteryMonitor.start(services: services, device: connectedDevice)
  }

  // MARK: - Stale Node Cleanup

  /// Runs automatic cleanup of stale non-favorite nodes if the threshold is configured.
  /// - Parameter force: When `true`, skips the 6-hour cooldown (used when the user changes the setting).
  func performStaleNodeCleanup(force: Bool = false) {
    let threshold = UserDefaults.standard.integer(forKey: AppStorageKey.autoDeleteStaleNodesDays.rawValue)
    guard threshold > 0 else { return }

    if !force {
      let lastRunTimestamp = UserDefaults.standard.double(forKey: AppStorageKey.lastStaleCleanupDate.rawValue)
      let lastRun = lastRunTimestamp > 0 ? Date(timeIntervalSinceReferenceDate: lastRunTimestamp) : Date.distantPast
      guard Date().timeIntervalSince(lastRun) >= 3 * 3600 else {
        logger.debug("Stale node cleanup skipped — cooldown not expired")
        return
      }
    }

    Task {
      do {
        let result = try await connectionManager.removeStaleNodes(olderThanDays: threshold)
        UserDefaults.standard.set(Date().timeIntervalSinceReferenceDate, forKey: AppStorageKey.lastStaleCleanupDate.rawValue)
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

  /// Donates the tip unconditionally. Used on iPad where the radio is always
  /// visible in the sidebar regardless of which section is selected.
  func donateDeviceMenuTip() async {
    navigation.pendingDeviceMenuTipDonation = false
    await DeviceMenuTip.hasCompletedOnboarding.donate()
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
}

// MARK: - Preview Support

extension AppState {
  /// Creates an AppState for previews using an in-memory container
  @MainActor
  convenience init() {
    self.init(modelContainer: Self.makeInMemoryContainer())
  }

  /// In-memory container over the canonical `PersistenceStore.schema`, shared by preview and
  /// placeholder instances. Built directly rather than via `createContainer(inMemory:)`, which
  /// interns its container and would share one store across otherwise independent instances.
  private static func makeInMemoryContainer() -> ModelContainer {
    let config = ModelConfiguration(isStoredInMemoryOnly: true)
    // swiftlint:disable:next force_try
    return try! ModelContainer(for: PersistenceStore.schema, configurations: [config])
  }

  /// Shared inert stand-in backing the `appState` environment default. Built once and never
  /// wired to a connection: its services are nil, and it publishes no process-global state.
  static let placeholder = makePlaceholder()

  private static func makePlaceholder() -> AppState {
    let state = AppState(modelContainer: makeInMemoryContainer(), isPlaceholder: true)
    // Cancel the transaction listener the store service starts in init; the placeholder never
    // observes purchases, and the listener would otherwise self-retain for the process lifetime.
    state.shutdown()
    return state
  }
}

// A hand-written key rather than `@Entry`: the macro expands `defaultValue` into a computed
// property, which for a class type SwiftUI flags as reallocating on every read. A stored
// `static let` returns the one shared placeholder and silences that diagnostic.
// swiftformat:disable environmentEntry
private struct AppStateKey: EnvironmentKey {
  static let defaultValue = MainActor.assumeIsolated { AppState.placeholder }
}

extension EnvironmentValues {
  /// Inert stand-in returned when a view reads `appState` outside a configured scene, such as
  /// an app-switcher snapshot. `AppState.placeholder` is shared and never wired to a connection.
  var appState: AppState {
    get { self[AppStateKey.self] }
    set { self[AppStateKey.self] = newValue }
  }
}

// swiftformat:enable environmentEntry
