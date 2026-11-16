// BLEStateMachine.swift
@preconcurrency import CoreBluetooth
import Dispatch
import Foundation
import MeshCore

/// Manages BLE connections using an explicit state machine.
///
/// All CoreBluetooth operations are modeled as state transitions. Each state
/// owns its resources (continuations, timeouts), ensuring proper cleanup
/// on any transition.
actor BLEStateMachine: BLEStateMachineProtocol {
  // MARK: - Logging

  let logger = PersistentLogger(subsystem: "com.mc1", category: "BLEStateMachine")
  let instanceID = String(UUID().uuidString.prefix(8))
  var lastCentralState: CBManagerState?

  nonisolated var processContext: String {
    let processName = ProcessInfo.processInfo.processName
    let bundleID = Bundle.main.bundleIdentifier ?? "unknown"
    return "process: \(processName), bundle: \(bundleID)"
  }

  /// Converts CBPeripheralState to readable string for diagnostics
  nonisolated func peripheralStateString(_ state: CBPeripheralState) -> String {
    switch state {
    case .disconnected: "disconnected"
    case .connecting: "connecting"
    case .connected: "connected"
    case .disconnecting: "disconnecting"
    @unknown default: "unknown(\(state.rawValue))"
    }
  }

  // MARK: - State

  var phase: BLEPhase = .idle
  private var disconnectTask: (id: UUID, generation: UInt64, task: Task<Void, Never>)?

  /// Tracks when the current phase started (for timing diagnostics)
  var phaseStartTime: Date = .init()

  /// Monotonically increasing generation counter. Incremented on each new
  /// connection or auto-reconnect cycle. Used to reject stale disconnect
  /// callbacks that arrive after a newer connection has started.
  var connectionGeneration: UInt64 = 0

  /// Monotonic boundary timestamp for the current generation.
  /// Disconnect callbacks older than this belong to a previous generation.
  var connectionGenerationStartTime: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()

  /// Classifies link failures as transient or escalating. Owns the failure
  /// tallies, the discovery-extension budget, and bond-verification recency;
  /// this actor executes its decisions.
  var reconnectPolicy = ReconnectPolicy()

  /// Device ID for which ConnectionManager reports a live app-layer session.
  /// RSSI bond refresh requires this match so a preserved dead stack cannot
  /// extend the bond shield. Cleared in cleanupPhaseResources on exit from
  /// `.connected` and explicitly on preserve-path stack teardown.
  var appSessionLiveDeviceID: UUID?
  var activeSessionBinding: SessionTransportBinding?
  private var dataStreamSequence: UInt64 = 0

  private var appSessionLogContext: String {
    "instance=\(instanceID) generation=\(connectionGeneration) stream=\(dataStreamSequence)"
  }

  /// Expose current phase for testing
  var currentPhase: BLEPhase {
    phase
  }

  /// Expose current connection generation for testing
  var currentConnectionGeneration: UInt64 {
    connectionGeneration
  }

  /// Expose the per-generation discovery-extension count for testing
  var currentDiscoveryTimeoutExtensions: Int {
    reconnectPolicy.discoveryTimeoutExtensions
  }

  /// Expose the auto-reconnect connect-failure tally for testing
  var currentAutoReconnectConnectFailures: Int {
    reconnectPolicy.autoReconnectConnectFailures
  }

  /// Expose bond-verification stamps for testing.
  func bondVerificationDate(for deviceID: UUID) -> Date? {
    reconnectPolicy.bondVerificationDates[deviceID]
  }

  /// Expose the session-live signal for testing.
  var currentAppSessionLiveDeviceID: UUID? {
    appSessionLiveDeviceID
  }

  // MARK: - CoreBluetooth

  /// The central manager instance.
  ///
  /// Marked `nonisolated(unsafe)` because:
  /// 1. CBCentralManager is not Sendable
  /// 2. We need nonisolated access from `bluetoothState` property
  /// 3. The manager is only mutated once during initialization
  /// 4. All other access is from the actor's isolated context
  /// 5. The `bluetoothState` property returns `.unknown` during the brief
  ///    initialization window before the manager is assigned
  nonisolated(unsafe) var centralManager: CBCentralManager!
  let delegateHandler: BLEDelegateHandler

  // MARK: - Configuration

  let operationClock: BLEOperationClock
  private let stateRestorationID = "com.pocketmesh.ble.central"
  private let connectionTimeout: TimeInterval
  let serviceDiscoveryTimeout: TimeInterval
  private let autoReconnectDiscoveryTimeout: TimeInterval
  private let writeTimeout: TimeInterval

  /// Delay between write operations for ESP32 compatibility (0 = no pacing)
  var writePacingDelay: TimeInterval = 0

  /// Next write blocked until this instant. Checked in claimWriteSlot
  /// so both queued and sequential writes are paced.
  var earliestNextWrite: ContinuousClock.Instant = .now

  /// Tracks consecutive queued writes for diagnostic logging
  var consecutiveQueuedWrites = 0
  private let queuePressureThreshold = 3

  // MARK: - UUIDs

  let nordicUARTServiceUUID = CBUUID(string: BLEServiceUUID.nordicUART)
  let txCharacteristicUUID = CBUUID(string: BLEServiceUUID.txCharacteristic)
  let rxCharacteristicUUID = CBUUID(string: BLEServiceUUID.rxCharacteristic)

  /// Pending write continuation (only one write at a time)
  var pendingWriteContinuation: CheckedContinuation<Void, Error>?

  /// Monotonic sequence number for correlating didWriteValue callbacks to the active write.
  /// Each write's sequence is recorded with the delegate handler at issue time
  /// (`recordIssuedWriteSequence`), and the delegate tags each callback with the oldest
  /// unacknowledged write's sequence. A callback from write N that arrives after N timed
  /// out therefore carries N, mismatches `pendingWriteSequence` (N+1), and is dropped
  /// instead of resuming write N+1's continuation with write N's result.
  private var writeSequenceNumber: UInt64 = 0
  var pendingWriteSequence: UInt64 = 0

  /// Queue of tasks waiting to write (serializes concurrent sends)
  private struct WriteWaiter {
    let id: UUID
    let continuation: CheckedContinuation<Void, Error>
  }

  private var writeWaiters: [WriteWaiter] = []

  var queuedWriteCount: Int {
    writeWaiters.count
  }

  /// Tracks the current write timeout task so it can be cancelled when write completes
  var writeTimeoutTask: Task<Void, Never>?

  /// Whether the connected peripheral's write (tx) characteristic advertises
  /// `.writeWithoutResponse`. Captured at characteristic discovery (both the normal and
  /// auto-reconnect branches) and only read while `.connected`, so it cannot go stale for
  /// the same radio: a fresh value is written before any send can occur. nRF52 (Adafruit
  /// BLEUart) advertises it; ESP32 does not, so ESP32 stays on the acknowledged path.
  private var txSupportsWriteWithoutResponse = false

  /// Continuation for a `sendWithoutResponse` caller parked on CoreBluetooth backpressure
  /// (`canSendWriteWithoutResponse == false`). Resumed by the `peripheralIsReady` delegate
  /// callback, by `cancelPendingWriteOperations` on teardown, or thrown by its own timeout
  /// backstop, so a sender never hangs across a disconnect or a peripheral that goes silent.
  /// Single slot: the pipeline issues one Write Command at a time.
  private var writeWithoutResponseReadyContinuation: CheckedContinuation<Void, Error>?
  private var writeWithoutResponseReadyBinding: SessionTransportBinding?

  #if DEBUG
    var testWriteReadinessHandled: (@Sendable (UUID) -> Void)?

    func noteWriteReadinessHandled(_ deviceID: UUID) {
      testWriteReadinessHandled?(deviceID)
    }
  #endif

  /// Backstop timeout for `writeWithoutResponseReadyContinuation`: fails a parked sender if
  /// the peripheral never re-signals readiness and no disconnect arrives, so a stuck Write
  /// Command cannot defeat the session's pipeline timeout and hang the channel sync.
  private var writeWithoutResponseReadyTimeoutTask: Task<Void, Never>?
  private var writeWithoutResponseReadyID: UUID?

  /// Error recorded when a `.discoveryComplete` phase is torn down before
  /// `connect()` adopts it, so the in-flight connect surfaces the real
  /// teardown classification (a bond-loss disconnect in that window must
  /// still route to guided re-pair) instead of a generic failure.
  var discoveryCompleteTeardownError: BLEError?

  /// Tracks the service discovery timeout task so it can be cancelled on success
  var serviceDiscoveryTimeoutTask: Task<Void, Never>?

  /// Tracks the auto-reconnect discovery timeout task so it can be cancelled on success
  var autoReconnectDiscoveryTimeoutTask: Task<Void, Never>?

  /// Periodic RSSI read task that keeps the BLE connection alive in background.
  /// Without periodic BLE activity, iOS may drop idle connections.
  private var rssiKeepaliveTask: Task<Void, Never>?

  /// Consecutive RSSI read failures. Reset on success. Logged for diagnostics.
  var consecutiveRSSIFailures = 0

  /// Confirmed foreground. Starts false: a BLE process relaunch is already
  /// background, and scene `.onChange` does not fire for the initial phase.
  private(set) var isAppActive = false

  /// Tracks whether CBCentralManager has been created
  private var isActivated = false

  /// Grace period task for poweredOff during waitingForBluetooth.
  /// Allows CBCentralManager initialization to settle (poweredOff → poweredOn).
  var bluetoothPowerOffGraceTask: Task<Void, Never>?
  var bluetoothPowerOffGraceID: UUID?
  private var bluetoothWaitID: UUID?
  static let bluetoothPowerOffGrace: Duration = .seconds(1)
  private static let disconnectSettleDelay: Duration = .milliseconds(100)

  // MARK: - Scanning (orthogonal to connection lifecycle)

  var isCurrentlyScanning = false
  var pendingScanRequest = false
  /// Installed by `ConnectionManager.startBLEScanning` and reset to a no-op when scanning ends.
  private var onDeviceDiscovered: (@Sendable (UUID, String?, Int) -> Void)?

  // MARK: - Callbacks

  var onLifecycleEvent: (@Sendable (BLELifecycleEvent, AsyncStream<Data>?) -> Void)?
  /// Fired when an existing bond verification was refreshed while a live app
  /// session is present. Installed by `ConnectionManager.wireTransportHandlers`.
  var onBondRefreshed: (@Sendable (UUID) -> Void)?
  /// Installed by `ConnectionManager.init`.
  var onBluetoothStateChange: (@Sendable (CBManagerState) -> Void)?
  /// Installed by `ConnectionManager.init`.
  var onBluetoothPoweredOn: (@Sendable () -> Void)?
  /// Distinguishes restoration from an adoption whose lifecycle event was already sent.
  enum RestoredPeripheralSource {
    case stateRestoration
    case adoption
  }

  // MARK: - Initialization

  /// Creates a new BLE state machine.
  ///
  /// - Parameters:
  ///   - connectionTimeout: Timeout for initial connection (default 10s)
  ///   - serviceDiscoveryTimeout: Timeout for service/characteristic discovery (default 40s for pairing dialog)
  ///   - autoReconnectDiscoveryTimeout: Timeout for auto-reconnect discovery (default 15s, shorter since no pairing expected)
  ///   - writeTimeout: Timeout for write operations (default 5s)
  ///   - writePacingDelay: Delay between write operations for ESP32 compatibility (default 0 = no pacing)
  init(
    connectionTimeout: TimeInterval = 10.0,
    serviceDiscoveryTimeout: TimeInterval = 40.0,
    autoReconnectDiscoveryTimeout: TimeInterval = 15.0,
    writeTimeout: TimeInterval = 5.0,
    writePacingDelay: TimeInterval = 0,
    centralManager: CBCentralManager? = nil,
    operationClock: BLEOperationClock = .continuous
  ) {
    self.operationClock = operationClock
    self.connectionTimeout = connectionTimeout
    self.serviceDiscoveryTimeout = serviceDiscoveryTimeout
    self.autoReconnectDiscoveryTimeout = autoReconnectDiscoveryTimeout
    self.writeTimeout = writeTimeout
    self.writePacingDelay = writePacingDelay
    delegateHandler = BLEDelegateHandler()
    earliestNextWrite = operationClock.now()
    if let centralManager {
      self.centralManager = centralManager
      isActivated = true
      delegateHandler.stateMachine = self
    }
  }

  /// Sets the write pacing delay for ESP32 compatibility.
  /// - Parameter delay: Delay in seconds between write operations (0 = no pacing)
  func setWritePacingDelay(_ delay: TimeInterval) {
    writePacingDelay = delay
  }

  func setWritePacingDelay(_ delay: TimeInterval, sessionBinding: SessionTransportBinding) {
    guard activeSessionBinding === sessionBinding else { return }
    try? sessionBinding.performIfActive { writePacingDelay = delay }
  }

  /// Activates the BLE state machine, creating the CBCentralManager.
  /// Call once during app initialization. Safe to call multiple times.
  func activate() {
    guard !isActivated else { return }
    isActivated = true
    logger.info("[BLE] Activating state machine, instance: \(instanceID), \(processContext)")
    initializeCentralManager()
  }

  private let centralQueue = DispatchQueue(label: "com.pocketmesh.ble.central")

  private func initializeCentralManager() {
    // Set stateMachine reference before creating CBCentralManager.
    // iOS calls willRestoreState during or immediately after CBCentralManager.init(),
    // and the delegate handler needs the stateMachine reference to process it.
    delegateHandler.stateMachine = self

    logger.info("[BLE] Initializing central manager, instance: \(instanceID), \(processContext)")
    let options: [String: Any] = [
      CBCentralManagerOptionRestoreIdentifierKey: stateRestorationID,
      CBCentralManagerOptionShowPowerAlertKey: true
    ]
    centralManager = CBCentralManager(
      delegate: delegateHandler,
      queue: centralQueue,
      options: options
    )
  }

  // MARK: - Connection Generation

  /// Advances the connection generation counter and records the boundary timestamp.
  /// Called when starting a new connection, auto-reconnect cycle, or restoration reconnect
  /// so that stale disconnect callbacks from previous generations can be identified and rejected.
  func advanceConnectionGeneration() {
    connectionGeneration &+= 1
    connectionGenerationStartTime = CFAbsoluteTimeGetCurrent()
    reconnectPolicy.generationAdvanced()
    discoveryCompleteTeardownError = nil
  }

  /// Returns true when a disconnect callback's timestamp predates the current generation boundary.
  /// Uses CFAbsoluteTime from CoreBluetooth's didDisconnectPeripheral (reflects disconnect event
  /// time per Apple's header: "now or a few seconds ago", not callback delivery time).
  /// The tolerance accounts for non-monotonic clock adjustments (NTP sync, user clock changes).
  static func isDisconnectCallbackFromPreviousGeneration(
    timestamp: CFAbsoluteTime,
    generationStart: CFAbsoluteTime,
    tolerance: CFAbsoluteTime = 1.0
  ) -> Bool {
    timestamp + tolerance < generationStart
  }

  // MARK: - API

  /// Whether the state machine is currently connected to a device
  var isConnected: Bool {
    if case .connected = phase { return true }
    return false
  }

  /// Whether the state machine is currently handling iOS auto-reconnect or state restoration
  var isAutoReconnecting: Bool {
    switch phase {
    case .autoReconnecting, .restoringState:
      true
    default:
      false
    }
  }

  /// UUID of the currently connected device, or nil if not connected
  var connectedDeviceID: UUID? {
    phase.deviceID
  }

  /// Current Bluetooth hardware state
  nonisolated var bluetoothState: CBManagerState {
    centralManager?.state ?? .unknown
  }

  /// One consistent snapshot of central state, phase, and peripheral state,
  /// read in a single actor hop.
  var linkDiagnostics: BLELinkDiagnostics {
    BLELinkDiagnostics(
      centralState: centralManagerStateName,
      phase: phase.kind,
      peripheralState: phase.peripheral.map { peripheralStateString($0.state) }
    )
  }

  /// Whether the Bluetooth central manager is in the powered-off state.
  var isBluetoothPoweredOff: Bool {
    centralManager?.state == .poweredOff
  }

  /// Current CBCentralManager state name for diagnostic logging
  private var centralManagerStateName: String {
    guard let manager = centralManager else { return "notActivated" }
    switch manager.state {
    case .unknown: return "unknown"
    case .resetting: return "resetting"
    case .unsupported: return "unsupported"
    case .unauthorized: return "unauthorized"
    case .poweredOff: return "poweredOff"
    case .poweredOn: return "poweredOn"
    @unknown default: return "unknown(\(manager.state.rawValue))"
    }
  }

  /// Checks if a device is connected to the system (possibly by another app).
  /// Call this before attempting connection when in `.idle` phase.
  /// - Parameter deviceID: The UUID of the device to check
  /// - Returns: `true` if the device is connected to the system
  func isDeviceConnectedToSystem(_ deviceID: UUID) -> Bool {
    activate()
    let connectedPeripherals = centralManager.retrieveConnectedPeripherals(
      withServices: [nordicUARTServiceUUID]
    )
    return connectedPeripherals.contains { $0.identifier == deviceID }
  }

  func systemConnectedPeripheralIDs() -> [UUID] {
    activate()
    return centralManager.retrieveConnectedPeripherals(
      withServices: [nordicUARTServiceUUID]
    ).map(\.identifier)
  }

  /// Starts a best-effort adoption of an already system-connected peripheral.
  ///
  /// When iOS keeps the BLE link alive but state restoration does not fire (common across app updates),
  /// `retrieveConnectedPeripherals` may report the radio as system-connected while our state machine
  /// remains `.idle`. In that scenario, we can adopt the existing link by running the restoration
  /// discovery chain against the connected peripheral.
  ///
  /// - Parameter deviceID: The UUID of the device to adopt.
  /// - Returns: `true` if an adoption attempt was started.
  func startAdoptingSystemConnectedPeripheral(_ deviceID: UUID) -> Bool {
    activate()

    guard case .idle = phase else {
      logger.info("[BLE] adoptSystemConnectedPeripheral skipped - phase: \(phase.name)")
      return false
    }

    let connectedPeripherals = centralManager.retrieveConnectedPeripherals(withServices: [nordicUARTServiceUUID])
    guard let peripheral = connectedPeripherals.first(where: { $0.identifier == deviceID }) else {
      return false
    }

    let pState = peripheralStateString(peripheral.state)
    logger.info("[BLE] Adopting system-connected peripheral: \(deviceID.uuidString.prefix(8)), state: \(pState)")
    handleRestoredPeripheral(peripheral, source: .adoption)
    return true
  }

  // MARK: - Event Handler Registration

  /// Sets a handler for Bluetooth state changes
  func setBluetoothStateChangeHandler(_ handler: @escaping @Sendable (CBManagerState) -> Void) {
    onBluetoothStateChange = handler
  }

  /// Sets a handler called when Bluetooth powers on
  func setBluetoothPoweredOnHandler(_ handler: @escaping @Sendable () -> Void) {
    onBluetoothPoweredOn = handler
  }

  /// Records a verified encrypted session. An exhausted encryption-timeout
  /// budget within the grace window then continues the episode rather than
  /// tearing down. `ConnectionManager` seeds and refreshes this stamp.
  func recordBondVerification(deviceID: UUID, at date: Date) {
    reconnectPolicy.recordBondVerification(deviceID: deviceID, at: date)
  }

  func recordBondVerification(deviceID: UUID, at date: Date, sessionBinding: SessionTransportBinding) {
    guard activeSessionBinding === sessionBinding else { return }
    try? sessionBinding.performIfActive {
      reconnectPolicy.recordBondVerification(deviceID: deviceID, at: date)
    }
  }

  /// Clears a device's bond-verification record when its pairing is
  /// forgotten, so a stale verification cannot shield the next episode.
  func clearBondVerification(deviceID: UUID) {
    reconnectPolicy.clearBondVerification(deviceID: deviceID)
  }

  func setAppSessionLive(deviceID: UUID?) {
    appSessionLiveDeviceID = deviceID
  }

  func setSessionBinding(_ binding: SessionTransportBinding) {
    try? binding.performIfActive {
      if let previous = activeSessionBinding, previous !== binding {
        previous.revoke()
        cancelUnsubmittedWrites(sessionBinding: previous)
      }
      activeSessionBinding = binding
    }
  }

  func setAppSessionLive(deviceID: UUID?, sessionBinding: SessionTransportBinding) {
    let binding = String(describing: ObjectIdentifier(sessionBinding))
    let currentBinding = activeSessionBinding.map { String(describing: ObjectIdentifier($0)) } ?? "none"
    guard activeSessionBinding === sessionBinding else {
      logger.info("[BLE] Session live rejected binding=\(binding) currentBinding=\(currentBinding) reason=bindingMismatch \(appSessionLogContext)")
      return
    }
    if let deviceID {
      do {
        try sessionBinding.performIfActive { appSessionLiveDeviceID = deviceID }
        logger.info("[BLE] Session live binding=\(binding) device=\(deviceID.uuidString.prefix(8)) \(appSessionLogContext)")
      } catch {
        logger.info("[BLE] Session live rejected binding=\(binding) currentBinding=\(currentBinding) reason=revoked \(appSessionLogContext)")
      }
    } else {
      appSessionLiveDeviceID = nil
      logger.info("[BLE] Session revoked binding=\(binding) \(appSessionLogContext)")
    }
  }

  func hasBondVerification(deviceID: UUID) -> Bool {
    reconnectPolicy.bondVerificationDates[deviceID] != nil
  }

  func isAppSessionLive(deviceID: UUID) -> Bool {
    appSessionLiveDeviceID == deviceID
  }

  func shouldPersistBondRefresh(deviceID: UUID) -> Bool {
    reconnectPolicy.bondVerificationDates[deviceID] != nil
      && appSessionLiveDeviceID == deviceID
  }

  func setBondRefreshedHandler(_ handler: (@Sendable (UUID) -> Void)?) {
    onBondRefreshed = handler
  }

  // MARK: - BLE Scanning

  /// Sets a handler called when a device is discovered during scanning.
  /// - Parameter handler: Callback with (deviceID, advertised name, rssi)
  func setDeviceDiscoveredHandler(_ handler: @escaping @Sendable (UUID, String?, Int) -> Void) {
    onDeviceDiscovered = handler
  }

  /// Starts scanning for BLE peripherals advertising the Nordic UART service.
  /// Scanning is orthogonal to the connection lifecycle — it works while connected.
  /// Requires `activate()` to have been called and Bluetooth to be powered on.
  func startScanning() {
    activate()
    guard centralManager.state == .poweredOn else {
      logger.info("[BLE] Cannot start scanning: Bluetooth not powered on, will start when ready")
      pendingScanRequest = true
      return
    }
    pendingScanRequest = false
    guard !isCurrentlyScanning else { return }
    isCurrentlyScanning = true
    logger.info("[BLE] Starting BLE scan for device discovery")
    centralManager.scanForPeripherals(
      withServices: [nordicUARTServiceUUID],
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
    )
  }

  /// Stops an active BLE scan.
  func stopScanning() {
    pendingScanRequest = false
    guard isCurrentlyScanning else { return }
    isCurrentlyScanning = false
    logger.info("[BLE] Stopping BLE scan")
    centralManager.stopScan()
  }

  /// Handles a discovered peripheral during scanning.
  func handleDidDiscoverPeripheral(peripheralID: UUID, name: String?, rssi: Int) {
    guard isCurrentlyScanning else { return }
    onDeviceDiscovered?(peripheralID, name, rssi)
  }

  /// Waits for Bluetooth to be powered on.
  ///
  /// - Throws: `BLEError.bluetoothUnavailable` if Bluetooth is not supported
  ///           `BLEError.bluetoothUnauthorized` if access is denied
  ///           `BLEError.bluetoothPoweredOff` if Bluetooth is off and doesn't turn on
  func waitForPoweredOn() async throws {
    try Task.checkCancellation()
    activate()

    // Already powered on
    if centralManager.state == .poweredOn { return }

    // Terminal states won't produce further callbacks - fail immediately
    switch centralManager.state {
    case .unsupported:
      throw BLEError.bluetoothUnavailable
    case .unauthorized:
      throw BLEError.bluetoothUnauthorized
    default:
      break
    }

    // Wait for state change (.unknown, .resetting, and .poweredOff reach here).
    // poweredOff is included because a freshly created CBCentralManager may
    // briefly report poweredOff before settling on poweredOn.
    let waitID = UUID()
    try await withTaskCancellationHandler {
      try Task.checkCancellation()
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        guard case .idle = phase else {
          continuation.resume(throwing: BLEError.connectionFailed("Already in operation"))
          return
        }
        bluetoothWaitID = waitID
        transition(to: .waitingForBluetooth(continuation: continuation))
        if centralManager.state == .poweredOff {
          startBluetoothPowerOffGraceIfNeeded()
        }
      }
    } onCancel: {
      Task { await self.cancelBluetoothWait(id: waitID) }
    }
  }

  private func cancelBluetoothWait(id: UUID) {
    guard bluetoothWaitID == id, case .waitingForBluetooth = phase else { return }
    cancelCurrentOperation(with: CancellationError())
  }

  /// Connects to a BLE device and returns a data stream.
  ///
  /// - Parameter deviceID: UUID of the device to connect to
  /// - Returns: AsyncStream of data received from the device
  /// - Throws: BLEError if connection fails
  func connect(to deviceID: UUID, sessionBinding: SessionTransportBinding? = nil) async throws -> AsyncStream<Data> {
    try sessionBinding?.checkActive()
    logger.info("Connect requested for device: \(deviceID)")

    // Ensure we're in idle state
    guard case .idle = phase else {
      // Diagnostic: Log detailed state when connection is rejected
      let peripheralState = phase.peripheral.map { peripheralStateString($0.state) } ?? "none"
      let phaseDeviceID = phase.deviceID?.uuidString ?? "none"
      logger.warning(
        "Connect rejected - phase: \(phase.name), peripheralState: \(peripheralState), phaseDeviceID: \(phaseDeviceID), requestedDeviceID: \(deviceID)"
      )
      throw BLEError.connectionFailed("Already in operation: \(phase.name)")
    }

    // Wait for Bluetooth
    try await waitForPoweredOn()
    try sessionBinding?.checkActive()

    // Re-validate after the suspension: state restoration or a poweredOn
    // callback can claim the machine (e.g. .autoReconnecting) while waiting,
    // and proceeding would clobber that phase's armed timeout and CB connect.
    guard case .idle = phase else {
      logger.warning("Connect rejected after waitForPoweredOn - phase: \(phase.name), requestedDeviceID: \(deviceID)")
      throw BLEError.connectionFailed("Already in operation: \(phase.name)")
    }

    // Retrieve peripheral
    let peripherals = centralManager.retrievePeripherals(withIdentifiers: [deviceID])
    guard let peripheral = peripherals.first else {
      logger.warning("[BLE] Device not found in peripheral cache: \(deviceID.uuidString.prefix(8))")
      throw BLEError.deviceNotFound
    }

    // Advance connection generation before starting connection
    advanceConnectionGeneration()
    logger.info("[BLE] Connection generation advanced to \(connectionGeneration) for device: \(deviceID.uuidString.prefix(8))")

    // Connect and discover services (continuation spans entire discovery chain)
    try await connectToPeripheral(peripheral, sessionBinding: sessionBinding)
    try sessionBinding?.checkActive()

    // Create data stream and transition to connected
    let (stream, continuation) = AsyncStream.makeStream(
      of: Data.self,
      bufferingPolicy: .bufferingOldest(512)
    )

    guard case let .discoveryComplete(_, tx, rx) = phase else {
      let teardownError = discoveryCompleteTeardownError
      discoveryCompleteTeardownError = nil
      throw teardownError ?? BLEError.connectionFailed("Unexpected state after service discovery")
    }

    // Pass continuation to delegate handler for direct yielding (preserves ordering)
    installDataContinuation(continuation, peripheral: peripheral, rx: rx)

    transition(to: .connected(
      peripheral: peripheral,
      tx: tx,
      rx: rx,
      dataContinuation: continuation
    ))
    startRSSIKeepalive(for: peripheral)

    logger.info("Connection complete for device: \(deviceID)")
    return stream
  }

  /// Sends data to the connected device.
  ///
  /// This method serializes concurrent calls - if a write is already in progress,
  /// subsequent calls will wait until the previous write completes.
  ///
  /// - Parameter data: Data to send
  /// - Throws: BLEError if not connected or write fails
  func send(_ data: Data, sessionBinding: SessionTransportBinding? = nil) async throws {
    logger.debug("[BLE] send: \(data.count) bytes")
    try await claimWriteSlot(data: data, sessionBinding: sessionBinding)
  }

  /// Whether the connected peripheral's write characteristic supports ATT Write Commands.
  /// Drives the transport's `supportsWriteWithoutResponse` capability gate.
  var supportsWriteWithoutResponse: Bool {
    txSupportsWriteWithoutResponse
  }

  /// Test observability: whether a `sendWithoutResponse` caller is currently parked
  /// awaiting peripheral readiness. Reflects existing state; does not alter behavior.
  var isAwaitingWriteWithoutResponseReady: Bool {
    writeWithoutResponseReadyContinuation != nil
  }

  /// Test observability: whether the RSSI keepalive task is currently running.
  /// Reflects existing state; does not alter behavior.
  var isRSSIKeepaliveActive: Bool {
    rssiKeepaliveTask != nil
  }

  /// Sends data as an unacknowledged ATT Write Command (no `didWriteValue` ACK), which is
  /// what lets a caller pipeline back-to-back requests.
  ///
  /// This path is independent of the `.withResponse` machinery (`pendingWriteContinuation`,
  /// `writeTimeoutTask`, `writeWaiters`): flow control is CoreBluetooth's
  /// `canSendWriteWithoutResponse` backpressure plus the caller's bounded send window. It
  /// deliberately skips `writePacingDelay` — that delay protects the ESP32 RX queue during
  /// `.withResponse` bursts, and Write Commands are an nRF52-only path. A radio whose write
  /// characteristic does not advertise the capability degrades to the acknowledged path.
  func sendWithoutResponse(_ data: Data, sessionBinding: SessionTransportBinding? = nil) async throws {
    try Task.checkCancellation()
    try sessionBinding?.checkActive()
    let myGeneration = connectionGeneration

    guard case let .connected(peripheral, _, _, _) = phase,
          peripheral.state == .connected else {
      throw MeshTransportError.notConnected
    }

    guard txSupportsWriteWithoutResponse else {
      try await claimWriteSlot(data: data, sessionBinding: sessionBinding)
      return
    }

    while true {
      try await awaitWriteWithoutResponseReadiness(
        alreadyReady: peripheral.canSendWriteWithoutResponse, sessionBinding: sessionBinding
      )
      try Task.checkCancellation()
      guard case let .connected(readyPeripheral, readyTx, _, _) = phase,
            readyPeripheral.state == .connected,
            connectionGeneration == myGeneration else {
        throw MeshTransportError.notConnected
      }
      guard readyPeripheral.canSendWriteWithoutResponse else { continue }
      if let sessionBinding {
        try sessionBinding.performIfActive {
          readyPeripheral.writeValue(data, for: readyTx, type: .withoutResponse)
        }
      } else {
        readyPeripheral.writeValue(data, for: readyTx, type: .withoutResponse)
      }
      return
    }
  }

  /// Serializes concurrent writes by waiting for any pending write to complete,
  /// then claims the write slot and issues the BLE write.
  private func claimWriteSlot(data: Data, sessionBinding: SessionTransportBinding?) async throws {
    let myGeneration = connectionGeneration
    var claimedWrite = false
    defer {
      // A cancelled caller can wake after leaving the queue; it still owes the next handoff.
      if !claimedWrite, connectionGeneration == myGeneration, pendingWriteContinuation == nil {
        resumeNextWriteWaiter()
      }
    }
    while true {
      try sessionBinding?.checkActive()
      guard connectionGeneration == myGeneration else {
        throw MeshTransportError.notConnected
      }

      // Pacing: wait until earliestNextWrite.
      if writePacingDelay > 0, operationClock.now() < earliestNextWrite {
        try await operationClock.sleep(operationClock.now().duration(to: earliestNextWrite))
        try Task.checkCancellation()
        guard connectionGeneration == myGeneration else {
          throw MeshTransportError.notConnected
        }
      }

      try Task.checkCancellation()

      guard case let .connected(peripheral, _, _, _) = phase, peripheral.state == .connected else {
        throw MeshTransportError.notConnected
      }

      // Wait for any pending write to complete (serializes concurrent sends).
      // After waking, loop and re-check slot ownership to avoid
      // continuation overwrite if multiple waiters are resumed together.
      if pendingWriteContinuation != nil {
        consecutiveQueuedWrites += 1
        let queueDepth = writeWaiters.count + 1
        if consecutiveQueuedWrites >= queuePressureThreshold {
          logger.warning("[BLE] Write queue pressure: depth=\(queueDepth), consecutive=\(consecutiveQueuedWrites)")
        } else {
          logger.debug("[BLE] Write queued, depth: \(queueDepth)")
        }
        let waiterID = UUID()
        try await withTaskCancellationHandler {
          try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            guard !Task.isCancelled else {
              continuation.resume(throwing: CancellationError())
              return
            }
            writeWaiters.append(WriteWaiter(id: waiterID, continuation: continuation))
          }
        } onCancel: {
          Task { await self.cancelWriteWaiter(id: waiterID) }
        }
        continue
      }

      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        // Revalidate at claim time in case phase changed between loop iterations.
        guard case let .connected(currentPeripheral, currentTx, _, _) = self.phase,
              currentPeripheral.state == .connected,
              self.pendingWriteContinuation == nil else {
          continuation.resume(throwing: MeshTransportError.notConnected)
          return
        }

        claimedWrite = true
        self.writeSequenceNumber += 1
        let currentSeq = self.writeSequenceNumber
        self.pendingWriteSequence = currentSeq
        self.pendingWriteContinuation = continuation
        // Record the sequence before issuing the write so didWriteValue
        // can tag its callback with the originating write
        do {
          if let sessionBinding {
            try sessionBinding.performIfActive {
              self.delegateHandler.recordIssuedWriteSequence(currentSeq, peripheral: currentPeripheral, characteristic: currentTx)
              currentPeripheral.writeValue(data, for: currentTx, type: .withResponse)
            }
          } else {
            self.delegateHandler.recordIssuedWriteSequence(currentSeq, peripheral: currentPeripheral, characteristic: currentTx)
            currentPeripheral.writeValue(data, for: currentTx, type: .withResponse)
          }
        } catch {
          self.pendingWriteContinuation = nil
          continuation.resume(throwing: error)
          self.resumeNextWriteWaiter()
          return
        }

        // Cancel any previous timeout task and create a new one
        let seq = self.pendingWriteSequence
        self.writeTimeoutTask?.cancel()
        self.writeTimeoutTask = Task {
          try? await self.operationClock.sleep(.seconds(self.writeTimeout))
          guard !Task.isCancelled, self.pendingWriteSequence == seq else { return }
          if let pending = self.pendingWriteContinuation {
            self.logger.warning("[BLE] Write timeout: seq=\(seq), elapsed=\(self.writeTimeout)s")
            self.pendingWriteContinuation = nil
            self.consecutiveQueuedWrites = 0
            pending.resume(throwing: BLEError.operationTimeout)
            self.writeTimeoutTask = nil
            self.earliestNextWrite = operationClock.now().advanced(by: .seconds(self.writePacingDelay))
            self.resumeNextWriteWaiter()
          }
        }
      }
      return
    }
  }

  /// Resumes the next task waiting to write.
  /// Pacing is enforced in claimWriteSlot via earliestNextWrite.
  func resumeNextWriteWaiter() {
    guard !writeWaiters.isEmpty else { return }
    let waiter = writeWaiters.removeFirst()
    waiter.continuation.resume()
  }

  private func cancelWriteWaiter(id: UUID) {
    guard let index = writeWaiters.firstIndex(where: { $0.id == id }) else { return }
    writeWaiters.remove(at: index).continuation.resume(throwing: CancellationError())
  }

  /// Waits for Write Command readiness, with teardown cancellation and a per-write timeout
  /// so a peripheral that stops signalling cannot leave the sender suspended.
  func awaitWriteWithoutResponseReadiness(
    alreadyReady: Bool, sessionBinding: SessionTransportBinding? = nil
  ) async throws {
    try Task.checkCancellation()
    try sessionBinding?.checkActive()
    guard !alreadyReady else { return }
    let timeout = writeTimeout
    let waitID = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        guard !Task.isCancelled else {
          continuation.resume(throwing: CancellationError())
          return
        }
        completeWriteWithoutResponseReadiness(error: MeshTransportError.notConnected)
        writeWithoutResponseReadyID = waitID
        writeWithoutResponseReadyContinuation = continuation
        writeWithoutResponseReadyBinding = sessionBinding
        writeWithoutResponseReadyTimeoutTask = Task { [weak self] in
          try? await self?.operationClock.sleep(.seconds(timeout))
          guard !Task.isCancelled else { return }
          await self?.timeoutWriteWithoutResponseReadiness(id: waitID)
        }
      }
    } onCancel: {
      Task { await self.cancelWriteWithoutResponseReadiness(id: waitID) }
    }
  }

  private func timeoutWriteWithoutResponseReadiness(id: UUID) {
    guard writeWithoutResponseReadyID == id else { return }
    logger.warning("[BLE] Write-without-response readiness timeout after \(writeTimeout)s")
    completeWriteWithoutResponseReadiness(error: BLEError.operationTimeout)
  }

  private func cancelWriteWithoutResponseReadiness(id: UUID) {
    guard writeWithoutResponseReadyID == id else { return }
    completeWriteWithoutResponseReadiness(error: CancellationError())
  }

  func cancelUnsubmittedWrites(sessionBinding: SessionTransportBinding) {
    guard writeWithoutResponseReadyBinding === sessionBinding else { return }
    completeWriteWithoutResponseReadiness(error: MeshTransportError.cancelledBeforeSubmission)
  }

  private func completeWriteWithoutResponseReadiness(error: Error? = nil) {
    guard let continuation = writeWithoutResponseReadyContinuation else { return }
    writeWithoutResponseReadyContinuation = nil
    writeWithoutResponseReadyID = nil
    writeWithoutResponseReadyBinding = nil
    writeWithoutResponseReadyTimeoutTask?.cancel()
    writeWithoutResponseReadyTimeoutTask = nil
    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
  }

  /// Resumes a parked sender only when its current peripheral can accept a Write Command.
  func handlePeripheralReadyForWriteWithoutResponse(_ peripheral: CBPeripheral) {
    guard case let .connected(current, _, _, _) = phase,
          current === peripheral,
          current.canSendWriteWithoutResponse else { return }
    resumeWriteWithoutResponseReadiness()
  }

  func handlePeripheralReadyForWriteWithoutResponse(_ peripheral: CBPeripheral, generation: UInt64) {
    guard connectionGeneration == generation else { return }
    handlePeripheralReadyForWriteWithoutResponse(peripheral)
  }

  func resumeWriteWithoutResponseReadiness() {
    completeWriteWithoutResponseReadiness()
  }

  /// Records whether the resolved write characteristic advertises Write-Without-Response.
  /// Called from characteristic discovery; the log doubles as the on-device capability check.
  func captureWriteWithoutResponseCapability(from tx: CBCharacteristic) {
    txSupportsWriteWithoutResponse = tx.properties.contains(.writeWithoutResponse)
    logger.info("[BLE] tx writeWithoutResponse capability: \(txSupportsWriteWithoutResponse)")
  }

  /// Gracefully shuts down the state machine, resuming all pending operations with cancellation.
  /// Call this before dropping the last reference to the actor.
  func shutdown() {
    logger.info("[BLE] Shutting down state machine, instance: \(instanceID)")

    stopScanning()

    // Cancel all timeout tasks
    bluetoothPowerOffGraceTask?.cancel()
    bluetoothPowerOffGraceTask = nil
    bluetoothPowerOffGraceID = nil
    autoReconnectDiscoveryTimeoutTask?.cancel()
    autoReconnectDiscoveryTimeoutTask = nil
    serviceDiscoveryTimeoutTask?.cancel()
    serviceDiscoveryTimeoutTask = nil
    // The direct phase write below bypasses cleanupPhaseResources, so the
    // RSSI keepalive must be cancelled here or it outlives the shutdown.
    rssiKeepaliveTask?.cancel()
    rssiKeepaliveTask = nil

    cancelPendingWriteOperations(error: CancellationError())

    // Resume any phase continuation with cancellation
    switch phase {
    case let .waitingForBluetooth(continuation):
      continuation.resume(throwing: CancellationError())
    case let .connecting(_, continuation, timeoutTask):
      timeoutTask.cancel()
      continuation.resume(throwing: CancellationError())
    case let .discoveringServices(_, continuation):
      continuation.resume(throwing: CancellationError())
    case let .discoveringCharacteristics(_, _, continuation):
      continuation.resume(throwing: CancellationError())
    case let .subscribingToNotifications(_, _, _, continuation):
      continuation.resume(throwing: CancellationError())
    case let .connected(_, _, _, dataContinuation):
      delegateHandler.clearDataContinuation()
      dataContinuation.finish()
    default:
      break
    }

    let deviceID = phase.deviceID
    bluetoothWaitID = nil
    phase = .idle
    phaseStartTime = Date()

    if let deviceID {
      notifyDisconnection(deviceID, nil)
    }
  }

  func appDidEnterBackground() {
    isAppActive = false
    autoReconnectDiscoveryTimeoutTask?.cancel()
    autoReconnectDiscoveryTimeoutTask = nil
    logger.info("[BLE] App entered background: cancelled auto-reconnect timeout (keepalive persists)")
  }

  func appDidBecomeActive() {
    isAppActive = true
    logger.info("[BLE] App became active, phase: \(phase.name)")

    // Defensive restart: only if connected but keepalive task died unexpectedly
    if case let .connected(peripheral, _, _, _) = phase, rssiKeepaliveTask == nil {
      logger.warning("[BLE] Keepalive task died while connected - restarting defensively")
      startRSSIKeepalive(for: peripheral)
    }

    // Re-arm auto-reconnect timeout if in auto-reconnecting phase
    if case let .autoReconnecting(peripheral, _, _) = phase {
      phaseStartTime = Date()
      armAutoReconnectDiscoveryTimeout(
        for: peripheral,
        generation: connectionGeneration
      )
      logger.info("[BLE] Re-armed auto-reconnect timeout after foreground return")
    }
  }

  func armAutoReconnectDiscoveryTimeout(
    for peripheral: CBPeripheral,
    generation: UInt64
  ) {
    autoReconnectDiscoveryTimeoutTask?.cancel()
    logger.info("[BLE] Arming auto-reconnect discovery timeout: \(autoReconnectDiscoveryTimeout)s, generation: \(generation), device: \(peripheral.identifier.uuidString.prefix(8))")
    autoReconnectDiscoveryTimeoutTask = Task {
      try? await Task.sleep(for: .seconds(autoReconnectDiscoveryTimeout))
      guard !Task.isCancelled else { return }
      handleAutoReconnectDiscoveryTimeout(
        for: peripheral,
        generation: generation
      )
    }
  }

  // Note: `isolated deinit` would be the ideal safety net here, but it requires
  // a deployment target of macOS 15.4 / iOS 18.4+. Since we target iOS 18.0,
  // callers must call shutdown() explicitly before dropping the actor reference.

  /// Disconnects from the current device.
  func disconnect(expectedGeneration: UInt64? = nil) async {
    await disconnect(
      generation: expectedGeneration ?? connectionGeneration, sessionBinding: nil, pendingOnly: false
    )
  }

  func disconnect(sessionBinding: SessionTransportBinding) async {
    await disconnect(generation: connectionGeneration, sessionBinding: sessionBinding, pendingOnly: false)
  }

  func cancelPendingEstablishment(sessionBinding: SessionTransportBinding) async {
    await disconnect(generation: connectionGeneration, sessionBinding: sessionBinding, pendingOnly: true)
  }

  private func permitsDisconnect(sessionBinding: SessionTransportBinding?, pendingOnly: Bool) -> Bool {
    if let sessionBinding, activeSessionBinding !== sessionBinding { return false }
    guard pendingOnly else { return true }
    switch phase {
    case .waitingForBluetooth, .connecting, .discoveringServices, .discoveringCharacteristics,
         .subscribingToNotifications, .discoveryComplete:
      return true
    default:
      return false
    }
  }

  private func disconnect(generation: UInt64, sessionBinding: SessionTransportBinding?, pendingOnly: Bool) async {
    guard generation == connectionGeneration,
          permitsDisconnect(sessionBinding: sessionBinding, pendingOnly: pendingOnly) else { return }
    if let disconnectTask, disconnectTask.generation == generation {
      await disconnectTask.task.value
      return
    }
    let id = UUID()
    let worker = Task {
      await performDisconnect(generation: generation, sessionBinding: sessionBinding, pendingOnly: pendingOnly)
    }
    disconnectTask = (id, generation, worker)
    await worker.value
    if disconnectTask?.id == id { disconnectTask = nil }
  }

  private func performDisconnect(generation: UInt64, sessionBinding: SessionTransportBinding?, pendingOnly: Bool) async {
    guard generation == connectionGeneration,
          permitsDisconnect(sessionBinding: sessionBinding, pendingOnly: pendingOnly) else { return }
    logger.info("Disconnect requested")

    // Cancel Bluetooth power-off grace period
    bluetoothPowerOffGraceTask?.cancel()
    bluetoothPowerOffGraceTask = nil
    bluetoothPowerOffGraceID = nil

    // Cancel write timeout task
    writeTimeoutTask?.cancel()
    writeTimeoutTask = nil

    // Reset queue tracking
    consecutiveQueuedWrites = 0
    earliestNextWrite = operationClock.now()

    // The pending continuation already called writeValue. Queued waiters have not.
    if let pending = pendingWriteContinuation {
      pendingWriteContinuation = nil
      pending.resume(throwing: BLEError.notConnected)
    }

    while !writeWaiters.isEmpty {
      writeWaiters.removeFirst().continuation.resume(throwing: MeshTransportError.notConnected)
    }

    // Get peripheral before cancelling
    let peripheral = phase.peripheral
    // During auto-reconnect iOS holds a pending connect that stays armed even
    // while the peripheral reports .disconnected; an explicit disconnect must
    // cancel it or iOS later re-establishes a link the user severed.
    let hadPendingAutoReconnect = if case .autoReconnecting = phase { true } else { false }

    // Cancel current operation
    cancelCurrentOperation(with: BLEError.notConnected)

    // Disconnect peripheral if connected or holding a pending auto-reconnect
    if let peripheral, hadPendingAutoReconnect || peripheral.state == .connected || peripheral.state == .connecting {
      transition(to: .disconnecting(peripheral: peripheral))
      centralManager.cancelPeripheralConnection(peripheral)

      // Wait briefly for disconnection to complete
      try? await operationClock.sleep(Self.disconnectSettleDelay)
    }

    guard generation == connectionGeneration,
          permitsDisconnect(sessionBinding: sessionBinding, pendingOnly: false) else { return }
    transition(to: .idle)
    logger.info("Disconnect complete")
  }

  private func connectToPeripheral(_ peripheral: CBPeripheral, sessionBinding: SessionTransportBinding?) async throws {
    let pState = peripheralStateString(peripheral.state)
    logger.info("[BLE] Connecting to peripheral: \(peripheral.identifier.uuidString.prefix(8)), currentState: \(pState), timeout: \(connectionTimeout)s, autoReconnect: enabled")

    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      // Create timeout task
      let timeoutTask = Task {
        try? await Task.sleep(for: .seconds(connectionTimeout))
        guard !Task.isCancelled else { return }
        self.handleConnectionTimeout(for: peripheral)
      }

      transition(to: .connecting(
        peripheral: peripheral,
        continuation: continuation,
        timeoutTask: timeoutTask
      ))

      let options: [String: Any] = [
        CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
        CBConnectPeripheralOptionNotifyOnNotificationKey: true,
        CBConnectPeripheralOptionEnableAutoReconnect: true
      ]
      do {
        if let sessionBinding {
          try sessionBinding.performIfActive {
            centralManager.connect(peripheral, options: options)
          }
        } else {
          centralManager.connect(peripheral, options: options)
        }
      } catch {
        timeoutTask.cancel()
        transition(to: .idle)
        continuation.resume(throwing: error)
      }
    }
  }

  private func handleConnectionTimeout(for peripheral: CBPeripheral) {
    guard case let .connecting(expected, continuation, _) = phase,
          expected.identifier == peripheral.identifier else {
      return // No longer connecting to this peripheral
    }

    let pState = peripheralStateString(peripheral.state)
    let elapsed = Date().timeIntervalSince(phaseStartTime)
    logger.warning("[BLE] Connection timeout: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")
    centralManager.cancelPeripheralConnection(peripheral)
    transition(to: .idle)
    continuation.resume(throwing: BLEError.connectionTimeout)
  }
}

// MARK: - State Transitions

extension BLEStateMachine {
  /// Transitions to a new phase, cleaning up the old phase's resources.
  ///
  /// - Parameter newPhase: The phase to transition to
  /// - Returns: The previous phase (for logging/debugging)
  @discardableResult
  func transition(to newPhase: BLEPhase) -> BLEPhase {
    let oldPhase = phase
    let elapsed = Date().timeIntervalSince(phaseStartTime)
    let deviceID = oldPhase.deviceID?.uuidString.prefix(8) ?? "none"
    logger.info("[BLE] Transition: \(oldPhase.name) → \(newPhase.name), device: \(deviceID), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")

    // Clean up old phase resources (except continuations - caller handles those)
    cleanupPhaseResources(oldPhase, newPhase: newPhase)

    phase = newPhase
    phaseStartTime = Date()
    return oldPhase
  }

  /// Starts a periodic RSSI read to keep the BLE connection alive.
  /// In foreground, fires every 15s. In background, the task freezes during
  /// iOS suspension; when a BLE event wakes the app, the expired sleep resumes
  /// and fires an opportunistic RSSI read within the ~10s wake window.
  func startRSSIKeepalive(for peripheral: CBPeripheral) {
    rssiKeepaliveTask?.cancel()
    consecutiveRSSIFailures = 0
    rssiKeepaliveTask = Task {
      while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(15))
        guard !Task.isCancelled else { break }
        peripheral.readRSSI()
      }
    }
  }

  func installDataContinuation(_ continuation: AsyncStream<Data>.Continuation, peripheral: CBPeripheral, rx: CBCharacteristic) {
    dataStreamSequence &+= 1
    let binding = activeSessionBinding.map { String(describing: ObjectIdentifier($0)) }
    delegateHandler.setDataContinuation(
      continuation, peripheral: peripheral, characteristic: rx, instanceID: instanceID,
      generation: connectionGeneration, streamSequence: dataStreamSequence, binding: binding
    )
  }

  /// Replaces the connected phase's data stream with a fresh one and returns it,
  /// or returns nil when not `.connected`.
  ///
  /// A stopped session's receive-loop cancellation terminates the stream's
  /// shared storage, so every later iteration of the old stream ends
  /// immediately; a session rebuild over the still-live link must consume a
  /// renewed stream or its handshake times out.
  ///
  /// The direct phase write below bypasses cleanupPhaseResources, the same
  /// convention as shutdown(), but unlike shutdown() it deliberately
  /// replicates none of the skipped cleanup: the phase stays `.connected`, so
  /// the RSSI keepalive must keep running (cancelling it would silently kill
  /// background keepalive on every rebuild), and the outgoing continuation is
  /// dropped without finish() because its consumer is already cancelled and
  /// continuation deinit terminates the dead storage.
  func renewDataStream(sessionBinding: SessionTransportBinding? = nil) -> AsyncStream<Data>? {
    if let sessionBinding {
      guard activeSessionBinding === sessionBinding, sessionBinding.isActive else { return nil }
    }
    guard case let .connected(peripheral, tx, rx, _) = phase else { return nil }
    let (stream, continuation) = AsyncStream.makeStream(
      of: Data.self,
      bufferingPolicy: .bufferingOldest(512)
    )
    do {
      if let sessionBinding {
        try sessionBinding.performIfActive {
          phase = .connected(peripheral: peripheral, tx: tx, rx: rx, dataContinuation: continuation)
          installDataContinuation(continuation, peripheral: peripheral, rx: rx)
        }
      } else {
        phase = .connected(peripheral: peripheral, tx: tx, rx: rx, dataContinuation: continuation)
        installDataContinuation(continuation, peripheral: peripheral, rx: rx)
      }
    } catch {
      continuation.finish()
      return nil
    }
    return stream
  }

  /// Cleans up non-continuation resources owned by a phase.
  ///
  /// Timeout cancellation is phase-aware:
  /// - Discovery timeout is preserved when transitioning within the discovery chain
  /// - Auto-reconnect timeout is preserved when staying in auto-reconnect
  private func cleanupPhaseResources(_ oldPhase: BLEPhase, newPhase: BLEPhase) {
    // Only cancel discovery timeout when leaving the discovery chain
    if !newPhase.isDiscoveryChain {
      serviceDiscoveryTimeoutTask?.cancel()
      serviceDiscoveryTimeoutTask = nil
    }

    // Only cancel auto-reconnect timeout when leaving auto-reconnect
    if case .autoReconnecting = newPhase {
      // preserve
    } else {
      autoReconnectDiscoveryTimeoutTask?.cancel()
      autoReconnectDiscoveryTimeoutTask = nil
    }

    switch oldPhase {
    case .waitingForBluetooth:
      bluetoothWaitID = nil
      bluetoothPowerOffGraceTask?.cancel()
      bluetoothPowerOffGraceTask = nil
      bluetoothPowerOffGraceID = nil

    case let .connecting(_, _, timeoutTask):
      timeoutTask.cancel()

    case let .connected(_, _, _, dataContinuation):
      rssiKeepaliveTask?.cancel()
      rssiKeepaliveTask = nil
      consecutiveRSSIFailures = 0
      // Phase exit from `.connected` must drop the session-live signal so a
      // same-device reconnect's pre-handshake keepalive window cannot refresh
      // a stale stamp. Preserve-path stack teardown clears explicitly too
      // because that path keeps the phase `.connected`.
      appSessionLiveDeviceID = nil
      // Clear delegate handler's continuation first to stop data flow
      delegateHandler.clearDataContinuation()
      dataContinuation.finish()

    default:
      break
    }
  }

  /// Cancels pending write operations and write waiters without touching phase state.
  /// Used when transitioning to auto-reconnect where we need to clean up writes
  /// but handle the phase continuation separately.
  func cancelPendingWriteOperations(error: Error = BLEError.notConnected) {
    writeTimeoutTask?.cancel()
    writeTimeoutTask = nil
    consecutiveQueuedWrites = 0
    earliestNextWrite = operationClock.now()
    // Outstanding write callbacks either never arrive (link dropped) or
    // find no continuation; their recorded sequences must not survive to
    // mis-tag the next connection's first write callback.
    delegateHandler.clearIssuedWriteSequences()

    if let pending = pendingWriteContinuation {
      pendingWriteContinuation = nil
      pending.resume(throwing: error)
    }

    let unsubmitted = Self.unsubmittedWriteError(error)
    while !writeWaiters.isEmpty {
      writeWaiters.removeFirst().continuation.resume(throwing: unsubmitted)
    }

    // Release a Write-Command sender parked on backpressure so it doesn't hang past the drop.
    resumeWriteWithoutResponseReadiness()
  }

  /// A queued waiter has not called `writeValue`. `BLEError.notConnected` after that call stays a submitted write.
  private static func unsubmittedWriteError(_ error: Error) -> Error {
    if let ble = error as? BLEError, case .notConnected = ble {
      return MeshTransportError.notConnected
    }
    return error
  }

  /// Cancels the current operation, resuming any pending continuation with an error.
  ///
  /// - Parameter error: The error to resume continuations with
  func cancelCurrentOperation(with error: Error) {
    logger.warning("[BLE] cancelCurrentOperation: phase=\(phase.name), error=\(error.localizedDescription)")
    cancelPendingWriteOperations(error: error)

    switch phase {
    case let .waitingForBluetooth(continuation):
      continuation.resume(throwing: error)

    case let .connecting(_, continuation, timeoutTask):
      timeoutTask.cancel()
      continuation.resume(throwing: error)

    case let .discoveringServices(_, continuation):
      continuation.resume(throwing: error)

    case let .discoveringCharacteristics(_, _, continuation):
      continuation.resume(throwing: error)

    case let .subscribingToNotifications(_, _, _, continuation):
      continuation.resume(throwing: error)

    case let .connected(_, _, _, dataContinuation):
      dataContinuation.finish()

    case .discoveryComplete:
      // Continuation already consumed; connect() is between discovery and
      // adopting .connected. Record why the phase was torn down so connect()
      // surfaces the real classification instead of a generic failure.
      discoveryCompleteTeardownError = error as? BLEError ?? ReconnectPolicy.makeConnectionError(error)

    case .idle, .autoReconnecting, .restoringState, .disconnecting:
      break
    }

    transition(to: .idle)
  }

  /// Cancels connection to a peripheral if we're not expecting it.
  func cancelUnexpectedPeripheral(_ peripheral: CBPeripheral) {
    logger.warning("Cancelling unexpected peripheral: \(peripheral.identifier)")
    centralManager.cancelPeripheralConnection(peripheral)
  }

  func armServiceDiscoveryTimeout(for peripheral: CBPeripheral) {
    serviceDiscoveryTimeoutTask?.cancel()
    serviceDiscoveryTimeoutTask = Task {
      try? await Task.sleep(for: .seconds(serviceDiscoveryTimeout))
      guard !Task.isCancelled else { return }
      handleServiceDiscoveryTimeout(for: peripheral)
    }
  }

  func handleServiceDiscoveryTimeout(for peripheral: CBPeripheral) {
    // Guard against stale timeout: if the normal path already cleared the
    // task reference, this timeout fired after cancellation took effect.
    guard serviceDiscoveryTimeoutTask != nil else { return }

    switch phase {
    case let .discoveringServices(p, c),
         let .discoveringCharacteristics(p, _, c),
         let .subscribingToNotifications(p, _, _, c):
      guard p.identifier == peripheral.identifier else { return }
      let pState = peripheralStateString(peripheral.state)
      let elapsed = Date().timeIntervalSince(phaseStartTime)
      logger.warning("[BLE] Service discovery timeout: \(peripheral.identifier.uuidString.prefix(8)), phase: \(phase.name), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")

      switch reconnectPolicy.resolveServiceDiscoveryStall(peripheralConnected: peripheral.state == .connected) {
      case let .extendDiscoveryWindow(extensionCount, budget):
        logger.warning("[BLE] Peripheral still connected; extending service discovery window (\(extensionCount)/\(budget)) instead of failing")
        armServiceDiscoveryTimeout(for: peripheral)

      case let .tearDown(teardownError):
        centralManager.cancelPeripheralConnection(peripheral)
        transition(to: .idle)
        c.resume(throwing: teardownError)
      }
    default:
      break
    }
  }

  private func handleAutoReconnectDiscoveryTimeout(for peripheral: CBPeripheral, generation: UInt64) {
    // Guard against stale timeout: if the normal path already cleared the
    // task reference, this timeout fired after cancellation took effect.
    guard autoReconnectDiscoveryTimeoutTask != nil else { return }

    // Skip timeout enforcement while app is inactive
    guard isAppActive else {
      logger.info("[BLE] Skipping auto-reconnect timeout while app inactive")
      return
    }

    // Reject stale timeout from a previous generation
    if generation != connectionGeneration {
      logger.info("[BLE] Ignoring stale auto-reconnect timeout for generation \(generation)")
      return
    }

    guard case let .autoReconnecting(expected, _, _) = phase,
          expected.identifier == peripheral.identifier else {
      return
    }

    let pState = peripheralStateString(peripheral.state)
    let elapsed = Date().timeIntervalSince(phaseStartTime)

    switch reconnectPolicy.resolveAutoReconnectStall(peripheralConnected: peripheral.state == .connected) {
    case .waitForPendingConnect:
      logger.info(
        "[BLE] Auto-reconnect window elapsed while link down: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s; waiting for pending connect"
      )
      armAutoReconnectDiscoveryTimeout(for: peripheral, generation: generation)

    case let .extendDiscoveryWindow(extensionCount, budget):
      // iOS may flip the peripheral to .connected before delivering didConnect.
      // Tearing the link down here cancels a reconnection that actually
      // succeeded, after which the late didConnect lands in .idle and is
      // rejected. Give discovery another window instead of destroying it.
      logger.warning("[BLE] Peripheral still connected; extending auto-reconnect discovery window (\(extensionCount)/\(budget)) instead of tearing down")
      armAutoReconnectDiscoveryTimeout(for: peripheral, generation: generation)

    case let .tearDown(teardownError):
      logger.warning(
        "[BLE] Auto-reconnect discovery timeout: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s"
      )
      centralManager.cancelPeripheralConnection(peripheral)
      transition(to: .idle)
      notifyDisconnection(peripheral.identifier, teardownError)
    }
  }
}
