@preconcurrency import CoreBluetooth
import Foundation
import SwiftData
import MeshCore
import OSLog

/// Connection state for the mesh device
public enum ConnectionState: Sendable {
    case disconnected
    case connecting
    case connected
    case syncing
    case ready

    /// True when session and services are available and the transport is alive.
    /// Used by internal infrastructure (resync loop, health checks, heartbeat).
    /// UI code should check `== .ready` to gate user interactions.
    public var isOperational: Bool {
        self == .syncing || self == .ready
    }

    /// True when a transport link is established (session may or may not be synced).
    public var isConnected: Bool {
        switch self {
        case .connected, .syncing, .ready: true
        case .disconnected, .connecting: false
        }
    }
}

/// Transport type for the mesh connection
public enum TransportType: Sendable {
    case bluetooth
    case wifi
}

/// Errors that can occur during connection operations
public enum ConnectionError: LocalizedError {
    case connectionFailed(String)
    case deviceNotFound
    case notConnected
    case initializationFailed(String)

    public var errorDescription: String? {
        switch self {
        case .connectionFailed(let reason):
            return "Connection failed: \(reason)"
        case .deviceNotFound:
            return "Device not found"
        case .notConnected:
            return "Not connected to device"
        case .initializationFailed(let reason):
            return "Device initialization failed: \(reason)"
        }
    }
}

/// Errors that can occur during device pairing
public enum PairingError: LocalizedError {
    /// ASK pairing succeeded but BLE connection failed (e.g., wrong PIN)
    case connectionFailed(deviceID: UUID, underlying: Error)
    /// ASK pairing succeeded but device is connected to another app
    case deviceConnectedToOtherApp(deviceID: UUID)

    public var errorDescription: String? {
        switch self {
        case .connectionFailed(_, let underlying):
            return "Connection failed: \(underlying.localizedDescription)"
        case .deviceConnectedToOtherApp:
            return "Device is connected to another app."
        }
    }

    /// The device ID that failed to connect (for recovery UI)
    public var deviceID: UUID? {
        switch self {
        case .connectionFailed(let deviceID, _):
            return deviceID
        case .deviceConnectedToOtherApp(let deviceID):
            return deviceID
        }
    }

    /// True when the underlying BLE failure is an auth/encryption error.
    /// Detection is locale-independent: BLEStateMachine maps CBATTError auth
    /// codes (5/8/12/15) and CBError.encryptionTimedOut to BLEError.authenticationFailed
    /// at the throw site.
    public var isAuthenticationFailure: Bool {
        guard case .connectionFailed(_, let underlying) = self else { return false }
        if case BLEError.authenticationFailed = underlying { return true }
        return false
    }
}

/// Reasons for disconnecting from a device (for debugging)
public enum DisconnectReason: String, Sendable {
    case userInitiated = "user initiated disconnect"
    case statusMenuDisconnectTap = "status menu disconnect tapped"
    case switchingDevice = "switching to new device"
    case factoryReset = "device factory reset"
    case wifiAddressChange = "WiFi address changed"
    case resyncFailed = "resync failed after 3 attempts"
    case forgetDevice = "user forgot device"
    case deviceRemovedFromSettings = "device removed from iOS Settings"
    case pairingFailed = "device pairing failed"
    case wifiReconnectPrep = "preparing for WiFi reconnect"
}

/// Device platform type for BLE write pacing configuration
public enum DevicePlatform: Sendable {
    case esp32
    case nrf52
    case unknown

    /// Recommended write pacing delay for this platform
    var recommendedWritePacing: TimeInterval {
        switch self {
        case .esp32: return 0.060  // 60ms required by ESP32 BLE stack
        case .nrf52: return 0.025  // Light pacing to avoid RX queue pressure
        case .unknown: return 0.060  // Conservative ESP32-safe default for unrecognized devices
        }
    }

    /// Detects the device platform from the model string for BLE write pacing.
    ///
    /// Uses specific model substrings rather than vendor prefixes, because vendors like
    /// Heltec, RAK, Seeed, and Elecrow ship devices on multiple chip families.
    /// Unrecognized devices fall to `.unknown` (conservative 60ms pacing).
    public static func detect(from model: String) -> DevicePlatform {
        for rule in platformRules {
            if model.localizedStandardContains(rule.substring) {
                return rule.platform
            }
        }
        return .unknown
    }

    // Ordering matters: first match wins in detect(from:). More specific patterns must precede general ones within each platform group.
    private static let platformRules: [(substring: String, platform: DevicePlatform)] = [
        // ESP32 — Heltec
        ("Heltec V2", .esp32),
        ("Heltec V3", .esp32),
        ("Heltec V4", .esp32),
        ("Heltec Tracker", .esp32),
        ("Heltec E290", .esp32),
        ("Heltec E213", .esp32),
        ("Heltec T190", .esp32),
        ("Heltec CT62", .esp32),
        // ESP32 — LilyGo
        ("T-Beam", .esp32),
        ("T-Deck", .esp32),
        ("T-LoRa", .esp32),
        ("TLora", .esp32),
        // ESP32 — Seeed
        ("Xiao S3 WIO", .esp32),
        ("Xiao C3", .esp32),
        ("Xiao C6", .esp32),
        // ESP32 — RAK
        ("RAK 3112", .esp32),
        // ESP32 — M5Stack
        ("Unit C6L", .esp32),
        // ESP32 — Other
        ("Station G2", .esp32),
        ("Meshadventurer", .esp32),
        ("Generic ESP32", .esp32),
        ("ThinkNode M2", .esp32),
        ("ThinkNode M5", .esp32),
        // nRF52 — Heltec
        ("MeshPocket", .nrf52),
        ("Mesh Pocket", .nrf52),
        ("T114", .nrf52),
        ("Mesh Solar", .nrf52),
        // nRF52 — Seeed
        ("Xiao-nrf52", .nrf52),
        ("Xiao_nrf52", .nrf52),
        ("WM1110", .nrf52),
        ("Wio Tracker", .nrf52),
        ("T1000-E", .nrf52),
        ("SenseCap Solar", .nrf52),
        // nRF52 — RAK
        ("WisMesh Tag", .nrf52),
        ("RAK 4631", .nrf52),
        ("RAK 3401", .nrf52),
        // nRF52 — LilyGo
        ("T-Echo", .nrf52),
        // nRF52 — Elecrow
        ("ThinkNode-M1", .nrf52),
        ("ThinkNode M3", .nrf52),
        ("ThinkNode-M6", .nrf52),
        // nRF52 — GAT562
        ("GAT562", .nrf52),
        // nRF52 — Other
        ("Ikoka", .nrf52),
        ("ProMicro", .nrf52),
        ("Minewsemi", .nrf52),
        ("Meshtiny", .nrf52),
        ("Keepteen", .nrf52),
        ("Nano G2 Ultra", .nrf52),
    ]
}

/// Result of removing unfavorited nodes from the device
public struct RemoveUnfavoritedResult: Sendable {
    public let removed: Int
    public let total: Int
}

/// Manages the connection lifecycle for mesh devices.
///
/// `ConnectionManager` owns the transport, session, and services. It handles:
/// - Device pairing via AccessorySetupKit
/// - Connection and disconnection
/// - Auto-reconnect on connection loss
/// - Last-device persistence for app restoration
@MainActor
@Observable
public final class ConnectionManager {

    // MARK: - Logging

    let logger = PersistentLogger(subsystem: "com.mc1.services", category: "ConnectionManager")

    // MARK: - Observable State

    /// Current connection state
    public internal(set) var connectionState: ConnectionState = .disconnected {
        didSet {
            #if DEBUG
            assertStateInvariants()
            #endif
        }
    }

    /// Connected device info (nil when disconnected)
    public internal(set) var connectedDevice: DeviceDTO?

    /// Allowed repeat frequency ranges from connected device (empty when disconnected or unsupported)
    public var allowedRepeatFreqRanges: [MeshCore.FrequencyRange] = []

    /// Services container (nil when disconnected)
    public internal(set) var services: ServiceContainer?

    /// Current transport type (bluetooth or wifi)
    public internal(set) var currentTransportType: TransportType?

    /// Detected device platform, used for sync throttling config.
    /// Survives reconnects (lives on ConnectionManager, not ServiceContainer).
    private(set) var detectedPlatform: DevicePlatform = .unknown

    /// Records the last fully-clean channel sync, keyed by device.
    /// Only set when channel sync completes with zero errors (including retries).
    /// Survives transient disconnects; cleared on explicit disconnect or device change.
    var lastCleanChannelSync: (radioID: UUID, completedAt: Date)?

    /// The user's connection intent. Replaces shouldBeConnected, userExplicitlyDisconnected, and pendingForceFullSync.
    var connectionIntent: ConnectionIntent = .none

    /// The device being actively connected via connect(to:).
    /// Nil during auto-reconnect (tracked by reconnectionCoordinator.reconnectingDeviceID instead).
    var connectingDeviceID: UUID?

    /// The device whose MeshCore session is currently being rebuilt after a BLE auto-reconnect.
    /// Used to suppress duplicate reconnect attempts while session startup is still in flight.
    var sessionRebuildDeviceID: UUID?

    /// True for the duration of pairNewDevice() — suppresses opportunistic
    /// reconnect paths so they can't race the pairing's `connect(to:)` for the
    /// BLE state machine's single in-flight connect slot.
    var isPairingInProgress = false

    /// Single source of truth for "stand down, an explicit connect flow is running."
    /// Opportunistic reconnect call sites consult this; OR new conditions in here
    /// when the next contention class shows up, so every site picks them up.
    var shouldDeferOpportunisticReconnect: Bool {
        isPairingInProgress
    }

    /// Single chokepoint for opportunistic reconnect attempts. Consults the defer
    /// predicate and dispatches to `connect(to:)`. New opportunistic-reconnect
    /// sites must route through this helper rather than duplicating the gate logic.
    /// The auto-reconnect-handler closure (`setAutoReconnectingHandler`) operates
    /// on `.autoReconnecting` phases rather than initiating a `.connecting`, so it
    /// applies the same gate predicate at its own call site instead.
    /// - Parameters:
    ///   - deviceID: device to reconnect to. The caller is responsible for
    ///     vetting that the user wants this device connected before invoking.
    ///   - reason: short string for log correlation across call sites.
    func attemptOpportunisticReconnect(deviceID: UUID, reason: String) async {
        guard !shouldDeferOpportunisticReconnect else {
            logger.info("[BLE] Opportunistic reconnect skipped (\(reason)): pairing in progress")
            return
        }
        do {
            try await connect(to: deviceID)
        } catch {
            logger.warning("[BLE] Opportunistic reconnect (\(reason)) failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Callbacks

    /// Called when connection is ready and services are available.
    /// Use this to wire up UI observation of services.
    public var onConnectionReady: (() async -> Void)?

    /// Called when connection is lost (disconnection, BLE power off, etc).
    /// Use this to update UI state when services become unavailable.
    public var onConnectionLost: (() async -> Void)?

    /// Called after initial sync completes and connectionState becomes `.ready`.
    /// Use this for work that depends on up-to-date synced data (e.g. stale node cleanup).
    public var onDeviceSynced: (() async -> Void)?

    /// Provider for app foreground/background state detection
    public var appStateProvider: AppStateProvider?

    /// Number of paired accessories (for troubleshooting UI)
    public var pairedAccessoriesCount: Int {
        accessorySetupKit.pairedAccessories.count
    }

    /// Creates a standalone persistence store for operations that don't require services
    public func createStandalonePersistenceStore() -> PersistenceStore {
        PersistenceStore(modelContainer: modelContainer)
    }

    // MARK: - Internal Components

    let modelContainer: ModelContainer
    private let defaults: UserDefaults
    let transport: any iOSMeshTransport
    var wifiTransport: WiFiTransport?
    var session: MeshCoreSession?
    let accessorySetupKit: any AccessorySetupKitServicing

    /// Shared BLE state machine to manage connection lifecycle.
    /// This prevents state restoration race conditions that cause "API MISUSE" errors.
    let stateMachine: any BLEStateMachineProtocol

    /// Coordinates iOS auto-reconnect lifecycle (timeouts, teardown, rebuild).
    let reconnectionCoordinator = BLEReconnectionCoordinator()

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

    /// Current resync attempt count (reset on success or disconnect)
    private var resyncAttemptCount = 0

    /// Maximum resync attempts before giving up
    private static let maxResyncAttempts = 3

    /// Interval between resync attempts
    private static let resyncInterval: Duration = .seconds(2)

    /// Task managing the resync retry loop
    var resyncTask: Task<Void, Never>?

    /// Callback when resync fails after all attempts (triggers "Sync Failed" pill)
    /// Note: @Sendable @MainActor ensures safe cross-isolation callback
    public var onResyncFailed: (@Sendable @MainActor () -> Void)?

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

    /// Checks whether a connection attempt should proceed.
    /// Returns `true` if the circuit breaker allows it.
    /// - Parameter force: When `true`, bypasses the circuit breaker (user-initiated reconnect)
    func shouldAllowConnection(force: Bool) -> Bool {
        if force { return true }

        switch circuitBreaker {
        case .closed:
            return true
        case .open(let since):
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

    /// Records a successful connection, resetting the circuit breaker.
    func recordConnectionSuccess() {
        if case .closed = circuitBreaker { return }
        circuitBreaker = .closed
        logger.info("[BLE] Circuit breaker: → closed (connection succeeded)")
    }

    // MARK: - Reconnection Watchdog

    /// Task managing the reconnection watchdog (retries when stuck disconnected)
    var reconnectionWatchdogTask: Task<Void, Never>?

    /// Session IDs that need re-authentication after BLE reconnect.
    /// Populated by `handleBLEDisconnection()`, consumed by `rebuildSession()`.
    /// Empty after app restart, so rooms show "Tap to reconnect" instead of auto-connecting.
    var sessionsAwaitingReauth: Set<UUID> = []

    // MARK: - Persistence Keys

    private let lastDeviceIDKey = PersistenceKeys.lastConnectedDeviceID
    private let lastDeviceNameKey = PersistenceKeys.lastConnectedDeviceName
    private let lastRadioIDKey = PersistenceKeys.lastConnectedRadioID
    private let lastDisconnectDiagnosticKey = "com.pocketmesh.lastDisconnectDiagnostic"

    // MARK: - Simulator Support

    /// Simulator connection mode (used for demo mode on device)
    let simulatorMode = SimulatorConnectionMode()

    /// Whether running in simulator mode
    #if targetEnvironment(simulator)
    public var isSimulatorMode: Bool { true }
    #else
    public var isSimulatorMode: Bool { false }
    #endif

    // MARK: - Last Device Persistence

    #if DEBUG
    /// Test override for lastConnectedDeviceID
    internal var testLastConnectedDeviceID: UUID?

    /// True when the BLE reconnection watchdog task is active.
    internal var isReconnectionWatchdogRunning: Bool {
        reconnectionWatchdogTask != nil
    }

    /// Strategy injection for `waitForOtherAppReconnection`. Tests use this to
    /// signal when pairing is suspended in the wait so they can drive racers
    /// deterministically before releasing the wait.
    typealias OtherAppWaitStrategy = @Sendable (UUID) async -> Bool

    internal var otherAppWaitStrategyOverride: OtherAppWaitStrategy?
    #endif

    /// The last connected device ID (for auto-reconnect)
    public var lastConnectedDeviceID: UUID? {
        #if DEBUG
        if let testID = testLastConnectedDeviceID {
            return testID
        }
        #endif
        guard let uuidString = defaults.string(forKey: lastDeviceIDKey) else {
            return nil
        }
        return UUID(uuidString: uuidString)
    }

    /// The last connected radio ID (for offline data scoping)
    public var lastConnectedRadioID: UUID? {
        guard let uuidString = defaults.string(forKey: lastRadioIDKey) else {
            return nil
        }
        return UUID(uuidString: uuidString)
    }

    /// Records a successful connection for future restoration
    func persistConnection(deviceID: UUID, radioID: UUID, deviceName: String) {
        defaults.set(deviceID.uuidString, forKey: lastDeviceIDKey)
        defaults.set(radioID.uuidString, forKey: lastRadioIDKey)
        defaults.set(deviceName, forKey: lastDeviceNameKey)
    }

    /// Clears the persisted connection
    func clearPersistedConnection() {
        defaults.removeObject(forKey: lastDeviceIDKey)
        defaults.removeObject(forKey: lastRadioIDKey)
        defaults.removeObject(forKey: lastDeviceNameKey)
    }

    /// Whether the disconnected pill should be suppressed (user explicitly disconnected)
    public var shouldSuppressDisconnectedPill: Bool {
        connectionIntent.isUserDisconnected
    }

    /// Most recent disconnect diagnostic summary persisted across app launches.
    public var lastDisconnectDiagnostic: String? {
        defaults.string(forKey: lastDisconnectDiagnosticKey)
    }

    /// Current high-level connection intent, exported for diagnostics.
    public var connectionIntentSummary: String {
        switch connectionIntent {
        case .none:
            return "none"
        case .userDisconnected:
            return "userDisconnected"
        case .wantsConnection(let forceFullSync):
            return forceFullSync ? "wantsConnection(forceFullSync: true)" : "wantsConnection"
        }
    }

    /// Whether the last connection was a simulator connection
    public var wasSimulatorConnection: Bool {
        lastConnectedDeviceID == MockDataProvider.simulatorDeviceID
    }

    /// Whether a WiFi disconnection is currently being handled (prevents interleaving
    /// across await suspension points before wifiReconnectTask is set).
    var isHandlingWiFiDisconnection = false

    // MARK: - Cancellation Helpers

    /// Cancels any in-progress WiFi reconnection attempts
    func cancelWiFiReconnection() {
        wifiReconnectTask?.cancel()
        wifiReconnectTask = nil
        wifiReconnectAttempt = 0
    }

    /// Cancels any resync retry loop in progress.
    /// The cancelled task's catch-all calls endResyncActivity(succeeded: false) asynchronously,
    /// but callers that also trigger handleDisconnect don't need to wait for it —
    /// handleDisconnect zeroes syncActivityCount independently, and the onEnded callback's
    /// guard (syncActivityCount > 0) prevents underflow.
    func cancelResyncLoop() {
        resyncTask?.cancel()
        resyncTask = nil
        resyncAttemptCount = 0
    }

    // MARK: - Initial Sync

    /// Performs initial sync with automatic resync loop on failure.
    /// Returns `true` if sync completed successfully, `false` if it failed and a resync loop was started.
    /// - Parameters:
    ///   - radioID: The radio ID for data scoping
    ///   - services: The service container
    ///   - transportType: The transport being used (determines whether BLE throttling applies)
    ///   - context: Optional context string for logging (e.g., "WiFi reconnect")
    ///   - forceFullSync: When true, forces complete data exchange regardless of sync state
    func performInitialSync(
        radioID: UUID,
        services: ServiceContainer,
        transportType: TransportType = .bluetooth,
        context: String = "",
        forceFullSync: Bool = false
    ) async -> Bool {
        let channelSyncConfig = currentChannelSyncConfig(for: radioID, transportType: transportType)
        do {
            try await withTimeout(.seconds(120), operationName: "performInitialSync") {
                try await services.syncCoordinator.onConnectionEstablished(
                    radioID: radioID,
                    services: services,
                    forceFullSync: forceFullSync,
                    channelSyncConfig: channelSyncConfig,
                    platformName: "\(self.detectedPlatform)"
                )
            }
            return true
        } catch {
            // Don't start resync if user disconnected while sync was in progress
            guard connectionIntent.wantsConnection else { return false }
            let prefix = context.isEmpty ? "" : "\(context): "
            logger.warning("\(prefix)Initial sync failed, starting resync loop: \(error.localizedDescription)")
            startResyncLoop(radioID: radioID, services: services, transportType: transportType, forceFullSync: forceFullSync)
            return false
        }
    }

    /// Starts a retry loop to resync after initial sync failure.
    /// Retries every 2 seconds, shows "Sync Failed" pill and disconnects after 3 failures.
    /// Holds a sync activity bracket so the "Syncing" pill stays visible across retries.
    /// - Parameters:
    ///   - radioID: The radio ID for data scoping
    ///   - services: The ServiceContainer with all services
    ///   - forceFullSync: When true, forces complete data exchange regardless of sync state
    func startResyncLoop(
        radioID: UUID,
        services: ServiceContainer,
        transportType: TransportType = .bluetooth,
        forceFullSync: Bool = false
    ) {
        resyncTask?.cancel()
        resyncAttemptCount = 0

        // Note: No [weak self] needed - Task is stored property, self is @MainActor class.
        // Task inherits MainActor isolation, no retain cycle risk.
        resyncTask = Task {
            // Hold sync activity for the entire resync loop so the "Syncing" pill stays visible.
            // Must be inside the task body: placing it before task assignment introduced a
            // suspension point where resyncTask was still nil, breaking the dedup guard in
            // checkSyncHealth().
            await services.syncCoordinator.beginResyncActivity()
            var didEndResyncActivity = false

            while !Task.isCancelled {
                try? await Task.sleep(for: Self.resyncInterval)
                guard !Task.isCancelled else { break }

                guard connectionIntent.wantsConnection,
                      connectionState.isOperational else { break }

                resyncAttemptCount += 1
                logger.info("Resync attempt \(resyncAttemptCount)/\(Self.maxResyncAttempts)")

                let channelSyncConfig = self.currentChannelSyncConfig(for: radioID, transportType: transportType)
                let success: Bool
                do {
                    success = try await withTimeout(.seconds(60), operationName: "performResync") {
                        await services.syncCoordinator.performResync(
                            radioID: radioID,
                            services: services,
                            forceFullSync: forceFullSync,
                            channelSyncConfig: channelSyncConfig,
                            platformName: "\(self.detectedPlatform)"
                        )
                    }
                } catch {
                    logger.warning("Resync timed out: \(error.localizedDescription)")
                    success = false
                }

                if success {
                    logger.info("Resync succeeded")
                    resyncAttemptCount = 0

                    // Run post-sync hooks deferred when initial sync failed.
                    // Guard each await: disconnect(), device switch, or a new
                    // reconnect cycle may have torn down the connection.
                    guard !Task.isCancelled,
                          connectionIntent.wantsConnection,
                          connectionState.isOperational,
                          self.services === services else { break }

                    await syncDeviceTimeIfNeeded()

                    guard !Task.isCancelled,
                          connectionIntent.wantsConnection,
                          connectionState.isOperational,
                          self.services === services else { break }

                    // Re-authenticate room sessions before onDeviceSynced to avoid
                    // BLE contention with stale node cleanup's fire-and-forget Task.
                    let sessionIDs = sessionsAwaitingReauth
                    if !sessionIDs.isEmpty {
                        await services.remoteNodeService.handleBLEReconnection(sessionIDs: sessionIDs)
                    }

                    guard !Task.isCancelled,
                          connectionIntent.wantsConnection,
                          connectionState.isOperational,
                          self.services === services else { break }

                    // Report success only after confirming the loop is still authoritative.
                    // Earlier placement fired the "Ready" toast before these guards,
                    // relying on handleDisconnect as an accidental backstop.
                    await services.syncCoordinator.endResyncActivity(succeeded: true)
                    didEndResyncActivity = true

                    // Only clear consumed IDs after confirming the loop is still valid.
                    // Any IDs appended during the await (via teardownSessionForReconnect) survive.
                    sessionsAwaitingReauth.subtract(sessionIDs)

                    // Promote from .syncing to .ready now that sync completed.
                    // Not using promoteToReady() because: (1) its guards (services identity,
                    // connectionIntent) are already checked above, and (2) it would re-run
                    // time sync and onDeviceSynced, duplicating the resync loop's own post-sync work.
                    connectionState = .ready

                    await onDeviceSynced?()

                    break
                }

                if resyncAttemptCount >= Self.maxResyncAttempts {
                    logger.warning("Resync failed \(Self.maxResyncAttempts) times, disconnecting")
                    await services.syncCoordinator.endResyncActivity(succeeded: false)
                    didEndResyncActivity = true
                    onResyncFailed?()
                    await disconnect(reason: .resyncFailed)
                    break
                }
            }

            // Catch-all for cancellation or guard exits
            if !didEndResyncActivity {
                await services.syncCoordinator.endResyncActivity(succeeded: false)
            }

            // Only nil resyncTask if this task wasn't cancelled. When startResyncLoop()
            // is called while a previous loop is running, it cancels the old task and
            // assigns a new one. The old task's catch-all must not nil resyncTask or it
            // would destroy the replacement.
            if !Task.isCancelled {
                resyncTask = nil
            }
        }
    }

    // MARK: - Initialization

    /// Creates a new connection manager.
    /// - Parameters:
    ///   - modelContainer: The SwiftData model container for persistence
    ///   - stateMachine: Optional BLE state machine for testing. If nil, creates a real BLEStateMachine.
    ///   - transport: Optional iOS mesh transport for testing. If nil, creates an `iOSBLETransport` against the chosen state machine.
    ///   - accessorySetupKit: Optional ASK picker service for testing. If nil, creates a real `AccessorySetupKitService`.
    public init(
        modelContainer: ModelContainer,
        defaults: UserDefaults = .standard,
        stateMachine: (any BLEStateMachineProtocol)? = nil,
        transport: (any iOSMeshTransport)? = nil,
        accessorySetupKit: (any AccessorySetupKitServicing)? = nil
    ) {
        self.modelContainer = modelContainer
        self.defaults = defaults
        self.connectionIntent = .restored(from: defaults)

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

        self.accessorySetupKit = accessorySetupKit ?? AccessorySetupKitService()

        self.accessorySetupKit.delegate = self
        reconnectionCoordinator.delegate = self

        // Wire up transport handlers
        Task { [stateMachine = self.stateMachine, transport = self.transport] in
            // Handle disconnection events
            await transport.setDisconnectionHandler { [weak self] deviceID, error in
                Task { @MainActor in
                    guard let self else { return }
                    await self.handleConnectionLoss(deviceID: deviceID, error: error)
                }
            }

            // Handle entering auto-reconnecting phase
            await stateMachine.setAutoReconnectingHandler { [weak self] (deviceID: UUID, errorInfo: String) in
                Task { @MainActor in
                    guard let self else { return }
                    let initialState = String(describing: self.connectionState)
                    let transportName = switch self.currentTransportType {
                    case .bluetooth: "bluetooth"
                    case .wifi: "wifi"
                    case nil: "none"
                    }
                    let bleState = await self.stateMachine.centralManagerStateName
                    let blePhase = await self.stateMachine.currentPhaseName
                    let blePeripheralState = await self.stateMachine.currentPeripheralState ?? "none"

                    self.persistDisconnectDiagnostic(
                        "source=bleStateMachine.autoReconnectingHandler, " +
                        "device=\(deviceID.uuidString.prefix(8)), " +
                        "transport=\(transportName), " +
                        "initialState=\(initialState), " +
                        "bleState=\(bleState), " +
                        "blePhase=\(blePhase), " +
                        "blePeripheralState=\(blePeripheralState), " +
                        "error=\(errorInfo), " +
                        "intent=\(self.connectionIntent)"
                    )

                    if self.shouldDeferOpportunisticReconnect {
                        self.logger.info(
                            "[BLE] Auto-reconnect entry suppressed for \(deviceID.uuidString.prefix(8)) (pairing in progress) — tearing down stale session"
                        )
                        // Skip the reconnect-cycle claim and UI timeout (pairing's
                        // connect(to:) ceremony owns the next state transitions),
                        // but tear down the OLD session so a pairing early-exit
                        // path doesn't strand the UI on stale `.ready` state.
                        await self.handleConnectionLoss(deviceID: deviceID, error: nil)
                        return
                    }

                    await self.reconnectionCoordinator.handleEnteringAutoReconnect(deviceID: deviceID)
                }
            }

            // Handle iOS auto-reconnect completion
            // Using transport.setReconnectionHandler ensures the transport captures
            // the data stream internally before calling our handler
            await transport.setReconnectionHandler { [weak self] deviceID in
                Task { @MainActor in
                    guard let self else { return }
                    await self.reconnectionCoordinator.handleReconnectionComplete(deviceID: deviceID)
                }
            }

            // Handle Bluetooth power-cycle recovery
            await stateMachine.setBluetoothPoweredOnHandler { [weak self] in
                Task { @MainActor in
                    guard let self,
                          self.connectionIntent.wantsConnection,
                          self.connectionState == .disconnected,
                          let deviceID = self.lastConnectedDeviceID else { return }

                    if self.shouldDeferOpportunisticReconnect {
                        self.logger.info("[BLE] Bluetooth powered on: standing down (pairing in progress)")
                        return
                    }

                    let blePhase = await self.stateMachine.currentPhaseName
                    let bleConnectedDeviceID = await self.stateMachine.connectedDeviceID
                    if blePhase != "idle" || bleConnectedDeviceID == deviceID {
                        self.logger.info(
                            "[BLE] Bluetooth powered on: BLE already owns reconnect flow for \(deviceID.uuidString.prefix(8)) " +
                            "(phase: \(blePhase), bleConnectedDevice: \(bleConnectedDeviceID?.uuidString.prefix(8) ?? "none"))"
                        )
                        return
                    }

                    if self.activeReconnectDeviceID == deviceID {
                        self.logger.info("[BLE] Bluetooth powered on: reconnect/session rebuild already in progress for \(deviceID.uuidString.prefix(8))")
                        return
                    }

                    self.logger.info("[BLE] Bluetooth powered on: attempting reconnection to \(deviceID.uuidString.prefix(8))")
                    await self.attemptOpportunisticReconnect(deviceID: deviceID, reason: "Bluetooth powered on")
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
    }

    // MARK: - Session Helpers

    /// Starts a session and queries device capabilities.
    func initializeSession(
        _ session: MeshCoreSession
    ) async throws -> (SelfInfo, DeviceCapabilities) {
        do {
            try await withTimeout(.seconds(10), operationName: "session.start") {
                try await session.start()
            }
        } catch {
            logger.warning("[BLE] session.start() timed out or failed: \(error.localizedDescription)")
            throw error
        }

        guard let selfInfo = await session.currentSelfInfo else {
            logger.warning("[BLE] selfInfo is nil after session.start()")
            throw ConnectionError.initializationFailed("Failed to get device self info")
        }
        do {
            let capabilities = try await withTimeout(.seconds(10), operationName: "queryDevice") {
                try await session.queryDevice()
            }
            return (selfInfo, capabilities)
        } catch {
            logger.warning("[BLE] queryDevice() timed out or failed: \(error.localizedDescription)")
            throw error
        }
    }

    // MARK: - Service Wiring Helpers

    /// Wires the clean-channel-sync callback on a new ServiceContainer so that
    /// `lastCleanChannelSync` is updated when a channel phase completes without errors.
    /// Called from every path that creates a new ServiceContainer.
    func wireCleanChannelSyncCallback(on services: ServiceContainer) async {
        await services.syncCoordinator.setCleanChannelSyncCallback { [weak self] radioID in
            await MainActor.run {
                self?.lastCleanChannelSync = (radioID: radioID, completedAt: Date())
            }
        }
    }

    // MARK: - Connection Ceremony

    /// Wires a fresh ServiceContainer, fetches device configuration from the radio
    /// and database, builds and persists the device record, and updates self.
    ///
    /// Each connection path (BLE, WiFi, reconnect, device switch) calls this after
    /// its transport and session are established. Post-ceremony work (sync, promote,
    /// cleanup) remains in each caller since it genuinely varies.
    ///
    /// - Returns: The wired `ServiceContainer` for the caller's sync phase.
    func buildServicesAndSaveDevice(
        deviceID: UUID,
        session: MeshCoreSession,
        selfInfo: SelfInfo,
        capabilities: DeviceCapabilities,
        connectionMethods: [ConnectionMethod] = []
    ) async throws -> ServiceContainer {
        let newServices = ServiceContainer(
            session: session,
            modelContainer: modelContainer,
            appStateProvider: appStateProvider
        )
        await newServices.wireServices()
        await wireCleanChannelSyncCallback(on: newServices)
        self.services = newServices

        async let existingDeviceResult = newServices.dataStore.fetchDevice(id: deviceID)
        async let autoAddConfigResult = session.getAutoAddConfig()
        let existingDevice = try? await existingDeviceResult
        let autoAddConfig = (try? await autoAddConfigResult) ?? MeshCore.AutoAddConfig(bitmask: 0)

        // If no device found by BLE UUID, check by publicKey (backup import scenario)
        let deviceByPublicKey: DeviceDTO?
        if existingDevice == nil {
            deviceByPublicKey = try? await newServices.dataStore.fetchDevice(publicKey: selfInfo.publicKey)
        } else {
            deviceByPublicKey = nil
        }
        let effectiveExisting = existingDevice ?? deviceByPublicKey

        let repeatFreqRanges: [MeshCore.FrequencyRange] = capabilities.clientRepeat
            ? (try? await session.getRepeatFreq()) ?? []
            : []

        let device = createDevice(
            deviceID: deviceID,
            selfInfo: selfInfo,
            capabilities: capabilities,
            autoAddConfig: autoAddConfig,
            existingDevice: effectiveExisting,
            connectionMethods: connectionMethods
        )

        let deviceDTO = DeviceDTO(from: device)
        try await newServices.dataStore.saveDevice(deviceDTO)

        // Clean up orphaned Device row from backup import
        if let oldDevice = deviceByPublicKey, oldDevice.id != deviceID {
            try? await newServices.dataStore.deleteDevice(id: oldDevice.id)
        }

        self.connectedDevice = deviceDTO
        self.allowedRepeatFreqRanges = repeatFreqRanges

        return newServices
    }

    // MARK: - Channel Sync Configuration

    /// Builds a channel sync config for the current device and transport.
    /// WiFi connections skip channel-sync gating; BLE connections use platform-specific values.
    private func currentChannelSyncConfig(for radioID: UUID, transportType: TransportType) -> ChannelSyncConfig {
        guard transportType != .wifi else { return .none }
        return detectedPlatform.channelSyncConfig(
            lastCleanChannelSync: lastCleanChannelSync?.radioID == radioID
                ? lastCleanChannelSync?.completedAt : nil
        )
    }

    // MARK: - Ready Promotion

    /// Promotes connection to `.ready` if the connection is still alive and owned by the expected services.
    /// Skips post-sync work (time sync, onDeviceSynced) when sync failed to avoid BLE pressure.
    /// Returns `true` if `.ready` was set, `false` if promotion was suppressed.
    ///
    /// - Parameter additionalGuard: Caller-specific invariant checked at every guard point,
    ///   including after async operations like `syncDeviceTimeIfNeeded()`. This cannot be an
    ///   inline check at the call site because the invariant must hold both before AND after
    ///   the internal awaits — a competing reconnect cycle could start during time sync,
    ///   and promoting a stale session to `.ready` would shadow the new one.
    ///   Currently only `rebuildSession` uses this (reconnect-generation check).
    @discardableResult
    func promoteToReady(
        syncSucceeded: Bool,
        expectedServices: ServiceContainer,
        transportType: TransportType,
        additionalGuard: (() -> Bool)? = nil
    ) async -> Bool {
        guard connectionIntent.wantsConnection else {
            logger.warning("Promotion suppressed: user disconnected")
            return false
        }
        guard self.services === expectedServices else {
            logger.warning("Promotion suppressed: services replaced or nil")
            return false
        }
        guard additionalGuard?() ?? true else {
            logger.warning("Promotion suppressed: caller guard failed (e.g. reconnect generation)")
            return false
        }

        currentTransportType = transportType
        connectionState = syncSucceeded ? .ready : .syncing

        // Skip time sync on BLE failure to avoid pressure on a saturated link.
        // WiFi/TCP has no such constraint, so always correct the clock there.
        if syncSucceeded || transportType == .wifi {
            await syncDeviceTimeIfNeeded()
            guard connectionIntent.wantsConnection else {
                logger.warning("Promotion suppressed after time sync: user disconnected")
                return false
            }
            guard self.services === expectedServices else {
                logger.warning("Promotion suppressed after time sync: services replaced or nil")
                return false
            }
            guard additionalGuard?() ?? true else {
                logger.warning("Promotion suppressed after time sync: caller guard failed (e.g. reconnect generation)")
                return false
            }
        }

        if syncSucceeded { await onDeviceSynced?() }
        return true
    }

    /// Syncs the device clock if it drifts more than 60 seconds from the phone.
    /// Safe to call after sync — only affects future device-originated timestamps.
    func syncDeviceTimeIfNeeded() async {
        guard let session else { return }
        do {
            let deviceTime = try await withTimeout(.seconds(5), operationName: "getTime") {
                try await session.getTime()
            }
            let timeDifference = abs(deviceTime.timeIntervalSinceNow)
            if timeDifference > 60 {
                try await withTimeout(.seconds(5), operationName: "setTime") {
                    try await session.setTime(Date())
                }
                logger.info("Synced device time (was off by \(Int(timeDifference))s)")
            } else {
                logger.info("Device time in sync (drift: \(Int(timeDifference))s)")
            }
        } catch {
            logger.warning("Failed to sync device time: \(error.localizedDescription)")
        }
    }

    /// Creates a Device from MeshCore types
    func createDevice(
        deviceID: UUID,
        selfInfo: MeshCore.SelfInfo,
        capabilities: MeshCore.DeviceCapabilities,
        autoAddConfig: MeshCore.AutoAddConfig,
        existingDevice: DeviceDTO? = nil,
        connectionMethods: [ConnectionMethod] = []
    ) -> Device {
        // Merge new connection methods with existing ones, replacing by transport type
        var mergedMethods = existingDevice?.connectionMethods ?? []
        for method in connectionMethods {
            if method.isWiFi {
                mergedMethods.removeAll { $0.isWiFi }
            } else if method.isBluetooth {
                mergedMethods.removeAll { $0.isBluetooth }
            }
            mergedMethods.append(method)
        }

        let device = Device(
            id: deviceID,
            radioID: existingDevice?.radioID ?? UUID(),
            publicKey: selfInfo.publicKey,
            nodeName: selfInfo.name,
            firmwareVersion: capabilities.firmwareVersion,
            firmwareVersionString: capabilities.version,
            manufacturerName: capabilities.model,
            buildDate: capabilities.firmwareBuild,
            maxContacts: UInt16(capabilities.maxContacts),
            maxChannels: UInt8(min(capabilities.maxChannels, 255)),
            frequency: UInt32(selfInfo.radioFrequency * 1000),  // Convert MHz to kHz
            bandwidth: UInt32(selfInfo.radioBandwidth * 1000),  // Convert kHz to Hz
            spreadingFactor: selfInfo.radioSpreadingFactor,
            codingRate: selfInfo.radioCodingRate,
            txPower: selfInfo.txPower,
            maxTxPower: selfInfo.maxTxPower,
            latitude: selfInfo.latitude,
            longitude: selfInfo.longitude,
            blePin: capabilities.blePin,
            clientRepeat: capabilities.clientRepeat,
            pathHashMode: capabilities.pathHashMode,
            defaultFloodScopeName: existingDevice?.defaultFloodScopeName,
            preRepeatFrequency: existingDevice?.preRepeatFrequency,
            preRepeatBandwidth: existingDevice?.preRepeatBandwidth,
            preRepeatSpreadingFactor: existingDevice?.preRepeatSpreadingFactor,
            preRepeatCodingRate: existingDevice?.preRepeatCodingRate,
            manualAddContacts: selfInfo.manualAddContacts,
            autoAddConfig: autoAddConfig.bitmask,
            autoAddMaxHops: autoAddConfig.maxHops,
            multiAcks: selfInfo.multiAcks,
            telemetryModeBase: selfInfo.telemetryModeBase,
            telemetryModeLoc: selfInfo.telemetryModeLocation,
            telemetryModeEnv: selfInfo.telemetryModeEnvironment,
            advertLocationPolicy: selfInfo.advertisementLocationPolicy,
            lastConnected: Date(),
            lastContactSync: existingDevice?.lastContactSync ?? 0,
            isActive: true,
            ocvPreset: existingDevice?.ocvPreset
                ?? OCVPreset.preset(forManufacturer: capabilities.model)?.rawValue,
            customOCVArrayString: existingDevice?.customOCVArrayString,
            connectionMethods: mergedMethods,
            knownRegions: existingDevice?.knownRegions ?? []
        )

        // If repeat mode was disabled externally, clear orphaned pre-repeat settings
        if !capabilities.clientRepeat && existingDevice?.preRepeatFrequency != nil {
            device.preRepeatFrequency = nil
            device.preRepeatBandwidth = nil
            device.preRepeatSpreadingFactor = nil
            device.preRepeatCodingRate = nil
        }

        return device
    }

    /// Configures BLE write pacing based on detected device platform.
    /// - Parameter capabilities: The device capabilities from queryDevice()
    func configureBLEPacing(for capabilities: MeshCore.DeviceCapabilities) async {
        let platform = DevicePlatform.detect(from: capabilities.model)
        detectedPlatform = platform
        let pacing = platform.recommendedWritePacing
        await stateMachine.setWritePacingDelay(pacing)
        if pacing > 0 {
            logger.info("[BLE] Platform detected: \(capabilities.model) -> \(platform), write pacing: \(pacing)s")
        }
    }

    // MARK: - Cleanup

    /// Cleans up session and services without changing connection state (used during retries)
    func cleanupResources() async {
        await session?.stop()
        session = nil
        services = nil
    }

    /// Full cleanup including state reset (used on explicit disconnect)
    func cleanupConnection() async {
        logger.info("[BLE] cleanupConnection: state → .disconnected")
        connectionState = .disconnected
        connectingDeviceID = nil
        connectedDevice = nil
        allowedRepeatFreqRanges = []
        await cleanupResources()
    }

    func persistDisconnectDiagnostic(_ summary: String) {
        let timestamp = Date().ISO8601Format()
        defaults.set("\(timestamp) \(summary)", forKey: lastDisconnectDiagnosticKey)
    }

    func persistIntent() {
        connectionIntent.persist(to: defaults)
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

    // MARK: - Test Helpers

    #if DEBUG
    /// Sets internal state for testing. Only available in DEBUG builds.
    internal func setTestState(
        connectionState: ConnectionState? = nil,
        services: ServiceContainer?? = nil,
        session: MeshCoreSession?? = nil,
        connectedDevice: DeviceDTO?? = nil,
        currentTransportType: TransportType?? = nil,
        connectionIntent: ConnectionIntent? = nil,
        connectingDeviceID: UUID?? = nil,
        sessionRebuildDeviceID: UUID?? = nil,
        isPairingInProgress: Bool? = nil
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
        if let intent = connectionIntent {
            self.connectionIntent = intent
        }
        if let deviceID = connectingDeviceID {
            self.connectingDeviceID = deviceID
        }
        if let deviceID = sessionRebuildDeviceID {
            self.sessionRebuildDeviceID = deviceID
        }
        if let pairing = isPairingInProgress {
            self.isPairingInProgress = pairing
        }
    }
    #endif
}
