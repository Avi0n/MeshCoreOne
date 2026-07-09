// BLEStateMachine.swift
@preconcurrency import CoreBluetooth
import Dispatch
import Foundation

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
    case .disconnected: return "disconnected"
    case .connecting: return "connecting"
    case .connected: return "connected"
    case .disconnecting: return "disconnecting"
    @unknown default: return "unknown(\(state.rawValue))"
    }
  }

  // MARK: - State

  var phase: BLEPhase = .idle

  /// Tracks when the current phase started (for timing diagnostics)
  var phaseStartTime: Date = .init()

  /// Monotonically increasing generation counter. Incremented on each new
  /// connection or auto-reconnect cycle. Used to reject stale disconnect
  /// callbacks that arrive after a newer connection has started.
  var connectionGeneration: UInt64 = 0

  /// Monotonic boundary timestamp for the current generation.
  /// Disconnect callbacks older than this belong to a previous generation.
  var connectionGenerationStartTime: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()

  /// Number of times a discovery watchdog has deferred teardown within the
  /// current generation because the peripheral was already connected. Reset
  /// per generation in `advanceConnectionGeneration()`.
  var discoveryTimeoutExtensions = 0

  /// Max times a discovery watchdog defers teardown while the peripheral is
  /// already connected, before forcing a reconnect. Bounds recovery so a
  /// genuinely wedged-but-connected link still tears down eventually.
  static let maxDiscoveryTimeoutExtensions = 2

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
    discoveryTimeoutExtensions
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
  private var writeWaiters: [CheckedContinuation<Void, Never>] = []

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

  /// Backstop timeout for `writeWithoutResponseReadyContinuation`: fails a parked sender if
  /// the peripheral never re-signals readiness and no disconnect arrives, so a stuck Write
  /// Command cannot defeat the session's pipeline timeout and hang the channel sync.
  private var writeWithoutResponseReadyTimeoutTask: Task<Void, Never>?

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

  /// Tracks whether the app is in the foreground. Used to gate
  /// keepalive and timeout behavior.
  private var isAppActive = true

  /// Tracks whether CBCentralManager has been created
  private var isActivated = false

  /// Grace period task for poweredOff during waitingForBluetooth.
  /// Allows CBCentralManager initialization to settle (poweredOff → poweredOn).
  var bluetoothPowerOffGraceTask: Task<Void, Never>?

  // MARK: - Scanning (orthogonal to connection lifecycle)

  var isCurrentlyScanning = false
  var pendingScanRequest = false
  /// Installed by `ConnectionManager.startBLEScanning` and reset to a no-op when scanning ends.
  private var onDeviceDiscovered: (@Sendable (UUID, String?, Int) -> Void)?

  // MARK: - Callbacks

  /// Installed by `ConnectionManager.init` (via `iOSBLETransport.setDisconnectionHandler`).
  var onDisconnection: (@Sendable (UUID, Error?) -> Void)?
  /// Installed by `ConnectionManager.init` (via `iOSBLETransport.setReconnectionHandler`,
  /// which wraps it to capture the data stream before the handler runs).
  var onReconnection: (@Sendable (UUID, AsyncStream<Data>) -> Void)?
  /// Installed by `ConnectionManager.init`.
  var onBluetoothStateChange: (@Sendable (CBManagerState) -> Void)?
  /// Installed by `ConnectionManager.init`.
  var onBluetoothPoweredOn: (@Sendable () -> Void)?
  /// Called when entering iOS auto-reconnecting phase.
  /// The device has disconnected but iOS will attempt automatic reconnection.
  /// Note: The MeshCore session is invalid at this point and will be rebuilt upon successful reconnection.
  /// Installed by `ConnectionManager.init`.
  var onAutoReconnecting: (@Sendable (UUID, String) -> Void)?

  /// Distinguishes the call site driving `handleRestoredPeripheral` so the function
  /// fires `onAutoReconnecting` only when no upstream caller has already claimed the cycle.
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
    writePacingDelay: TimeInterval = 0
  ) {
    self.connectionTimeout = connectionTimeout
    self.serviceDiscoveryTimeout = serviceDiscoveryTimeout
    self.autoReconnectDiscoveryTimeout = autoReconnectDiscoveryTimeout
    self.writeTimeout = writeTimeout
    self.writePacingDelay = writePacingDelay
    delegateHandler = BLEDelegateHandler()
  }

  /// Sets the write pacing delay for ESP32 compatibility.
  /// - Parameter delay: Delay in seconds between write operations (0 = no pacing)
  func setWritePacingDelay(_ delay: TimeInterval) {
    writePacingDelay = delay
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
    discoveryTimeoutExtensions = 0
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

  /// Current phase name for diagnostic logging
  var currentPhaseName: String {
    phase.name
  }

  /// Current peripheral state for diagnostic logging (nil if no peripheral)
  var currentPeripheralState: String? {
    guard let peripheral = phase.peripheral else { return nil }
    return peripheralStateString(peripheral.state)
  }

  /// Whether the Bluetooth central manager is in the powered-off state.
  var isBluetoothPoweredOff: Bool {
    centralManager?.state == .poweredOff
  }

  /// Current CBCentralManager state name for diagnostic logging
  var centralManagerStateName: String {
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

  /// Sets a handler for disconnection events
  func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) {
    onDisconnection = handler
  }

  /// Sets a handler for reconnection events.
  /// The handler receives the device ID and the data stream for receiving data.
  func setReconnectionHandler(_ handler: @escaping @Sendable (UUID, AsyncStream<Data>) -> Void) {
    onReconnection = handler
  }

  /// Sets a handler for Bluetooth state changes
  func setBluetoothStateChangeHandler(_ handler: @escaping @Sendable (CBManagerState) -> Void) {
    onBluetoothStateChange = handler
  }

  /// Sets a handler called when Bluetooth powers on
  func setBluetoothPoweredOnHandler(_ handler: @escaping @Sendable () -> Void) {
    onBluetoothPoweredOn = handler
  }

  /// Sets a handler for auto-reconnecting events.
  /// Called when device disconnects but iOS is attempting automatic reconnection.
  func setAutoReconnectingHandler(_ handler: @escaping @Sendable (UUID, String) -> Void) {
    onAutoReconnecting = handler
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
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      guard case .idle = phase else {
        continuation.resume(throwing: BLEError.connectionFailed("Already in operation"))
        return
      }
      transition(to: .waitingForBluetooth(continuation: continuation))
    }
  }

  /// Connects to a BLE device and returns a data stream.
  ///
  /// - Parameter deviceID: UUID of the device to connect to
  /// - Returns: AsyncStream of data received from the device
  /// - Throws: BLEError if connection fails
  func connect(to deviceID: UUID) async throws -> AsyncStream<Data> {
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
    try await connectToPeripheral(peripheral)

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
    delegateHandler.setDataContinuation(continuation)

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
  func send(_ data: Data) async throws {
    logger.info("[BLE] send: \(data.count) bytes")
    try await claimWriteSlot(data: data)
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

  /// Sends data as an unacknowledged ATT Write Command (no `didWriteValue` ACK), which is
  /// what lets a caller pipeline back-to-back requests.
  ///
  /// This path is independent of the `.withResponse` machinery (`pendingWriteContinuation`,
  /// `writeTimeoutTask`, `writeWaiters`): flow control is CoreBluetooth's
  /// `canSendWriteWithoutResponse` backpressure plus the caller's bounded send window. It
  /// deliberately skips `writePacingDelay` — that delay protects the ESP32 RX queue during
  /// `.withResponse` bursts, and Write Commands are an nRF52-only path. A radio whose write
  /// characteristic does not advertise the capability degrades to the acknowledged path.
  func sendWithoutResponse(_ data: Data) async throws {
    let myGeneration = connectionGeneration

    guard case let .connected(peripheral, _, _, _) = phase,
          peripheral.state == .connected else {
      throw BLEError.notConnected
    }

    guard txSupportsWriteWithoutResponse else {
      try await claimWriteSlot(data: data)
      return
    }

    try await awaitWriteWithoutResponseReadiness(alreadyReady: peripheral.canSendWriteWithoutResponse)

    // Re-validate after a possible suspension: the connection may have dropped while parked.
    guard case let .connected(readyPeripheral, readyTx, _, _) = phase,
          readyPeripheral.state == .connected,
          connectionGeneration == myGeneration else {
      throw BLEError.notConnected
    }

    readyPeripheral.writeValue(data, for: readyTx, type: .withoutResponse)
  }

  /// Serializes concurrent writes by waiting for any pending write to complete,
  /// then claims the write slot and issues the BLE write.
  private func claimWriteSlot(data: Data) async throws {
    let myGeneration = connectionGeneration
    while true {
      guard connectionGeneration == myGeneration else {
        throw BLEError.notConnected
      }

      // Pacing: wait until earliestNextWrite.
      if writePacingDelay > 0, ContinuousClock.now < earliestNextWrite {
        try await Task.sleep(until: earliestNextWrite, clock: .continuous)
        try Task.checkCancellation()
        guard connectionGeneration == myGeneration else {
          throw BLEError.notConnected
        }
      }

      try Task.checkCancellation()

      guard case let .connected(peripheral, _, _, _) = phase else {
        throw BLEError.notConnected
      }

      guard peripheral.state == .connected else {
        throw BLEError.notConnected
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
        await withCheckedContinuation { (waiter: CheckedContinuation<Void, Never>) in
          writeWaiters.append(waiter)
        }
        continue
      }

      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        // Revalidate at claim time in case phase changed between loop iterations.
        guard case let .connected(currentPeripheral, currentTx, _, _) = self.phase,
              currentPeripheral.state == .connected,
              self.pendingWriteContinuation == nil else {
          continuation.resume(throwing: BLEError.notConnected)
          return
        }

        self.writeSequenceNumber += 1
        let currentSeq = self.writeSequenceNumber
        self.pendingWriteSequence = currentSeq
        self.pendingWriteContinuation = continuation
        // Record the sequence before issuing the write so didWriteValue
        // can tag its callback with the originating write
        self.delegateHandler.recordIssuedWriteSequence(currentSeq)
        currentPeripheral.writeValue(data, for: currentTx, type: .withResponse)

        // Cancel any previous timeout task and create a new one
        let seq = self.pendingWriteSequence
        self.writeTimeoutTask?.cancel()
        self.writeTimeoutTask = Task {
          try? await Task.sleep(for: .seconds(self.writeTimeout))
          guard !Task.isCancelled else { return }
          guard self.pendingWriteSequence == seq else { return }
          if let pending = self.pendingWriteContinuation {
            self.logger.warning("[BLE] Write timeout: seq=\(seq), elapsed=\(self.writeTimeout)s")
            self.pendingWriteContinuation = nil
            self.consecutiveQueuedWrites = 0
            pending.resume(throwing: BLEError.operationTimeout)
            self.writeTimeoutTask = nil
            self.earliestNextWrite = ContinuousClock.now.advanced(by: .seconds(self.writePacingDelay))
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
    waiter.resume()
  }

  /// Parks the caller until the peripheral can accept another Write Command. Returns
  /// immediately when `alreadyReady`. Otherwise suspends on a single continuation that is
  /// resumed by `handlePeripheralReadyForWriteWithoutResponse()`, released on teardown by
  /// `cancelPendingWriteOperations`, or failed by a timeout backstop — so a peripheral that
  /// stops signalling readiness cannot hang the sender past the per-write timeout.
  func awaitWriteWithoutResponseReadiness(alreadyReady: Bool) async throws {
    guard !alreadyReady else { return }
    let timeout = writeTimeout
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      // The pipeline issues one Write Command at a time, so the slot is normally free.
      // Release any stale waiter rather than leak it if that invariant is ever broken.
      if let stale = writeWithoutResponseReadyContinuation {
        writeWithoutResponseReadyContinuation = nil
        writeWithoutResponseReadyTimeoutTask?.cancel()
        writeWithoutResponseReadyTimeoutTask = nil
        stale.resume(throwing: BLEError.notConnected)
      }
      writeWithoutResponseReadyContinuation = continuation
      writeWithoutResponseReadyTimeoutTask = Task { [weak self] in
        try? await Task.sleep(for: .seconds(timeout))
        guard !Task.isCancelled else { return }
        await self?.timeoutWriteWithoutResponseReadiness()
      }
    }
  }

  /// Fails a parked `sendWithoutResponse` caller when the peripheral has not re-signalled
  /// readiness within the backstop interval. A no-op when no caller is parked.
  private func timeoutWriteWithoutResponseReadiness() {
    guard let continuation = writeWithoutResponseReadyContinuation else { return }
    writeWithoutResponseReadyContinuation = nil
    writeWithoutResponseReadyTimeoutTask = nil
    logger.warning("[BLE] Write-without-response readiness timeout after \(writeTimeout)s")
    continuation.resume(throwing: BLEError.operationTimeout)
  }

  /// Resumes a parked `sendWithoutResponse` caller when CoreBluetooth reports the peripheral
  /// can accept another Write Command. A no-op when no caller is parked.
  func handlePeripheralReadyForWriteWithoutResponse() {
    guard let continuation = writeWithoutResponseReadyContinuation else { return }
    writeWithoutResponseReadyContinuation = nil
    writeWithoutResponseReadyTimeoutTask?.cancel()
    writeWithoutResponseReadyTimeoutTask = nil
    continuation.resume()
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
    autoReconnectDiscoveryTimeoutTask?.cancel()
    autoReconnectDiscoveryTimeoutTask = nil
    serviceDiscoveryTimeoutTask?.cancel()
    serviceDiscoveryTimeoutTask = nil

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
      delegateHandler.setDataContinuation(nil)
      dataContinuation.finish()
    default:
      break
    }

    let deviceID = phase.deviceID
    phase = .idle
    phaseStartTime = Date()

    if let deviceID {
      onDisconnection?(deviceID, nil)
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
  func disconnect() async {
    logger.info("Disconnect requested")

    // Cancel Bluetooth power-off grace period
    bluetoothPowerOffGraceTask?.cancel()
    bluetoothPowerOffGraceTask = nil

    // Cancel write timeout task
    writeTimeoutTask?.cancel()
    writeTimeoutTask = nil

    // Reset queue tracking
    consecutiveQueuedWrites = 0
    earliestNextWrite = .now

    // Cancel pending write
    if let pending = pendingWriteContinuation {
      pendingWriteContinuation = nil
      pending.resume(throwing: BLEError.notConnected)
    }

    // Resume all write waiters (they'll fail on the .connected check)
    while !writeWaiters.isEmpty {
      writeWaiters.removeFirst().resume()
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
      try? await Task.sleep(for: .milliseconds(100))
    }

    transition(to: .idle)
    logger.info("Disconnect complete")
  }

  /// Switches to a different device.
  ///
  /// Disconnects from current device (if any) and connects to the new one.
  ///
  /// - Parameter deviceID: UUID of the new device to connect to
  /// - Returns: AsyncStream of data from the new device
  /// - Throws: BLEError if connection fails
  func switchDevice(to deviceID: UUID) async throws -> AsyncStream<Data> {
    logger.info("Switch device requested: \(deviceID)")

    // Disconnect current device
    await disconnect()

    // Connect to new device
    return try await connect(to: deviceID)
  }

  private func connectToPeripheral(_ peripheral: CBPeripheral) async throws {
    let pState = peripheralStateString(peripheral.state)
    logger.info("[BLE] Connecting to peripheral: \(peripheral.identifier.uuidString.prefix(8)), currentState: \(pState), timeout: \(connectionTimeout)s, autoReconnect: enabled")

    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      // Create timeout task
      let timeoutTask = Task {
        try? await Task.sleep(for: .seconds(connectionTimeout))
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
      centralManager.connect(peripheral, options: options)
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
    case let .connecting(_, _, timeoutTask):
      timeoutTask.cancel()

    case let .connected(_, _, _, dataContinuation):
      rssiKeepaliveTask?.cancel()
      rssiKeepaliveTask = nil
      consecutiveRSSIFailures = 0
      // Clear delegate handler's continuation first to stop data flow
      delegateHandler.setDataContinuation(nil)
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
    earliestNextWrite = .now
    // Outstanding write callbacks either never arrive (link dropped) or
    // find no continuation; their recorded sequences must not survive to
    // mis-tag the next connection's first write callback.
    delegateHandler.clearIssuedWriteSequences()

    if let pending = pendingWriteContinuation {
      pendingWriteContinuation = nil
      pending.resume(throwing: error)
    }

    while !writeWaiters.isEmpty {
      writeWaiters.removeFirst().resume()
    }

    // Release a Write-Command sender parked on backpressure so it doesn't hang past the drop.
    handlePeripheralReadyForWriteWithoutResponse()
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
      discoveryCompleteTeardownError = error as? BLEError ?? Self.makeConnectionError(error)

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

  /// Decides whether a discovery-phase watchdog should extend its window
  /// rather than tear the link down. When the peripheral is already
  /// `.connected` the BLE link is up and a `didConnect`/discovery callback is
  /// in flight or merely slow; tearing it down kills a working reconnection.
  /// Extend a bounded number of times so a genuinely wedged link still
  /// recovers via reconnect.
  static func shouldExtendDiscoveryTimeout(
    peripheralState: CBPeripheralState,
    extensions: Int,
    maxExtensions: Int
  ) -> Bool {
    peripheralState == .connected && extensions < maxExtensions
  }

  /// Classifies how a discovery/subscribe watchdog teardown surfaces. A
  /// peripheral that reached link-layer `.connected` yet never completed
  /// discovery across the full extension budget is the strongest in-app signal
  /// of a silently invalidated bond: CoreBluetooth delivers no error, so
  /// nothing else distinguishes it from a healthy-but-slow link. Surfacing it
  /// as `.authenticationFailed` routes it into the same guided re-pair recovery
  /// as a delivered bond-invalidation error, instead of the generic timeout
  /// retry loop that would keep re-trying the dead bond. A link that never
  /// reached `.connected` is a plain connection timeout.
  static func discoveryTimeoutError(
    peripheralState: CBPeripheralState,
    extensions: Int,
    maxExtensions: Int
  ) -> BLEError {
    if peripheralState == .connected, extensions >= maxExtensions {
      return .authenticationFailed
    }
    return .connectionTimeout
  }

  /// What the auto-reconnect discovery watchdog does when its window elapses.
  enum AutoReconnectTimeoutAction {
    case waitForPendingConnect
    case extendWindow
    case tearDown
  }

  /// Unlike service discovery (an established link, where a stall means tear
  /// down), a peripheral that is not `.connected` here is backed by an OS
  /// pending connect that never expires; cancelling it abandons a reconnection
  /// iOS would complete once the radio is back in range, so the watchdog
  /// waits. Waiting consumes no extension budget — the bounded extensions
  /// exist only for a connected-but-wedged discovery.
  static func autoReconnectTimeoutAction(
    peripheralState: CBPeripheralState,
    extensions: Int,
    maxExtensions: Int
  ) -> AutoReconnectTimeoutAction {
    guard peripheralState == .connected else { return .waitForPendingConnect }
    return extensions < maxExtensions ? .extendWindow : .tearDown
  }

  /// Counts one watchdog deferral against the current generation's budget.
  func recordDiscoveryTimeoutExtension() {
    discoveryTimeoutExtensions += 1
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

      if Self.shouldExtendDiscoveryTimeout(
        peripheralState: peripheral.state,
        extensions: discoveryTimeoutExtensions,
        maxExtensions: Self.maxDiscoveryTimeoutExtensions
      ) {
        recordDiscoveryTimeoutExtension()
        logger.warning("[BLE] Peripheral still connected; extending service discovery window (\(discoveryTimeoutExtensions)/\(Self.maxDiscoveryTimeoutExtensions)) instead of failing")
        armServiceDiscoveryTimeout(for: peripheral)
        return
      }

      let teardownError = Self.discoveryTimeoutError(
        peripheralState: peripheral.state,
        extensions: discoveryTimeoutExtensions,
        maxExtensions: Self.maxDiscoveryTimeoutExtensions
      )
      centralManager.cancelPeripheralConnection(peripheral)
      transition(to: .idle)
      c.resume(throwing: teardownError)
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

    switch Self.autoReconnectTimeoutAction(
      peripheralState: peripheral.state,
      extensions: discoveryTimeoutExtensions,
      maxExtensions: Self.maxDiscoveryTimeoutExtensions
    ) {
    case .waitForPendingConnect:
      logger.info(
        "[BLE] Auto-reconnect window elapsed while link down: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s; waiting for pending connect"
      )
      armAutoReconnectDiscoveryTimeout(for: peripheral, generation: generation)

    case .extendWindow:
      // iOS may flip the peripheral to .connected before delivering didConnect.
      // Tearing the link down here cancels a reconnection that actually
      // succeeded, after which the late didConnect lands in .idle and is
      // rejected. Give discovery another window instead of destroying it.
      recordDiscoveryTimeoutExtension()
      logger.warning("[BLE] Peripheral still connected; extending auto-reconnect discovery window (\(discoveryTimeoutExtensions)/\(Self.maxDiscoveryTimeoutExtensions)) instead of tearing down")
      armAutoReconnectDiscoveryTimeout(for: peripheral, generation: generation)

    case .tearDown:
      logger.warning(
        "[BLE] Auto-reconnect discovery timeout: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s"
      )
      let teardownError = Self.discoveryTimeoutError(
        peripheralState: peripheral.state,
        extensions: discoveryTimeoutExtensions,
        maxExtensions: Self.maxDiscoveryTimeoutExtensions
      )
      centralManager.cancelPeripheralConnection(peripheral)
      transition(to: .idle)
      onDisconnection?(peripheral.identifier, teardownError)
    }
  }
}
