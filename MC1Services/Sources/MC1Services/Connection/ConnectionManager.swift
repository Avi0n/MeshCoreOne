import Foundation
import MeshCore
import SwiftData

/// Owns connection requests, installed stacks, retirement and radio metadata.
/// UI state, BLE phase and session state describe separate lifecycles.
@Observable
@MainActor
public final class ConnectionManager {
  // MARK: - Logging

  let logger = PersistentLogger(subsystem: "com.mc1", category: "ConnectionManager")

  // MARK: - Observable State

  /// Broadcasts every `connectionState` change. Per-connection consumers
  /// (`ServiceContainer`) subscribe at construction and end their
  /// subscription by task cancellation in `tearDown()`. `ConnectionManager`
  /// never calls `finish()`: it lives for the app lifetime, and the next
  /// connection's container subscribes to this same broadcaster.
  let connectionStateEvents = EventBroadcaster<DeviceConnectionState>()

  /// Current connection state
  public private(set) var connectionState: DeviceConnectionState = .disconnected {
    didSet {
      logLifecycle("state", context: lifecycleContext(request: desiredRequest, attempt: desiredRequest?.attempt, link: installedLink))
      connectionStateEvents.yield(connectionState)
      #if DEBUG
        assertStateInvariants()
      #endif
      if connectionState == .ready, oldValue != .ready, let snapshot = currentConnection { onConnectionBecameUsable?(snapshot) }
    }
  }

  /// Connected device info (nil when disconnected)
  public private(set) var connectedDevice: DeviceDTO?

  /// Allowed repeat frequency ranges from connected device (empty when disconnected or unsupported)
  public var allowedRepeatFreqRanges: [MeshCore.FrequencyRange] = []

  /// Services container (nil when disconnected)
  public private(set) var services: ServiceContainer?

  /// Current transport type (bluetooth or wifi)
  public private(set) var currentTransportType: TransportType?

  /// Current user-actionable Bluetooth availability, updated as `CBCentralManager` reports state
  /// changes. The macOS scan picker reads this to swap its scanning state for a remedy when
  /// Bluetooth is off or unauthorized; iOS surfaces the same conditions through its system picker.
  public internal(set) var bluetoothAvailability: BluetoothAvailability = .ready

  /// Detected device platform, used for sync throttling config.
  /// Survives reconnects (lives on ConnectionManager, not ServiceContainer).
  private(set) var detectedPlatform: DevicePlatform = .unknown

  /// Records the last fully-clean channel sync, keyed by device.
  /// Only set when channel sync completes with zero errors (including retries).
  /// Survives transient disconnects; cleared on explicit disconnect or device change.
  var lastCleanChannelSync: (radioID: UUID, completedAt: Date)?

  /// Records the last attempted channel sync, including partial/failed attempts.
  /// Used to cool down immediate channel-only retry loops.
  var lastAttemptedChannelSync: (radioID: UUID, attemptedAt: Date)?

  /// The user's connection intent. Replaces shouldBeConnected, userExplicitlyDisconnected, and pendingForceFullSync.
  var connectionIntent: ConnectionIntent = .none

  /// Device whose pairing-failure recovery has already been surfaced this failure
  /// episode. While the bond stays invalid every watchdog retry fails the same way,
  /// so without this latch the recovery alert would re-interrupt after each retry.
  /// Cleared on ready promotion and on explicit disconnect, opening a new episode.
  var surfacedAuthFailureDeviceID: UUID?

  /// True for the duration of pairNewDevice() — suppresses opportunistic
  /// reconnect paths so they can't race the pairing's `connect(to:)` for the
  /// BLE state machine's single in-flight connect slot.
  var isPairingInProgress = false

  /// True from scan/setup enqueue until cancel or `pairNewDevice` finishes,
  /// and while `deleteDevice` of the live or in-flight radio waits on iOS Remove Accessory.
  /// Distinct from `isPairingInProgress` so setup confirmation is not treated as a live picker.
  public var isPairingFlowActive = false

  /// Single source of truth for "stand down, an explicit connect flow is running."
  /// Opportunistic reconnect call sites consult this; or new conditions in here
  /// when the next contention class shows up, so every site picks them up.
  public var shouldDeferOpportunisticReconnect: Bool {
    isPairingInProgress || isPairingFlowActive || removalOperationID != nil
  }

  // MARK: - Callbacks

  /// Called when connection is ready and services are available.
  /// Use this to wire up UI observation of services.
  /// Installed by `AppState` during initialization.
  public var onConnectionReady: ((ConnectionSnapshot) async -> Void)?

  /// Called synchronously on entry into `.ready` so UI readiness precedes post-sync awaits.
  public var onConnectionBecameUsable: (@MainActor (ConnectionSnapshot) -> Void)?

  /// Called when connection is lost (disconnection, BLE power off, etc).
  /// Use this to update UI state when services become unavailable.
  /// Installed by `AppState` during initialization.
  public var onConnectionLost: ((ConnectionLossReceipt) async -> Void)?

  /// Called when a live link drops and iOS auto-reconnect begins;
  /// `connectionState` stays `.connecting` and `onConnectionLost` fires only if
  /// the reconnect is later lost or given up. Fires after the reconnect cycle is
  /// claimed and before session teardown, inside the CoreBluetooth disconnect
  /// wake window, so the Live Activity reflects the loss ahead of the heavy
  /// teardown work and before the app suspends.
  /// Installed by `AppState` during initialization.
  public var onAutoReconnectStarted: ((ConnectionLossReceipt) async -> Void)?

  /// Called when a background reconnect attempt fails because the peer
  /// invalidated its bond. Use this to present guided pairing-failure recovery,
  /// since the watchdog will otherwise keep retrying a bond that can't heal itself.
  /// Fires at most once per failure episode (see `surfacedAuthFailureDeviceID`).
  /// Installed by `AppState` during initialization.
  public var onAuthenticationFailure: ((UUID) -> Void)?

  /// Called after a usable sync promotes the connection to `.ready`.
  /// The flag is clean contacts and clean channels from that result, so a partial channel pass is `.ready` with false.
  /// Installed by `AppState` during initialization.
  public var onDeviceSynced: ((ConnectionSnapshot, _ hasCleanRecipientData: Bool) async -> Void)?

  /// Called when `clearPersistedConnection(for:)` clears the last-connected slot.
  /// Installed by `AppState` during initialization.
  public var onLastConnectedDeviceCleared: (@MainActor @Sendable () -> Void)?

  /// Provider for app foreground/background state detection.
  /// Installed by `AppState` during initialization.
  public var appStateProvider: AppStateProvider?

  /// Number of devices registered with the system pairing registry (for troubleshooting UI).
  /// iOS reports AccessorySetupKit accessories; macOS reports 0 (no system registry).
  public var pairedAccessoriesCount: Int {
    pairing.registeredDeviceCount
  }

  /// Whether the connected device can be renamed through a system rename surface.
  /// `true` on iOS (AccessorySetupKit rename sheet); `false` on macOS, where the UI must
  /// hide the rename action rather than offer a control that silently does nothing.
  public var supportsDeviceRename: Bool {
    pairing.supportsSystemRename
  }

  /// Whether this platform has an app-visible system pairing registry (AccessorySetupKit).
  /// `true` on iOS; `false` on macOS "Designed for iPad". The device picker uses this to
  /// decide whether registry membership or the stored connection method is the reachability
  /// signal, and the connect path uses it to bound a user-initiated connect's retry budget.
  public var hasSystemPairingRegistry: Bool {
    pairing.hasSystemPairingRegistry
  }

  /// Process-lifetime persistence actor, created once in `init`. Session
  /// services receive this instance; no production path mints a second one.
  public let persistenceStore: PersistenceStore

  // MARK: - Internal Components

  let modelContainer: ModelContainer
  private let defaults: UserDefaults
  private let lastConnectionStore: LastConnectionStore

  let textRulesURL: URL
  public internal(set) var textRulesPreference: TextRulesPreference?
  public internal(set) var isTextRulesLoading = true
  let transport: any iOSMeshTransport
  private(set) var wifiTransport: (any WiFiMeshTransport)?
  private(set) var session: MeshCoreSession? {
    didSet { invalidatePowerOffIfReplaced(newSession: session) }
  }

  var radioWiFiStates: [UUID: RadioWiFiState] = [:]
  var pendingIdentityReconciliations: [UUID: IdentityImportReceipt] = [:]
  #if DEBUG
    var sessionClockForTesting: (any Clock<Duration>)?
    var testIdentityBeforeAdmission: (@MainActor @Sendable () async -> Void)?
    var testIdentityBeforeSourceValidation: (@MainActor @Sendable () async -> Void)?
    var testIdentityBeforeCommit: (@MainActor @Sendable () async -> Void)?
    var testIdentityAfterCommit: (@MainActor @Sendable () async -> Void)?
  #endif
  var desiredRequest: ConnectionRequest?
  var installedLink: ConnectionLink?
  var currentLossReceipt: ConnectionLossReceipt?
  var retirementTask: Task<Void, Never>?
  var retiringWorkers: [Task<Void, Never>] = []
  var metadataCommitTask: Task<Void, Never>?
  var pairingOperationID: UUID?
  var removalDeviceID: UUID?
  var removalOperationID: UUID?
  var resyncSlotID: UUID?
  var channelRetrySlotID: UUID?
  var heartbeatSlotID: UUID?
  var wifiRecoverySlotID: UUID?
  let makeWiFiTransport: @Sendable () -> any WiFiMeshTransport

  public var currentConnection: ConnectionSnapshot? {
    installedLink?.snapshot
  }

  public func isCurrent(generation: UUID, requestID: UUID) -> Bool {
    installedLink?.generation == generation && desiredRequest?.id == requestID
  }

  public func isCurrent(_ snapshot: ConnectionSnapshot) -> Bool {
    installedLink?.generation == snapshot.generation && desiredRequest?.id == snapshot.requestID
  }

  public func isCurrent(_ receipt: ConnectionLossReceipt) -> Bool {
    desiredRequest?.id == receipt.requestID && installedLink == nil && currentLossReceipt?.id == receipt.id
  }

  public func isCurrent(_ result: ConnectionResult) -> Bool {
    switch result {
    case let .operational(snapshot): isCurrent(snapshot)
    case let .handoff(requestID): desiredRequest?.id == requestID
    case .superseded, .cancelled: false
    }
  }

  /// The in-flight power-off, if this manager still owns it.
  var powerOffOperation: PowerOffOperation?
  var powerOffCancelGeneration = 0
  var powerOffCleanupToken: UUID?
  var connectionTeardownCount = 0
  /// Device discovery + system pairing-registry seam. Resolves to AccessorySetupKit on
  /// iOS, or an in-app CoreBluetooth scan picker on macOS "Designed for iPad".
  let pairing: any DevicePairingService

  /// Non-nil only on macOS, where the scan picker UI must be presented by the view layer.
  /// nil on iOS, where AccessorySetupKit presents its own system picker.
  public var bluetoothScanPicker: BluetoothScanPairingService? {
    pairing as? BluetoothScanPairingService
  }

  /// Shared BLE state machine to manage connection lifecycle.
  /// This prevents state restoration race conditions that cause "API MISUSE" errors.
  let stateMachine: any BLEStateMachineProtocol

  /// Coordinates iOS auto-reconnect lifecycle (timeouts, teardown, rebuild).
  let reconnectionCoordinator: BLEReconnectionCoordinator
  let reconnectionClock: any Clock<Duration>

  // MARK: - WiFi Reconnection

  /// Task handling WiFi reconnection attempts
  var wifiReconnectTask: Task<Void, Never>?

  /// Current reconnection attempt number
  var wifiReconnectAttempt = 0

  /// Maximum duration for WiFi reconnection attempts (30 seconds)
  static let wifiMaxReconnectDuration: Duration = .seconds(30)

  /// Last reconnection start time (for rate limiting rapid disconnects)
  var lastWiFiReconnectStartTime: Date?

  /// Minimum interval between reconnection attempts (prevents flapping)
  static let wifiReconnectCooldown: TimeInterval = 35

  // MARK: - WiFi Heartbeat

  /// Task for periodic WiFi connection health checks
  var wifiHeartbeatTask: Task<Void, Never>?

  /// Interval between WiFi heartbeat probes (seconds)
  static let wifiHeartbeatInterval: Duration = .seconds(30)

  /// Task coordinating BLE scan startup to avoid start/stop races with stream termination.
  var bleScanTask: Task<Void, Never>?

  /// Monotonic token used to invalidate stale BLE scan requests.
  var bleScanRequestID: UInt64 = 0

  // MARK: - Resync State

  // Stored state for the sync retry loops; the loops themselves live in the
  // SyncRetry extension, which cannot add instance storage.

  /// Current resync attempt count (reset on success or disconnect)
  var resyncAttemptCount = 0

  /// Task managing the resync retry loop
  var resyncTask: Task<Void, Never>?

  public internal(set) var radioCleanup: [UUID: RadioCleanup] = [:]
  @ObservationIgnored var radioEraseTasks: [UUID: Task<ConnectionRemovalResult, Error>] = [:]

  /// Task managing delayed channel-only retry after a partial channel phase.
  var channelRetryTask: Task<Void, Never>?

  /// Callback when resync fails after all attempts (triggers "Sync Failed" pill)
  /// Note: @Sendable @MainActor ensures safe cross-isolation callback
  /// Installed by `ConnectionUIState.wireCallbacks`.
  public var onResyncFailed: (@Sendable @MainActor (ConnectionSnapshot) -> Void)?

  // MARK: - Circuit Breaker

  /// Prevents rapid reconnection loops after repeated failures.
  /// Closed → Open (30s cooldown) → Half-Open (single probe).
  private enum CircuitBreakerState {
    case closed
    case open(since: Date)
    case halfOpen
  }

  private var circuitBreaker: CircuitBreakerState = .closed
  private static let circuitBreakerCooldown: TimeInterval = 30

  /// Connect-retry budget for a single `connect(to:)` call.
  /// `default` applies to every attempt on a platform with a system pairing registry, and to
  /// all background reconnects. `unverified` bounds a *user-initiated* connect on a platform
  /// without a registry (macOS), where CoreBluetooth cannot pre-reject an absent cached
  /// peripheral, so the full budget would otherwise leave the user staring at a ~40s spinner.
  static let defaultConnectAttempts = 4
  static let unverifiedConnectAttempts = 2

  static let pairingAdoptionSettlePollInterval: Duration = .milliseconds(50)
  /// Pairing bound: rebuild can remain `.connected` after reconnect UI timeout stands down.
  static let pairingAdoptionSettleTimeout: Duration = .seconds(15)
  static let pairingAdoptionFailedDetail = "adoption failed"
  static let pairingAdoptionMissingSessionDetail = "adoption rebuilt no session"
  static let pairingAdoptionTimedOutDetail = "adoption timed out"

  /// Allows this many consecutive recovery failures to preserve the live BLE link.
  /// One failure can include two handshakes; exhaustion severs the link and waits
  /// for the reconnect watchdog to run while the process is awake.
  static let maxRebuildFailuresPreservingLink = 3

  /// Count of consecutive intent-wanting rebuild failures into `finishBLERecoveryFailure`.
  /// Reset on every operational session via `recordConnectionSuccess`.
  var consecutiveRebuildFailures = 0

  /// Checks whether a connection attempt should proceed.
  /// Returns `true` if the circuit breaker allows it.
  /// - Parameter force: When `true`, bypasses the circuit breaker (user-initiated reconnect)
  func shouldAllowConnection(force: Bool) -> Bool {
    if force { return true }

    switch circuitBreaker {
    case .closed:
      return true
    case let .open(since):
      if Date().timeIntervalSince(since) >= Self.circuitBreakerCooldown {
        circuitBreaker = .halfOpen
        logger.info("[BLE] Circuit breaker: open → halfOpen (cooldown elapsed)")
        return true
      }
      return false
    case .halfOpen:
      // Half-open: allow a single probe attempt to determine if the connection can be restored.
      return true
    }
  }

  /// Records a connection failure for circuit breaker tracking.
  /// Trips the breaker to `.open` when called after all retries are exhausted.
  func recordConnectionFailure() {
    switch circuitBreaker {
    case .closed:
      circuitBreaker = .open(since: Date())
      logger.warning("[BLE] Circuit breaker: closed → open (retries exhausted)")
    case .halfOpen:
      circuitBreaker = .open(since: Date())
      logger.warning("[BLE] Circuit breaker: halfOpen → open (probe failed)")
    case .open:
      break
    }
  }

  /// Records a successful connection, resetting the circuit breaker and the
  /// rebuild-preserve budget. The counter reset sits above the `.closed` early
  /// return so a healthy connection still refills the preserve budget.
  func recordConnectionSuccess() {
    consecutiveRebuildFailures = 0
    if case .closed = circuitBreaker { return }
    circuitBreaker = .closed
    logger.info("[BLE] Circuit breaker: → closed (connection succeeded)")
  }

  /// Epoch bumped by every `clearPersistedConnection` so a queued bond-refresh
  /// persist hop that already passed `shouldPersistBondRefresh` cannot write
  /// after a forget clears the shield.
  var bondRefreshPersistEpoch: UInt64 = 0

  // MARK: - Reconnection Watchdog

  /// Task managing the reconnection watchdog (retries when stuck disconnected)
  var reconnectionWatchdogTask: Task<Void, Never>?

  /// Generation token so a finishing watchdog Task cannot nil a replacement task.
  var reconnectionWatchdogGeneration = 0

  /// Session IDs that need re-authentication after BLE reconnect.
  /// Populated by `handleBLEDisconnection()`, consumed by `rebuildSession()`.
  /// Empty after app restart, so rooms show "Tap to reconnect" instead of auto-connecting.
  struct MembershipBatch {
    let id: UUID
    let radioID: UUID
    var sessionIDs: Set<UUID>
  }

  var membershipBatch: MembershipBatch?

  // MARK: - Simulator Support

  /// Simulator connection mode (used for demo mode on device)
  let simulatorMode = SimulatorConnectionMode()

  // Whether running in simulator mode
  #if targetEnvironment(simulator)
    public var isSimulatorMode: Bool {
      true
    }
  #else
    public var isSimulatorMode: Bool {
      false
    }
  #endif

  // MARK: - Last Device Persistence

  #if DEBUG
    /// Test override for lastConnectedDeviceID
    var testLastConnectedDeviceID: UUID?

    /// When true, `lastConnectedDeviceID` is nil so never-paired tests can
    /// drive `offlineDataStore` without touching the host UserDefaults.
    var testForceNeverPaired = false

    /// When set, the first watchdog sleep uses this instead of 30s so natural-exit
    /// tests can complete without waiting on production backoff.
    var testWatchdogInitialDelay: Duration?

    /// When set, WiFi reconnect gives up after this duration instead of 30s.
    var testWiFiMaxReconnectDuration: Duration?

    /// When set, a second WiFi reconnect inside this interval is suppressed.
    var testWiFiReconnectCooldown: TimeInterval?

    /// When set, the WiFi heartbeat loop sleeps on this clock instead of the wall clock.
    var testWiFiHeartbeatClock: (any Clock<Duration>)?

    var testConnectionTeardownHandler: (@MainActor () async -> Void)?
    var testNotificationSystemOperations: NotificationService.SystemOperations?
    var testConnectionSelectionHandler: (@MainActor (UUID) async throws -> Void)?
    var testCandidateRetirementHandler: (@MainActor (SessionTransportBinding) async -> Void)?
    var testAdmissionCancellationHandler: (@MainActor () async -> Void)?
    var testAdmissionCancellationFinished = false
    var testWiFiRecoveryEntryHandler: (@MainActor () async -> Void)?
    var testMessageListenerRepairHandler: (@MainActor (UUID) async -> Void)?
    var testPostSetupResultHandler: (@MainActor (UUID) async -> Void)?
    var testBLELifecycleEventHandled: (@MainActor (BLELifecycleEvent) -> Void)?

    /// How many times the Bluetooth powered-on handler has run.
    var testBluetoothPoweredOnHandlerRuns = 0

    /// Runs after `shouldPersistBondRefresh` returns true and before the epoch
    /// check in `persistBondRefreshIfStillValid` — tests force clear in that window.
    var bondRefreshPersistAfterShouldPersistHook: (@MainActor () async -> Void)?

    /// True when the BLE reconnection watchdog task is active.
    var isReconnectionWatchdogRunning: Bool {
      reconnectionWatchdogTask != nil
    }

    /// Strategy injection for `waitForOtherAppReconnection`. Tests use this to
    /// signal when pairing is suspended in the wait so they can drive racers
    /// deterministically before releasing the wait.
    typealias OtherAppWaitStrategy = @Sendable (UUID) async -> Bool

    var otherAppWaitStrategyOverride: OtherAppWaitStrategy?

    /// Pairing leftover-GATT tests settle adoption without `rebuildSession`.
    /// Invoked only after adoption started.
    typealias PairingAdoptionSettleStrategy = @MainActor () async throws -> Void

    var pairingAdoptionSettleStrategyOverride: PairingAdoptionSettleStrategy?

    var testPairingAdoptionSettleTimeout: Duration?

  #endif

  /// The last connected device ID (for auto-reconnect)
  public var lastConnectedDeviceID: UUID? {
    #if DEBUG
      if testForceNeverPaired { return nil }
      if let testID = testLastConnectedDeviceID {
        return testID
      }
    #endif
    return lastConnectionStore.deviceID
  }

  /// The last connected radio ID (for offline data scoping)
  public var lastConnectedRadioID: UUID? {
    lastConnectionStore.radioID
  }

  /// The last connected device name (for offline display when disconnected)
  public var lastConnectedDeviceName: String? {
    lastConnectionStore.deviceName
  }

  /// Records a successful connection for future restoration
  func persistConnection(deviceID: UUID, radioID: UUID, deviceName: String) {
    lastConnectionStore.persist(deviceID: deviceID, radioID: radioID, deviceName: deviceName)
  }

  /// Records that the device's BLE bond just completed a verified encrypted
  /// session. Called wherever session traffic flowed over the encrypted UART
  /// link (fresh connect, device switch, auto-reconnect rebuild); the radio
  /// gates that link behind MITM-bonded encryption, so any successful exchange
  /// proves the bond. Never called on the WiFi path, which bypasses the bond.
  /// Also marks the app session live for RSSI bond-shield refresh (awaited).
  func recordBondVerification(deviceID: UUID, binding: SessionTransportBinding) async {
    do {
      try binding.performIfActive { lastConnectionStore.persistBondVerification(deviceID: deviceID) }
    } catch { return }
    await stateMachine.recordBondVerification(deviceID: deviceID, at: Date(), sessionBinding: binding)
    await stateMachine.setAppSessionLive(deviceID: deviceID, sessionBinding: binding)
  }

  /// Clears this device's bond verification without touching the last-connection slot.
  /// Holder-matches the key, and bumps the epoch first, so another radio's shield stays and a queued refresh cannot write this one back.
  func clearBondVerification(for deviceID: UUID) async {
    bondRefreshPersistEpoch &+= 1
    await stateMachine.clearBondVerification(deviceID: deviceID)
    if await stateMachine.isAppSessionLive(deviceID: deviceID) {
      await stateMachine.setAppSessionLive(deviceID: nil)
    }
    lastConnectionStore.clearBondVerification(for: deviceID)
  }

  /// Clears bond verification and, when `deviceID` holds it, the last-connection slot.
  func clearPersistedConnection(for deviceID: UUID) async {
    await clearPersistedConnection(for: deviceID, notifyingRequestID: desiredRequest?.id)
  }

  func clearPersistedConnection(for deviceID: UUID, notifyingRequestID: UUID?) async {
    let wasLastConnected = lastConnectionStore.deviceID == deviceID
    await clearBondVerification(for: deviceID)
    lastConnectionStore.clearLastConnection(for: deviceID)
    if wasLastConnected, desiredRequest?.id == notifyingRequestID {
      onLastConnectedDeviceCleared?()
    }
  }

  /// Persist path for RSSI bond refresh: snapshot epoch, re-validate on the SM,
  /// then require the same epoch before writing UserDefaults so a clear that
  /// landed during the await cannot resurrect the cross-launch shield.
  func persistBondRefreshIfStillValid(deviceID: UUID) async {
    let epoch = bondRefreshPersistEpoch
    guard await stateMachine.shouldPersistBondRefresh(deviceID: deviceID) else { return }
    #if DEBUG
      if let bondRefreshPersistAfterShouldPersistHook {
        await bondRefreshPersistAfterShouldPersistHook()
      }
    #endif
    guard epoch == bondRefreshPersistEpoch else { return }
    lastConnectionStore.persistBondVerification(deviceID: deviceID)
  }

  /// Whether the disconnected pill should be suppressed (user explicitly disconnected)
  public var shouldSuppressDisconnectedPill: Bool {
    connectionIntent.isUserDisconnected
  }

  /// Most recent disconnect diagnostic summary persisted across app launches.
  public var lastDisconnectDiagnostic: String? {
    lastConnectionStore.disconnectDiagnostic
  }

  /// Current high-level connection intent, exported for diagnostics.
  public var connectionIntentSummary: String {
    switch connectionIntent {
    case .none:
      "none"
    case .userDisconnected:
      "userDisconnected"
    case let .wantsConnection(forceFullSync):
      forceFullSync ? "wantsConnection(forceFullSync: true)" : "wantsConnection"
    }
  }

  /// Whether the last connection was a simulator connection
  public var wasSimulatorConnection: Bool {
    lastConnectedDeviceID == MockDataProvider.simulatorDeviceID
  }

  var wifiDisconnectionReceipt: ConnectionLossReceipt?

  var isHandlingWiFiDisconnection: Bool {
    guard desiredRequest?.target.transportType == .wifi,
          let wifiDisconnectionReceipt else { return false }
    return isCurrent(wifiDisconnectionReceipt)
  }

  // MARK: - Cancellation Helpers

  /// Cancels any in-progress WiFi reconnection attempts
  func cancelWiFiReconnection() {
    if let wifiReconnectTask { retiringWorkers.append(wifiReconnectTask) }
    wifiRecoverySlotID = nil
    wifiReconnectTask?.cancel()
    wifiReconnectTask = nil
    wifiReconnectAttempt = 0
  }

  // MARK: - Initialization

  /// Creates a new connection manager.
  /// - Parameters:
  ///   - modelContainer: The SwiftData model container for persistence
  ///   - stateMachine: Optional BLE state machine for testing. If nil, creates a real BLEStateMachine.
  ///   - transport: Optional iOS mesh transport for testing. If nil, creates an `iOSBLETransport` against the chosen state machine.
  ///   - pairing: Optional pairing service for testing. If nil, `DevicePairingFactory` selects the platform implementation.
  public init(
    modelContainer: ModelContainer,
    defaults: UserDefaults = .standard,
    stateMachine: (any BLEStateMachineProtocol)? = nil,
    transport: (any iOSMeshTransport)? = nil,
    pairing: (any DevicePairingService)? = nil,
    reconnectionClock: (any Clock<Duration>)? = nil,
    makeWiFiTransport: @escaping @Sendable () -> any WiFiMeshTransport = { WiFiTransport() },
    textRulesURL: URL? = nil
  ) {
    self.modelContainer = modelContainer
    self.makeWiFiTransport = makeWiFiTransport
    let clock = reconnectionClock ?? ContinuousClock()
    self.reconnectionClock = clock
    reconnectionCoordinator = BLEReconnectionCoordinator(clock: clock)
    persistenceStore = PersistenceStore(modelContainer: modelContainer)
    self.defaults = defaults
    lastConnectionStore = LastConnectionStore(defaults: defaults)
    self.textRulesURL = textRulesURL ?? Self.defaultTextRulesURL
    connectionIntent = .restored(from: defaults)

    // Use provided state machine or create default
    let bleStateMachine = stateMachine ?? BLEStateMachine()
    self.stateMachine = bleStateMachine

    if let injected = transport {
      self.transport = injected
    } else if let concrete = bleStateMachine as? BLEStateMachine {
      self.transport = iOSBLETransport(stateMachine: concrete)
    } else {
      // Test mode without an injected transport: create a dummy (unused when mocking BLE)
      self.transport = iOSBLETransport(stateMachine: BLEStateMachine())
    }

    self.pairing = pairing ?? DevicePairingFactory.make()

    self.pairing.delegate = self
    wireReconnectPolicy()

    // Best-effort early wiring. activate() awaits the same idempotent
    // method before constructing the CBCentralManager, which is the
    // ordering guarantee; this Task just installs handlers promptly for
    // flows that touch the state machine before activate() runs.
    Task { await self.wireTransportHandlers() }
  }

  /// Wires the transport and state-machine lifecycle handlers.
  ///
  /// Must complete before anything constructs the CBCentralManager:
  /// creating the central can synchronously fire state-restoration
  /// callbacks, and a missed auto-reconnect or reconnection handler at
  /// launch leaves the restored link without a session rebuild. Idempotent;
  /// re-installing the same handlers is harmless.
  func wireTransportHandlers() async {
    let stateMachine = stateMachine
    let transport = transport

    await transport.setLifecycleHandler { [weak self] event in
      Task { @MainActor in await self?.handleBLELifecycleEvent(event) }
    }
    if let deviceID = lastConnectionStore.bondVerifiedDeviceID,
       let verified = lastConnectionStore.bondVerificationDate(for: deviceID) {
      await stateMachine.recordBondVerification(deviceID: deviceID, at: verified)
    }

    // RSSI bond refresh runs on the state-machine actor; persist on MainActor
    // via epoch-gated re-validation so a clear between shouldPersist and the
    // UserDefaults write cannot resurrect a forgotten cross-launch shield.
    await stateMachine.setBondRefreshedHandler { [weak self] deviceID in
      Task { @MainActor in
        guard let self else { return }
        await self.persistBondRefreshIfStillValid(deviceID: deviceID)
      }
    }

    // Handle Bluetooth power-cycle recovery
    await stateMachine.setBluetoothPoweredOnHandler { [weak self] in
      Task { @MainActor in
        await self?.handleBluetoothPoweredOn()
      }
    }

    // Handle Bluetooth state changes for diagnostics
    await stateMachine.setBluetoothStateChangeHandler { [weak self] state in
      Task { @MainActor in
        guard let self else { return }
        self.handleBluetoothStateChange(state)
      }
    }
  }

  /// Configures BLE write pacing based on detected device platform.
  /// - Parameter capabilities: The device capabilities from queryDevice()
  func configureBLEPacing(for capabilities: MeshCore.DeviceCapabilities, binding: SessionTransportBinding) async {
    do {
      try binding.performIfActive { detectAndStorePlatform(model: capabilities.model, transportType: .bluetooth) }
    } catch { return }
    let pacing = detectedPlatform.recommendedWritePacing
    await stateMachine.setWritePacingDelay(pacing, sessionBinding: binding)
    if pacing > 0 {
      logger.info("[BLE] Platform detected: \(capabilities.model) -> \(detectedPlatform), write pacing: \(pacing)s")
    }
  }

  /// Detects and stores the device platform from its model string, used for
  /// channel-sync throttling and (over BLE) write pacing.
  ///
  /// A WiFi radio is always ESP32-class — no nRF52 part ships a WiFi radio, and the
  /// WiFi companion firmware is ESP32-only — so an unrecognized model on WiFi resolves
  /// to `.esp32` rather than `.unknown`. Without this, WiFi radios would keep the
  /// no-skip `.unknown` config and re-read all channels on every sync. BLE keeps the
  /// conservative `.unknown` fallback, which drives its ESP32-safe write pacing.
  ///
  /// Lives here rather than in the BLE/WiFi extensions because `detectedPlatform` has a
  /// `private(set)` setter that only this file can write.
  func detectAndStorePlatform(model: String, transportType: TransportType) {
    var platform = DevicePlatform.detect(from: model)
    if transportType == .wifi, platform == .unknown {
      platform = .esp32
    }
    detectedPlatform = platform
    if transportType == .wifi {
      logger.info("[WiFi] Platform detected: \(model) -> \(platform)")
    }
  }

  // MARK: - Persisted Intent and Diagnostics

  func persistDisconnectDiagnostic(_ summary: String) {
    lastConnectionStore.persistDisconnectDiagnostic(summary)
  }

  func persistIntent() {
    connectionIntent.persist(to: defaults)
  }

  // MARK: - Owned Publication

  func publish(_ link: ConnectionLink, repeatRanges: [MeshCore.FrequencyRange]) throws {
    guard desiredRequest?.id == link.requestID, link.resource.binding.isActive else {
      logLifecycle("publicationRejected", context: lifecycleContext(request: nil, attempt: nil, link: link), reason: "requestOrBinding")
      throw CancellationError()
    }
    currentLossReceipt = nil
    installedLink = link
    session = link.resource.session
    services = link.services
    connectedDevice = link.device
    currentTransportType = link.target.transportType
    wifiTransport = link.resource.wifi
    allowedRepeatFreqRanges = repeatRanges
    connectionState = .syncing
    logLifecycle("published", context: lifecycleContext(request: desiredRequest, attempt: desiredRequest?.attempt, link: link))
  }

  func publishPending(_ request: ConnectionRequest, phase: DeviceConnectionState) {
    guard desiredRequest === request, installedLink == nil else {
      logLifecycle("publicationRejected", context: lifecycleContext(request: request, attempt: request.attempt, link: nil), reason: "requestOrInstalledLink")
      return
    }
    currentTransportType = phase == .disconnected ? nil : request.target.transportType
    connectionState = phase
  }

  @discardableResult
  func detachPublication(phase: DeviceConnectionState) -> ConnectionLink? {
    let old = installedLink
    installedLink = nil
    session = nil
    services = nil
    connectedDevice = nil
    wifiTransport = nil
    currentTransportType = nil
    allowedRepeatFreqRanges = []
    connectionState = phase
    return old
  }

  func transition(_ link: ConnectionLink, to state: DeviceConnectionState) {
    guard installedLink === link, desiredRequest?.id == link.requestID else {
      logLifecycle("transitionRejected", context: lifecycleContext(request: nil, attempt: nil, link: link), reason: "requestOrLink")
      return
    }
    connectionState = state
  }

  func setPublishedDevice(_ device: DeviceDTO) {
    guard let link = installedLink else {
      #if DEBUG
        connectedDevice = device
      #endif
      return
    }
    guard device.id == link.device.id else { return }
    link.device = device
    connectedDevice = device
  }

  // MARK: - State Invariants

  #if DEBUG
    private var suppressInvariantChecks = false

    private func assertStateInvariants() {
      guard !suppressInvariantChecks else { return }
      switch connectionState {
      case .ready, .syncing:
        assert(services != nil, "Invariant: \(connectionState) requires services")
        assert(session != nil, "Invariant: \(connectionState) requires session")
        assert(connectedDevice != nil, "Invariant: \(connectionState) requires connectedDevice")
      case .connected, .disconnected, .connecting:
        break
      }
      if connectionIntent.isUserDisconnected {
        assert(connectionState == .disconnected, "Invariant: .userDisconnected requires .disconnected state")
      }
    }
  #endif

  func makeBoundSession(transport: any MeshTransport, scope: RadioCLICorrelationScope) -> MeshCoreSession {
    let clock: (any Clock<Duration>)?
    #if DEBUG
      clock = sessionClockForTesting
    #else
      clock = nil
    #endif
    return MeshCoreSession(
      transport: transport, configuration: .default, clock: clock, radioCLICorrelationScope: scope
    )
  }

  // MARK: - Test Helpers

  #if DEBUG
    /// Sets the circuit breaker to `.open` with a chosen timestamp so tests can
    /// cross the cooldown boundary without waiting out the real cooldown.
    func setCircuitBreakerOpenForTesting(since: Date) {
      circuitBreaker = .open(since: since)
    }

    /// Sets internal state for testing. Only available in DEBUG builds.
    func setTestState(
      connectionState: DeviceConnectionState? = nil,
      services: ServiceContainer?? = nil,
      session: MeshCoreSession?? = nil,
      connectedDevice: DeviceDTO?? = nil,
      currentTransportType: TransportType?? = nil,
      wifiTransport: (any WiFiMeshTransport)?? = nil,
      connectionIntent: ConnectionIntent? = nil,
      connectingDeviceID: UUID?? = nil,
      sessionRebuildDeviceID: UUID?? = nil,
      isPairingInProgress: Bool? = nil,
      isPairingFlowActive: Bool? = nil,
      detectedPlatform: DevicePlatform? = nil,
      lastCleanChannelSync: (radioID: UUID, completedAt: Date)?? = nil,
      lastAttemptedChannelSync: (radioID: UUID, attemptedAt: Date)?? = nil
    ) {
      suppressInvariantChecks = true
      defer { suppressInvariantChecks = false }

      if let state = connectionState {
        self.connectionState = state
      }
      if let svc = services {
        self.services = svc
      }
      if let sess = session {
        self.session = sess
      }
      if let device = connectedDevice {
        self.connectedDevice = device
      }
      if let transport = currentTransportType {
        self.currentTransportType = transport
      }
      if let socket = wifiTransport { self.wifiTransport = socket }
      if let intent = connectionIntent {
        self.connectionIntent = intent
      }
      if let deviceID = connectingDeviceID {
        if let deviceID {
          desiredRequest = ConnectionRequest(
            targets: [.bluetooth(deviceID)], origin: .explicit, forceFullSync: false, forceReconnect: false,
            continuing: nil
          )
        } else if installedLink == nil {
          desiredRequest = nil
        }
      }
      if let deviceID = sessionRebuildDeviceID, let deviceID, let request = desiredRequest {
        _ = reconnectionCoordinator.begin(deviceID: deviceID, requestID: request.id)
      }
      if let pairing = isPairingInProgress {
        self.isPairingInProgress = pairing
      }
      if let pairingFlow = isPairingFlowActive {
        self.isPairingFlowActive = pairingFlow
      }
      if let platform = detectedPlatform {
        self.detectedPlatform = platform
      }
      if services != nil || session != nil || connectedDevice != nil || wifiTransport != nil,
         let liveSession = self.session, let liveServices = self.services, let device = self.connectedDevice {
        let target: ConnectionRequest.Target = self.currentTransportType == .wifi ? .wifi(host: "test", port: 5000) : .bluetooth(device.id)
        let request = desiredRequest ?? ConnectionRequest(
          targets: [target], origin: .explicit, forceFullSync: false, forceReconnect: false, continuing: nil
        )
        desiredRequest = request
        let physical: any MeshTransport = if let socket = self.wifiTransport { socket } else { self.transport }
        let resource = ConnectionResource(
          binding: SessionTransportBinding(), transport: physical, session: liveSession, wifi: self.wifiTransport,
          ble: self.wifiTransport == nil ? self.transport : nil
        )
        installedLink = ConnectionLink(requestID: request.id, target: target, resource: resource, services: liveServices, device: device)
        request.committedGeneration = installedLink?.generation
      } else if services != nil || session != nil || connectedDevice != nil {
        installedLink = nil
      }
      if let cleanSync = lastCleanChannelSync {
        self.lastCleanChannelSync = cleanSync
      }
      if let attemptedSync = lastAttemptedChannelSync {
        self.lastAttemptedChannelSync = attemptedSync
      }
    }
  #endif
}
