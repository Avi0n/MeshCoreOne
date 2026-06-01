// BLEStateMachine.swift
@preconcurrency import CoreBluetooth
import Dispatch
import Foundation

/// Manages BLE connections using an explicit state machine.
///
/// All CoreBluetooth operations are modeled as state transitions. Each state
/// owns its resources (continuations, timeouts), ensuring proper cleanup
/// on any transition.
public actor BLEStateMachine: BLEStateMachineProtocol {

    // MARK: - Logging

    private let logger = PersistentLogger(subsystem: "com.mc1", category: "BLEStateMachine")
    private let instanceID = String(UUID().uuidString.prefix(8))
    private var lastCentralState: CBManagerState?

    private nonisolated var processContext: String {
        let processName = ProcessInfo.processInfo.processName
        let bundleID = Bundle.main.bundleIdentifier ?? "unknown"
        return "process: \(processName), bundle: \(bundleID)"
    }

    /// Converts CBPeripheralState to readable string for diagnostics
    private nonisolated func peripheralStateString(_ state: CBPeripheralState) -> String {
        switch state {
        case .disconnected: return "disconnected"
        case .connecting: return "connecting"
        case .connected: return "connected"
        case .disconnecting: return "disconnecting"
        @unknown default: return "unknown(\(state.rawValue))"
        }
    }

    // MARK: - State

    private var phase: BLEPhase = .idle

    /// Tracks when the current phase started (for timing diagnostics)
    private var phaseStartTime: Date = Date()

    /// Monotonically increasing generation counter. Incremented on each new
    /// connection or auto-reconnect cycle. Used to reject stale disconnect
    /// callbacks that arrive after a newer connection has started.
    private var connectionGeneration: UInt64 = 0

    /// Monotonic boundary timestamp for the current generation.
    /// Disconnect callbacks older than this belong to a previous generation.
    private var connectionGenerationStartTime: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()

    /// Expose current phase for testing
    public var currentPhase: BLEPhase { phase }

    /// Expose current connection generation for testing
    public var currentConnectionGeneration: UInt64 { connectionGeneration }

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
    private nonisolated(unsafe) var centralManager: CBCentralManager!
    private let delegateHandler: BLEDelegateHandler

    // MARK: - Configuration

    private let stateRestorationID = "com.pocketmesh.ble.central"
    private let connectionTimeout: TimeInterval
    private let serviceDiscoveryTimeout: TimeInterval
    private let autoReconnectDiscoveryTimeout: TimeInterval
    private let writeTimeout: TimeInterval

    /// Delay between write operations for ESP32 compatibility (0 = no pacing)
    private var writePacingDelay: TimeInterval = 0

    /// Next write blocked until this instant. Checked in claimWriteSlot
    /// so both queued and sequential writes are paced.
    private var earliestNextWrite: ContinuousClock.Instant = .now

    /// Tracks consecutive queued writes for diagnostic logging
    private var consecutiveQueuedWrites = 0
    private let queuePressureThreshold = 3

    // MARK: - UUIDs

    private let nordicUARTServiceUUID = CBUUID(string: BLEServiceUUID.nordicUART)
    private let txCharacteristicUUID = CBUUID(string: BLEServiceUUID.txCharacteristic)
    private let rxCharacteristicUUID = CBUUID(string: BLEServiceUUID.rxCharacteristic)

    /// Pending write continuation (only one write at a time)
    private var pendingWriteContinuation: CheckedContinuation<Void, Error>?

    /// Monotonic sequence number for correlating didWriteValue callbacks to the active write.
    /// Prevents a late callback from write N resuming write N+1's continuation.
    private var writeSequenceNumber: UInt64 = 0
    private var pendingWriteSequence: UInt64 = 0

    /// Queue of tasks waiting to write (serializes concurrent sends)
    private var writeWaiters: [CheckedContinuation<Void, Never>] = []

    /// Tracks the current write timeout task so it can be cancelled when write completes
    private var writeTimeoutTask: Task<Void, Never>?

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

    /// Tracks the service discovery timeout task so it can be cancelled on success
    private var serviceDiscoveryTimeoutTask: Task<Void, Never>?

    /// Tracks the auto-reconnect discovery timeout task so it can be cancelled on success
    private var autoReconnectDiscoveryTimeoutTask: Task<Void, Never>?

    /// Periodic RSSI read task that keeps the BLE connection alive in background.
    /// Without periodic BLE activity, iOS may drop idle connections.
    private var rssiKeepaliveTask: Task<Void, Never>?

    /// Consecutive RSSI read failures. Reset on success. Logged for diagnostics.
    private var consecutiveRSSIFailures = 0

    /// Tracks whether the app is in the foreground. Used to gate
    /// keepalive and timeout behavior.
    private var isAppActive = true

    /// Tracks whether CBCentralManager has been created
    private var isActivated = false

    /// Grace period task for poweredOff during waitingForBluetooth.
    /// Allows CBCentralManager initialization to settle (poweredOff → poweredOn).
    private var bluetoothPowerOffGraceTask: Task<Void, Never>?

    // MARK: - Scanning (orthogonal to connection lifecycle)

    private var isCurrentlyScanning = false
    private var pendingScanRequest = false
    private var onDeviceDiscovered: (@Sendable (UUID, String?, Int) -> Void)?

    // MARK: - Callbacks

    private var onDisconnection: (@Sendable (UUID, Error?) -> Void)?
    private var onReconnection: (@Sendable (UUID, AsyncStream<Data>) -> Void)?
    private var onBluetoothStateChange: (@Sendable (CBManagerState) -> Void)?
    private var onBluetoothPoweredOn: (@Sendable () -> Void)?
    /// Called when entering iOS auto-reconnecting phase.
    /// The device has disconnected but iOS will attempt automatic reconnection.
    /// Note: The MeshCore session is invalid at this point and will be rebuilt upon successful reconnection.
    private var onAutoReconnecting: (@Sendable (UUID, String) -> Void)?

    /// Distinguishes the call site driving `handleRestoredPeripheral` so the function
    /// fires `onAutoReconnecting` only when no upstream caller has already claimed the cycle.
    private enum RestoredPeripheralSource {
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
    public init(
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
        self.delegateHandler = BLEDelegateHandler()
    }

    /// Sets the write pacing delay for ESP32 compatibility.
    /// - Parameter delay: Delay in seconds between write operations (0 = no pacing)
    public func setWritePacingDelay(_ delay: TimeInterval) {
        writePacingDelay = delay
    }

    /// Activates the BLE state machine, creating the CBCentralManager.
    /// Call once during app initialization. Safe to call multiple times.
    public func activate() {
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
        self.centralManager = CBCentralManager(
            delegate: delegateHandler,
            queue: centralQueue,
            options: options
        )
    }

    // MARK: - Connection Generation

    /// Advances the connection generation counter and records the boundary timestamp.
    /// Called when starting a new connection, auto-reconnect cycle, or restoration reconnect
    /// so that stale disconnect callbacks from previous generations can be identified and rejected.
    private func advanceConnectionGeneration() {
        connectionGeneration &+= 1
        connectionGenerationStartTime = CFAbsoluteTimeGetCurrent()
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

    // MARK: - Public API

    /// Whether the state machine is currently connected to a device
    public var isConnected: Bool {
        if case .connected = phase { return true }
        return false
    }

    /// Whether the state machine is currently handling iOS auto-reconnect or state restoration
    public var isAutoReconnecting: Bool {
        switch phase {
        case .autoReconnecting, .restoringState:
            return true
        default:
            return false
        }
    }

    /// UUID of the currently connected device, or nil if not connected
    public var connectedDeviceID: UUID? {
        phase.deviceID
    }

    /// Current Bluetooth hardware state
    public nonisolated var bluetoothState: CBManagerState {
        centralManager?.state ?? .unknown
    }

    /// Current phase name for diagnostic logging
    public var currentPhaseName: String {
        phase.name
    }

    /// Current peripheral state for diagnostic logging (nil if no peripheral)
    public var currentPeripheralState: String? {
        guard let peripheral = phase.peripheral else { return nil }
        return peripheralStateString(peripheral.state)
    }

    /// Whether the Bluetooth central manager is in the powered-off state.
    public var isBluetoothPoweredOff: Bool {
        centralManager?.state == .poweredOff
    }

    /// Current CBCentralManager state name for diagnostic logging
    public var centralManagerStateName: String {
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
    public func isDeviceConnectedToSystem(_ deviceID: UUID) -> Bool {
        activate()
        let connectedPeripherals = centralManager.retrieveConnectedPeripherals(
            withServices: [nordicUARTServiceUUID]
        )
        return connectedPeripherals.contains { $0.identifier == deviceID }
    }

    public func systemConnectedPeripheralIDs() -> [UUID] {
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
    public func startAdoptingSystemConnectedPeripheral(_ deviceID: UUID) -> Bool {
        activate()

        guard case .idle = phase else {
            logger.info("[BLE] adoptSystemConnectedPeripheral skipped - phase: \(self.phase.name)")
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
    public func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) {
        onDisconnection = handler
    }

    /// Sets a handler for reconnection events.
    /// The handler receives the device ID and the data stream for receiving data.
    public func setReconnectionHandler(_ handler: @escaping @Sendable (UUID, AsyncStream<Data>) -> Void) {
        onReconnection = handler
    }

    /// Sets a handler for Bluetooth state changes
    public func setBluetoothStateChangeHandler(_ handler: @escaping @Sendable (CBManagerState) -> Void) {
        onBluetoothStateChange = handler
    }

    /// Sets a handler called when Bluetooth powers on
    public func setBluetoothPoweredOnHandler(_ handler: @escaping @Sendable () -> Void) {
        onBluetoothPoweredOn = handler
    }

    /// Sets a handler for auto-reconnecting events.
    /// Called when device disconnects but iOS is attempting automatic reconnection.
    public func setAutoReconnectingHandler(_ handler: @escaping @Sendable (UUID, String) -> Void) {
        onAutoReconnecting = handler
    }

    // MARK: - BLE Scanning

    /// Sets a handler called when a device is discovered during scanning.
    /// - Parameter handler: Callback with (deviceID, advertised name, rssi)
    public func setDeviceDiscoveredHandler(_ handler: @escaping @Sendable (UUID, String?, Int) -> Void) {
        onDeviceDiscovered = handler
    }

    /// Starts scanning for BLE peripherals advertising the Nordic UART service.
    /// Scanning is orthogonal to the connection lifecycle — it works while connected.
    /// Requires `activate()` to have been called and Bluetooth to be powered on.
    public func startScanning() {
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
    public func stopScanning() {
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
    public func waitForPoweredOn() async throws {
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
            phase = .waitingForBluetooth(continuation: continuation)
        }
    }

    /// Connects to a BLE device and returns a data stream.
    ///
    /// - Parameter deviceID: UUID of the device to connect to
    /// - Returns: AsyncStream of data received from the device
    /// - Throws: BLEError if connection fails
    public func connect(to deviceID: UUID) async throws -> AsyncStream<Data> {
        logger.info("Connect requested for device: \(deviceID)")

        // Ensure we're in idle state
        guard case .idle = phase else {
            // Diagnostic: Log detailed state when connection is rejected
            let peripheralState = phase.peripheral.map { peripheralStateString($0.state) } ?? "none"
            let phaseDeviceID = phase.deviceID?.uuidString ?? "none"
            logger.warning(
                "Connect rejected - phase: \(self.phase.name), peripheralState: \(peripheralState), phaseDeviceID: \(phaseDeviceID), requestedDeviceID: \(deviceID)"
            )
            throw BLEError.connectionFailed("Already in operation: \(phase.name)")
        }

        // Wait for Bluetooth
        try await waitForPoweredOn()

        // Retrieve peripheral
        let peripherals = centralManager.retrievePeripherals(withIdentifiers: [deviceID])
        guard let peripheral = peripherals.first else {
            logger.warning("[BLE] Device not found in peripheral cache: \(deviceID.uuidString.prefix(8))")
            throw BLEError.deviceNotFound
        }

        // Advance connection generation before starting connection
        advanceConnectionGeneration()
        logger.info("[BLE] Connection generation advanced to \(self.connectionGeneration) for device: \(deviceID.uuidString.prefix(8))")

        // Connect and discover services (continuation spans entire discovery chain)
        try await connectToPeripheral(peripheral)

        // Create data stream and transition to connected
        let (stream, continuation) = AsyncStream.makeStream(
            of: Data.self,
            bufferingPolicy: .bufferingOldest(512)
        )

        guard case .discoveryComplete(_, let tx, let rx) = phase else {
            throw BLEError.connectionFailed("Unexpected state after service discovery")
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
    public func send(_ data: Data) async throws {
        logger.info("[BLE] send: \(data.count) bytes")
        try await claimWriteSlot(data: data)
    }

    /// Whether the connected peripheral's write characteristic supports ATT Write Commands.
    /// Drives the transport's `supportsWriteWithoutResponse` capability gate.
    public var supportsWriteWithoutResponse: Bool { txSupportsWriteWithoutResponse }

    /// Test observability: whether a `sendWithoutResponse` caller is currently parked
    /// awaiting peripheral readiness. Reflects existing state; does not alter behavior.
    var isAwaitingWriteWithoutResponseReady: Bool { writeWithoutResponseReadyContinuation != nil }

    /// Sends data as an unacknowledged ATT Write Command (no `didWriteValue` ACK), which is
    /// what lets a caller pipeline back-to-back requests.
    ///
    /// This path is independent of the `.withResponse` machinery (`pendingWriteContinuation`,
    /// `writeTimeoutTask`, `writeWaiters`): flow control is CoreBluetooth's
    /// `canSendWriteWithoutResponse` backpressure plus the caller's bounded send window. It
    /// deliberately skips `writePacingDelay` — that delay protects the ESP32 RX queue during
    /// `.withResponse` bursts, and Write Commands are an nRF52-only path. A radio whose write
    /// characteristic does not advertise the capability degrades to the acknowledged path.
    public func sendWithoutResponse(_ data: Data) async throws {
        let myGeneration = connectionGeneration

        guard case .connected(let peripheral, _, _, _) = phase,
              peripheral.state == .connected else {
            throw BLEError.notConnected
        }

        guard txSupportsWriteWithoutResponse else {
            try await claimWriteSlot(data: data)
            return
        }

        try await awaitWriteWithoutResponseReadiness(alreadyReady: peripheral.canSendWriteWithoutResponse)

        // Re-validate after a possible suspension: the connection may have dropped while parked.
        guard case .connected(let readyPeripheral, let readyTx, _, _) = phase,
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

            guard case .connected(let peripheral, _, _, _) = phase else {
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
                guard case .connected(let currentPeripheral, let currentTx, _, _) = self.phase,
                      currentPeripheral.state == .connected,
                      self.pendingWriteContinuation == nil else {
                    continuation.resume(throwing: BLEError.notConnected)
                    return
                }

                self.writeSequenceNumber += 1
                let currentSeq = self.writeSequenceNumber
                self.pendingWriteSequence = currentSeq
                self.pendingWriteContinuation = continuation
                // Publish sequence to delegate handler so didWriteValue can tag the callback
                self.delegateHandler.writeSequenceLock.withLock { $0 = currentSeq }
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
    private func resumeNextWriteWaiter() {
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
        logger.warning("[BLE] Write-without-response readiness timeout after \(self.writeTimeout)s")
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
        logger.info("[BLE] tx writeWithoutResponse capability: \(self.txSupportsWriteWithoutResponse)")
    }

    /// Gracefully shuts down the state machine, resuming all pending operations with cancellation.
    /// Call this before dropping the last reference to the actor.
    public func shutdown() {
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
        case .waitingForBluetooth(let continuation):
            continuation.resume(throwing: CancellationError())
        case .connecting(_, let continuation, let timeoutTask):
            timeoutTask.cancel()
            continuation.resume(throwing: CancellationError())
        case .discoveringServices(_, let continuation):
            continuation.resume(throwing: CancellationError())
        case .discoveringCharacteristics(_, _, let continuation):
            continuation.resume(throwing: CancellationError())
        case .subscribingToNotifications(_, _, _, let continuation):
            continuation.resume(throwing: CancellationError())
        case .connected(_, _, _, let dataContinuation):
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

    public func appDidEnterBackground() {
        isAppActive = false
        autoReconnectDiscoveryTimeoutTask?.cancel()
        autoReconnectDiscoveryTimeoutTask = nil
        logger.info("[BLE] App entered background: cancelled auto-reconnect timeout (keepalive persists)")
    }

    public func appDidBecomeActive() {
        isAppActive = true
        logger.info("[BLE] App became active, phase: \(phase.name)")

        // Defensive restart: only if connected but keepalive task died unexpectedly
        if case .connected(let peripheral, _, _, _) = phase, rssiKeepaliveTask == nil {
            logger.warning("[BLE] Keepalive task died while connected - restarting defensively")
            startRSSIKeepalive(for: peripheral)
        }

        // Re-arm auto-reconnect timeout if in auto-reconnecting phase
        if case .autoReconnecting(let peripheral, _, _) = phase {
            phaseStartTime = Date()
            armAutoReconnectDiscoveryTimeout(
                for: peripheral,
                generation: connectionGeneration
            )
            logger.info("[BLE] Re-armed auto-reconnect timeout after foreground return")
        }
    }

    private func armAutoReconnectDiscoveryTimeout(
        for peripheral: CBPeripheral,
        generation: UInt64
    ) {
        autoReconnectDiscoveryTimeoutTask?.cancel()
        logger.info("[BLE] Arming auto-reconnect discovery timeout: \(self.autoReconnectDiscoveryTimeout)s, generation: \(generation), device: \(peripheral.identifier.uuidString.prefix(8))")
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
    public func disconnect() async {
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

        // Cancel current operation
        cancelCurrentOperation(with: BLEError.notConnected)

        // Disconnect peripheral if connected
        if let peripheral, peripheral.state == .connected || peripheral.state == .connecting {
            phase = .disconnecting(peripheral: peripheral)
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
    public func switchDevice(to deviceID: UUID) async throws -> AsyncStream<Data> {
        logger.info("Switch device requested: \(deviceID)")

        // Disconnect current device
        await disconnect()

        // Connect to new device
        return try await connect(to: deviceID)
    }

    private func connectToPeripheral(_ peripheral: CBPeripheral) async throws {
        let pState = peripheralStateString(peripheral.state)
        logger.info("[BLE] Connecting to peripheral: \(peripheral.identifier.uuidString.prefix(8)), currentState: \(pState), timeout: \(self.connectionTimeout)s, autoReconnect: enabled")

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            // Create timeout task
            let timeoutTask = Task {
                try? await Task.sleep(for: .seconds(connectionTimeout))
                self.handleConnectionTimeout(for: peripheral)
            }

            phase = .connecting(
                peripheral: peripheral,
                continuation: continuation,
                timeoutTask: timeoutTask
            )

            let options: [String: Any] = [
                CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
                CBConnectPeripheralOptionNotifyOnNotificationKey: true,
                CBConnectPeripheralOptionEnableAutoReconnect: true
            ]
            centralManager.connect(peripheral, options: options)
        }
    }

    private func handleConnectionTimeout(for peripheral: CBPeripheral) {
        guard case .connecting(let expected, let continuation, _) = phase,
              expected.identifier == peripheral.identifier else {
            return  // No longer connecting to this peripheral
        }

        let pState = peripheralStateString(peripheral.state)
        let elapsed = Date().timeIntervalSince(phaseStartTime)
        logger.warning("[BLE] Connection timeout: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")
        centralManager.cancelPeripheralConnection(peripheral)
        transition(to: .idle)
        continuation.resume(throwing: BLEError.connectionTimeout)
    }
}

// MARK: - Internal Callback Handlers (stubs for now)

extension BLEStateMachine {

    func handleCentralManagerDidUpdateState(_ state: CBManagerState) {
        let stateString: String
        switch state {
        case .unknown: stateString = "unknown"
        case .resetting: stateString = "resetting"
        case .unsupported: stateString = "unsupported"
        case .unauthorized: stateString = "unauthorized"
        case .poweredOff: stateString = "poweredOff"
        case .poweredOn: stateString = "poweredOn"
        @unknown default: stateString = "unknown(\(state.rawValue))"
        }
        if lastCentralState != state {
            lastCentralState = state
            logger.info(
                "[BLE] Central manager state changed: \(stateString), currentPhase: \(self.phase.name), instance: \(instanceID), \(processContext)"
            )
        }
        onBluetoothStateChange?(state)

        switch state {
        case .poweredOn:
            // Cancel any poweredOff grace period — Bluetooth is now available
            bluetoothPowerOffGraceTask?.cancel()
            bluetoothPowerOffGraceTask = nil

            // Resume waiting continuation if any
            if case .waitingForBluetooth(let continuation) = phase {
                transition(to: .idle)
                continuation.resume()
            }

            // Handle state restoration from phase
            if case .restoringState(let peripheral) = phase {
                handleRestoredPeripheral(peripheral)
            }

            // Fulfill pending scan request
            if pendingScanRequest {
                startScanning()
            }

            // Notify handler for power-on events
            onBluetoothPoweredOn?()

        case .poweredOff:
            let wasScanning = isCurrentlyScanning
            isCurrentlyScanning = false
            if wasScanning {
                pendingScanRequest = true
            }

            if case .waitingForBluetooth = phase {
                // A freshly created CBCentralManager may briefly report poweredOff
                // before settling on poweredOn. Start a grace period instead of
                // failing immediately, so the initialization can complete.
                if bluetoothPowerOffGraceTask == nil {
                    logger.info("[BLE] poweredOff during waitingForBluetooth — starting grace period")
                    bluetoothPowerOffGraceTask = Task {
                        try? await Task.sleep(for: .seconds(1))
                        guard !Task.isCancelled else { return }
                        self.handleBluetoothPowerOffGraceExpired()
                    }
                }
            } else {
                // Not waiting — cancel any active operation immediately
                let deviceID = phase.deviceID
                cancelCurrentOperation(with: BLEError.bluetoothPoweredOff)
                if let deviceID {
                    onDisconnection?(deviceID, nil)
                }
            }

        case .unauthorized:
            handleBluetoothBecomingUnavailable(error: .bluetoothUnauthorized)

        case .unsupported:
            handleBluetoothBecomingUnavailable(error: .bluetoothUnavailable)

        default:
            break
        }
    }

    /// Called when the poweredOff grace period expires without poweredOn arriving.
    private func handleBluetoothPowerOffGraceExpired() {
        bluetoothPowerOffGraceTask = nil
        guard case .waitingForBluetooth = phase else { return }
        logger.info("[BLE] poweredOff grace period expired — Bluetooth is off")
        let deviceID = phase.deviceID
        cancelCurrentOperation(with: BLEError.bluetoothPoweredOff)
        if let deviceID {
            onDisconnection?(deviceID, nil)
        }
    }

    /// Handles Bluetooth becoming permanently unavailable (unauthorized or unsupported).
    private func handleBluetoothBecomingUnavailable(error: BLEError) {
        isCurrentlyScanning = false
        pendingScanRequest = false
        if case .waitingForBluetooth(let continuation) = phase {
            transition(to: .idle)
            continuation.resume(throwing: error)
        }
        if case .restoringState(let peripheral) = phase {
            transition(to: .idle)
            onDisconnection?(peripheral.identifier, nil)
        }
    }

    private func handleRestoredPeripheral(_ peripheral: CBPeripheral, source: RestoredPeripheralSource = .stateRestoration) {
        let pState = peripheralStateString(peripheral.state)
        logger.info("[BLE] Processing restored peripheral: \(peripheral.identifier.uuidString.prefix(8)), state: \(pState), source: \(source)")

        peripheral.delegate = delegateHandler

        // Advance connection generation for restoration-driven reconnect
        advanceConnectionGeneration()

        // Start timeout for auto-reconnect discovery
        armAutoReconnectDiscoveryTimeout(for: peripheral, generation: connectionGeneration)

        transition(to: .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil))

        // State-restoration paths claim the reconnect cycle here so the coordinator's
        // strict completion guard accepts the eventual onReconnection. The adoption
        // path already claimed via startAdoptingLastSystemConnectedPeripheralIfAvailable
        // and passes .adoption to skip and avoid double-claiming.
        if source == .stateRestoration {
            onAutoReconnecting?(peripheral.identifier, "state-restoration")
        }

        if peripheral.state == .connected {
            // Already connected, just need to rediscover services
            peripheral.discoverServices([nordicUARTServiceUUID])
        } else if peripheral.state == .connecting {
            // Connection in progress, wait for didConnect
        } else {
            // Not connected, try to reconnect
            let options: [String: Any] = [
                CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
                CBConnectPeripheralOptionEnableAutoReconnect: true
            ]
            centralManager.connect(peripheral, options: options)
        }
    }

    func handleWillRestoreState(_ peripheral: CBPeripheral) {
        let pState = peripheralStateString(peripheral.state)
        logger.info("[BLE] State restoration callback: \(peripheral.identifier.uuidString.prefix(8)), state: \(pState)")

        // If Bluetooth is already powered on, proceed directly to restoration.
        // This handles the edge case where .poweredOn Task runs before this Task.
        if centralManager.state == .poweredOn {
            handleRestoredPeripheral(peripheral)
        } else {
            transition(to: .restoringState(peripheral: peripheral))
        }
    }

    func handleDidConnect(_ peripheral: CBPeripheral) {
        let pState = peripheralStateString(peripheral.state)
        let elapsed = Date().timeIntervalSince(phaseStartTime)
        logger.info("[BLE] Did connect: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), phase: \(self.phase.name), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")

        // Handle auto-reconnect
        if case .autoReconnecting(let expected, _, _) = phase,
           peripheral.identifier == expected.identifier {
            logger.info("[BLE] Auto-reconnect: peripheral connected, discovering services")
            peripheral.delegate = delegateHandler
            peripheral.discoverServices([nordicUARTServiceUUID])

            // Cancel any existing timeout (e.g., from handleRestoredPeripheral) and restart
            armAutoReconnectDiscoveryTimeout(for: peripheral, generation: connectionGeneration)
            return
        }

        // Normal connection flow
        guard case .connecting(let expected, let continuation, let timeoutTask) = phase,
              expected.identifier == peripheral.identifier else {
            logger.warning("Unexpected didConnect for \(peripheral.identifier)")
            cancelUnexpectedPeripheral(peripheral)
            return
        }

        timeoutTask.cancel()

        // Arm discovery timeout before starting discovery so the callback
        // window between discoverServices() and timeout creation is closed.
        serviceDiscoveryTimeoutTask = Task {
            try? await Task.sleep(for: .seconds(serviceDiscoveryTimeout))
            guard !Task.isCancelled else { return }
            handleServiceDiscoveryTimeout(for: peripheral)
        }

        transition(to: .discoveringServices(
            peripheral: peripheral,
            continuation: continuation
        ))

        peripheral.delegate = delegateHandler
        peripheral.discoverServices([nordicUARTServiceUUID])
    }

    func handleDidFailToConnect(_ peripheral: CBPeripheral, error: Error?) {
        let pState = peripheralStateString(peripheral.state)
        let elapsed = Date().timeIntervalSince(phaseStartTime)
        var errorInfo = "none"
        if let error = error as NSError? {
            errorInfo = "domain=\(error.domain), code=\(error.code), desc=\(error.localizedDescription)"
        }
        logger.warning(
            "[BLE] Did fail to connect: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), phase: \(self.phase.name), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s, error: \(errorInfo)"
        )

        // Handle failure during auto-reconnect (iOS auto-reconnect gave up)
        if case .autoReconnecting(let expected, _, _) = phase,
           expected.identifier == peripheral.identifier {
            logger.warning("Auto-reconnect failed for \(peripheral.identifier) - transitioning to idle")
            transition(to: .idle)
            onDisconnection?(peripheral.identifier, error)
            return
        }

        guard case .connecting(let expected, let continuation, let timeoutTask) = phase,
              expected.identifier == peripheral.identifier else {
            logger.info("Ignoring didFailToConnect - not our peripheral or unexpected phase")
            return
        }

        timeoutTask.cancel()
        transition(to: .idle)
        continuation.resume(throwing: Self.makeConnectionError(error))
    }

    /// Maps a CoreBluetooth error to a typed BLEError. Auth/encryption codes
    /// from CBATTError or CBError get the typed `.authenticationFailed` case so
    /// detection survives iOS localizing the error description in any locale.
    static func makeConnectionError(_ error: Error?, fallback: String = "Unknown error") -> BLEError {
        if let nsError = error as NSError? {
            if nsError.domain == CBATTErrorDomain {
                switch nsError.code {
                case CBATTError.insufficientAuthentication.rawValue,
                     CBATTError.insufficientAuthorization.rawValue,
                     CBATTError.insufficientEncryption.rawValue,
                     CBATTError.insufficientEncryptionKeySize.rawValue:
                    return .authenticationFailed
                default:
                    break
                }
            }
            if nsError.domain == CBErrorDomain,
               nsError.code == CBError.encryptionTimedOut.rawValue {
                return .authenticationFailed
            }
        }
        return .connectionFailed(error?.localizedDescription ?? fallback)
    }

    func handleDidDisconnect(_ peripheral: CBPeripheral, timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
        let pState = peripheralStateString(peripheral.state)
        let elapsed = Date().timeIntervalSince(phaseStartTime)
        var errorInfo = "none"
        if let error = error as NSError? {
            errorInfo = "domain=\(error.domain), code=\(error.code), desc=\(error.localizedDescription)"
        }
        let elapsedStr = elapsed.formatted(.number.precision(.fractionLength(2)))
        logger.info(
            "[BLE] Did disconnect: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), isReconnecting: \(isReconnecting), phase: \(self.phase.name), elapsed: \(elapsedStr)s, error: \(errorInfo)"
        )

        // C7: Ignore stale disconnects for peripherals that don't match the active session.
        // A delayed callback from an old peripheral must not cancel the current session.
        if let activePeripheral = phase.peripheral,
           activePeripheral.identifier != peripheral.identifier {
            logger.warning("[BLE] Ignoring stale didDisconnect for \(peripheral.identifier.uuidString.prefix(8)), active: \(activePeripheral.identifier.uuidString.prefix(8))")
            return
        }

        // Primary stale-callback fence: reject disconnect callbacks from a previous generation.
        // After app resume, iOS may deliver queued disconnects from before the suspend.
        // We use CFAbsoluteTime (not a generation counter captured at callback delivery time)
        // because CoreBluetooth's didDisconnectPeripheral timestamp reflects the disconnect
        // event time per Apple's header ("now or a few seconds ago"), not delivery time.
        // A generation captured at delivery time would be unsafe if advanceConnectionGeneration()
        // runs between the event and callback delivery. CFAbsoluteTimeGetCurrent() is not
        // guaranteed monotonic (NTP adjustments can cause backward jumps), so the 1.0s
        // tolerance accommodates typical clock corrections. The peripheral identity check
        // above provides the primary defense; this timestamp fence is a secondary guard for
        // same-peripheral stale callbacks across generation boundaries.
        let generationStart = connectionGenerationStartTime
        if Self.isDisconnectCallbackFromPreviousGeneration(
            timestamp: timestamp,
            generationStart: generationStart
        ) {
            let callbackAge = CFAbsoluteTimeGetCurrent() - timestamp
            logger.warning(
                "[BLE] Ignoring stale disconnect callback: " +
                "age=\(callbackAge.formatted(.number.precision(.fractionLength(1))))s, " +
                "generation=\(connectionGeneration), phase=\(phase.name)"
            )
            return
        }

        // Secondary diagnostic: flag very old callbacks, but do not drop callbacks
        // that belong to the current connection generation.
        let callbackAge = CFAbsoluteTimeGetCurrent() - timestamp
        if callbackAge > 120 {
            logger.warning(
                "[BLE] Processing aged disconnect callback: " +
                "age=\(callbackAge.formatted(.number.precision(.fractionLength(1))))s, " +
                "generation=\(connectionGeneration), phase=\(phase.name)"
            )
        }

        let deviceID = peripheral.identifier

        if isReconnecting {
            handleAutoReconnectDisconnect(peripheral: peripheral, error: error)
        } else {
            handleFullDisconnect(deviceID: deviceID, error: error)
        }
    }

    /// Handles a disconnect where iOS is auto-reconnecting the peripheral.
    /// Setup-phase continuations route through makeConnectionError so a CBATT
    /// auth/encryption code arriving before the bond is established still maps
    /// to the typed BLEError.authenticationFailed.
    private func handleAutoReconnectDisconnect(peripheral: CBPeripheral, error: Error?) {
        let deviceID = peripheral.identifier
        var errorInfo = "none"
        if let nsError = error as NSError? {
            errorInfo = "domain=\(nsError.domain), code=\(nsError.code), desc=\(nsError.localizedDescription)"
        }
        logger.info("[BLE] iOS auto-reconnect started: \(deviceID.uuidString.prefix(8)), will attempt automatic reconnection")

        // Clean up pending operations before transitioning.
        // This ensures any pending setup continuations and write waiters are properly
        // resumed/failed, preventing orphaned continuations and waiter starvation.
        cancelPendingWriteOperations()

        // Clean up current state but preserve peripheral for reconnection.
        // transition() handles dataContinuation cleanup when leaving .connected.
        // Note: We handle phase continuations manually below since cancelCurrentOperation
        // would transition to .idle, but we need to go to .autoReconnecting.
        let setupError = Self.makeConnectionError(error, fallback: "Disconnected during setup")
        switch phase {
        case .connecting(_, let continuation, let timeoutTask):
            timeoutTask.cancel()
            continuation.resume(throwing: setupError)
        case .discoveringServices(_, let continuation):
            continuation.resume(throwing: setupError)
        case .discoveringCharacteristics(_, _, let continuation):
            continuation.resume(throwing: setupError)
        case .subscribingToNotifications(_, _, _, let continuation):
            continuation.resume(throwing: setupError)
        default:
            break
        }

        // Advance generation for the auto-reconnect cycle
        advanceConnectionGeneration()

        transition(to: .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil))

        // C5: Arm the auto-reconnect discovery timeout (same as restoration path)
        armAutoReconnectDiscoveryTimeout(for: peripheral, generation: connectionGeneration)

        // Notify handler so UI can show "connecting" state
        onAutoReconnecting?(deviceID, errorInfo)
    }

    /// Handles a full (non-reconnecting) disconnection.
    private func handleFullDisconnect(deviceID: UUID, error: Error?) {
        switch phase {
        case .disconnecting:
            // Expected disconnection, transition handled by disconnect()
            break

        case .connected, .autoReconnecting:
            // Unexpected disconnection
            cancelCurrentOperation(with: BLEError.notConnected)
            onDisconnection?(deviceID, error)

        default:
            // Disconnection during connection attempt
            cancelCurrentOperation(with: Self.makeConnectionError(error, fallback: "Disconnected during setup"))
        }
    }

    func handleDidDiscoverServices(_ peripheral: CBPeripheral, error: Error?) {
        let serviceCount = peripheral.services?.count ?? 0
        let hasNordicUART = peripheral.services?.contains { $0.uuid == nordicUARTServiceUUID } ?? false
        logger.info("[BLE] Did discover services: \(peripheral.identifier.uuidString.prefix(8)), count: \(serviceCount), hasNordicUART: \(hasNordicUART), error: \(error?.localizedDescription ?? "none")")

        // Handle auto-reconnect
        if case .autoReconnecting(let expected, _, _) = phase,
           peripheral.identifier == expected.identifier {
            if let error {
                logger.warning("Auto-reconnect service discovery failed: \(error.localizedDescription)")
                transition(to: .idle)
                onDisconnection?(expected.identifier, error)
                return
            }

            guard let service = peripheral.services?.first(where: { $0.uuid == nordicUARTServiceUUID }) else {
                logger.warning("Auto-reconnect: service not found")
                transition(to: .idle)
                onDisconnection?(expected.identifier, nil)
                return
            }

            peripheral.discoverCharacteristics([txCharacteristicUUID, rxCharacteristicUUID], for: service)
            return
        }

        // Normal flow
        guard case .discoveringServices(let expected, let continuation) = phase,
              expected.identifier == peripheral.identifier else {
            logger.warning("Unexpected didDiscoverServices")
            return
        }

        if let error {
            transition(to: .idle)
            continuation.resume(throwing: Self.makeConnectionError(error))
            return
        }

        guard let service = peripheral.services?.first(where: { $0.uuid == nordicUARTServiceUUID }) else {
            transition(to: .idle)
            continuation.resume(throwing: BLEError.characteristicNotFound)
            return
        }

        transition(to: .discoveringCharacteristics(
            peripheral: peripheral,
            service: service,
            continuation: continuation
        ))

        peripheral.discoverCharacteristics([txCharacteristicUUID, rxCharacteristicUUID], for: service)
    }

    func handleDidDiscoverCharacteristics(_ peripheral: CBPeripheral, service: CBService, error: Error?) {
        let characteristics = service.characteristics ?? []
        let hasTX = characteristics.contains { $0.uuid == txCharacteristicUUID }
        let hasRX = characteristics.contains { $0.uuid == rxCharacteristicUUID }
        logger.info("[BLE] Did discover characteristics: \(peripheral.identifier.uuidString.prefix(8)), count: \(characteristics.count), hasTX: \(hasTX), hasRX: \(hasRX), error: \(error?.localizedDescription ?? "none")")

        // Handle auto-reconnect
        if case .autoReconnecting(let expected, _, _) = phase,
           peripheral.identifier == expected.identifier {
            if let error {
                logger.warning("Auto-reconnect characteristic discovery failed: \(error.localizedDescription)")
                transition(to: .idle)
                onDisconnection?(expected.identifier, error)
                return
            }

            guard let characteristics = service.characteristics,
                  let tx = characteristics.first(where: { $0.uuid == txCharacteristicUUID }),
                  let rx = characteristics.first(where: { $0.uuid == rxCharacteristicUUID }) else {
                logger.warning("Auto-reconnect: characteristics not found")
                transition(to: .idle)
                onDisconnection?(expected.identifier, nil)
                return
            }

            captureWriteWithoutResponseCapability(from: tx)

            // Store tx/rx in phase for use when notification subscription completes
            transition(to: .autoReconnecting(peripheral: peripheral, tx: tx, rx: rx))

            // Subscribe to notifications to complete reconnection
            peripheral.setNotifyValue(true, for: rx)
            return
        }

        // Normal flow
        guard case .discoveringCharacteristics(let expected, let expectedService, let continuation) = phase,
              expected.identifier == peripheral.identifier,
              expectedService.uuid == service.uuid else {
            logger.warning("Unexpected didDiscoverCharacteristics")
            return
        }

        if let error {
            transition(to: .idle)
            continuation.resume(throwing: Self.makeConnectionError(error))
            return
        }

        guard let characteristics = service.characteristics,
              let tx = characteristics.first(where: { $0.uuid == txCharacteristicUUID }),
              let rx = characteristics.first(where: { $0.uuid == rxCharacteristicUUID }) else {
            transition(to: .idle)
            continuation.resume(throwing: BLEError.characteristicNotFound)
            return
        }

        captureWriteWithoutResponseCapability(from: tx)

        transition(to: .subscribingToNotifications(
            peripheral: peripheral,
            tx: tx,
            rx: rx,
            continuation: continuation
        ))

        peripheral.setNotifyValue(true, for: rx)
    }

    func handleDidUpdateNotificationState(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        // swiftlint:disable:next line_length
        logger.info("[BLE] Did update notification state: \(peripheral.identifier.uuidString.prefix(8)), isNotifying: \(characteristic.isNotifying), charUUID: \(characteristic.uuid.uuidString.prefix(8)), error: \(error?.localizedDescription ?? "none")")

        guard case .subscribingToNotifications(let expected, let tx, let rx, let continuation) = phase,
              expected.identifier == peripheral.identifier,
              characteristic.uuid == rxCharacteristicUUID else {
            // Could be auto-reconnect scenario - handle separately
            handleReconnectionNotificationState(peripheral, characteristic: characteristic, error: error)
            return
        }

        if let error {
            centralManager.cancelPeripheralConnection(expected)
            transition(to: .idle)
            continuation.resume(throwing: Self.makeConnectionError(error))
            return
        }

        // C9: Verify notification subscription actually succeeded
        guard characteristic.isNotifying else {
            logger.warning("[BLE] Notification subscription completed without isNotifying=true")
            transition(to: .idle)
            continuation.resume(throwing: BLEError.connectionFailed("Notification subscription failed"))
            return
        }

        // Cancel the service discovery timeout since we completed successfully
        serviceDiscoveryTimeoutTask?.cancel()
        serviceDiscoveryTimeoutTask = nil

        // Transition to discoveryComplete before resuming the continuation.
        // This prevents double-resume if cancelCurrentOperation, disconnect(),
        // or a timeout handler runs before connect() transitions to .connected.
        transition(to: .discoveryComplete(peripheral: expected, tx: tx, rx: rx))
        continuation.resume()
    }

    private func handleReconnectionNotificationState(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        // Handle auto-reconnect notification subscription completion
        guard case .autoReconnecting(let expected, let tx, let rx) = phase,
              peripheral.identifier == expected.identifier else {
            return
        }

        // C9: Verify characteristic UUID matches RX and notification is active
        guard characteristic.uuid == rxCharacteristicUUID else {
            logger.debug("[BLE] Auto-reconnect: ignoring notification state for non-RX characteristic \(characteristic.uuid.uuidString.prefix(8))")
            return
        }

        if let error {
            logger.warning("Auto-reconnect notification subscription failed: \(error.localizedDescription)")
            transition(to: .idle)
            onDisconnection?(peripheral.identifier, error)
            return
        }

        guard characteristic.isNotifying else {
            logger.warning("[BLE] Auto-reconnect: notification subscription completed without isNotifying=true")
            transition(to: .idle)
            onDisconnection?(peripheral.identifier, nil)
            return
        }

        guard let tx, let rx else {
            logger.error("Auto-reconnect: tx/rx characteristics missing from phase")
            transition(to: .idle)
            onDisconnection?(peripheral.identifier, nil)
            return
        }

        // Cancel the auto-reconnect discovery timeout since we completed successfully
        autoReconnectDiscoveryTimeoutTask?.cancel()
        autoReconnectDiscoveryTimeoutTask = nil

        let elapsed = Date().timeIntervalSince(phaseStartTime)
        logger.info("[BLE] Auto-reconnect notification subscription complete, elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")

        // Create data stream and transition to connected
        let (stream, continuation) = AsyncStream.makeStream(
            of: Data.self,
            bufferingPolicy: .bufferingOldest(512)
        )

        // Pass continuation to delegate handler for direct yielding (preserves ordering)
        delegateHandler.setDataContinuation(continuation)

        transition(to: .connected(
            peripheral: peripheral,
            tx: tx,
            rx: rx,
            dataContinuation: continuation
        ))
        startRSSIKeepalive(for: peripheral)

        logger.info("[BLE] iOS auto-reconnect complete: \(peripheral.identifier.uuidString.prefix(8))")
        onReconnection?(peripheral.identifier, stream)
    }

    func handleDidWriteValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?, writeSequence: UInt64) {
        guard let continuation = pendingWriteContinuation else {
            logger.debug("[BLE] didWriteValue with no pending continuation, ignoring")
            return
        }

        // C8: Reject stale write callbacks from a previous (timed-out) write
        if writeSequence != pendingWriteSequence {
            logger.warning("[BLE] Stale didWriteValue: seq=\(writeSequence), expected=\(self.pendingWriteSequence), ignoring")
            return
        }

        // Cancel the timeout task since write completed
        writeTimeoutTask?.cancel()
        writeTimeoutTask = nil

        // Reset queue tracking on successful completion
        consecutiveQueuedWrites = 0

        pendingWriteContinuation = nil

        if let error {
            logger.warning("[BLE] Write error: seq=\(writeSequence), error=\(error.localizedDescription)")
            continuation.resume(throwing: BLEError.writeError(error.localizedDescription))
        } else {
            logger.debug("[BLE] Write complete: seq=\(writeSequence)")
            continuation.resume()
        }

        earliestNextWrite = ContinuousClock.now.advanced(by: .seconds(writePacingDelay))
        resumeNextWriteWaiter()
    }

    func handleDidReadRSSI(RSSI: NSNumber, error: Error?) {
        if let error {
            consecutiveRSSIFailures += 1
            if consecutiveRSSIFailures == 3 || consecutiveRSSIFailures % 10 == 0 {
                logger.warning(
                    "[BLE] RSSI read failed (\(self.consecutiveRSSIFailures) consecutive): \(error.localizedDescription)"
                )
            }
        } else {
            if consecutiveRSSIFailures > 0 {
                logger.info("[BLE] RSSI read recovered after \(self.consecutiveRSSIFailures) failures, RSSI: \(RSSI)")
            }
            consecutiveRSSIFailures = 0
        }
    }
}

// MARK: - State Transitions

extension BLEStateMachine {

    /// Transitions to a new phase, cleaning up the old phase's resources.
    ///
    /// - Parameter newPhase: The phase to transition to
    /// - Returns: The previous phase (for logging/debugging)
    @discardableResult
    private func transition(to newPhase: BLEPhase) -> BLEPhase {
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
    private func startRSSIKeepalive(for peripheral: CBPeripheral) {
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
        case .connecting(_, _, let timeoutTask):
            timeoutTask.cancel()

        case .connected(_, _, _, let dataContinuation):
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
    private func cancelPendingWriteOperations(error: Error = BLEError.notConnected) {
        writeTimeoutTask?.cancel()
        writeTimeoutTask = nil
        consecutiveQueuedWrites = 0
        earliestNextWrite = .now

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
        logger.warning("[BLE] cancelCurrentOperation: phase=\(self.phase.name), error=\(error.localizedDescription)")
        cancelPendingWriteOperations(error: error)

        switch phase {
        case .waitingForBluetooth(let continuation):
            continuation.resume(throwing: error)

        case .connecting(_, let continuation, let timeoutTask):
            timeoutTask.cancel()
            continuation.resume(throwing: error)

        case .discoveringServices(_, let continuation):
            continuation.resume(throwing: error)

        case .discoveringCharacteristics(_, _, let continuation):
            continuation.resume(throwing: error)

        case .subscribingToNotifications(_, _, _, let continuation):
            continuation.resume(throwing: error)

        case .connected(_, _, _, let dataContinuation):
            dataContinuation.finish()

        case .discoveryComplete:
            // Continuation already consumed — nothing to resume
            break

        case .idle, .autoReconnecting, .restoringState, .disconnecting:
            break
        }

        transition(to: .idle)
    }

    /// Cancels connection to a peripheral if we're not expecting it.
    private func cancelUnexpectedPeripheral(_ peripheral: CBPeripheral) {
        logger.warning("Cancelling unexpected peripheral: \(peripheral.identifier)")
        centralManager.cancelPeripheralConnection(peripheral)
    }

    private func handleServiceDiscoveryTimeout(for peripheral: CBPeripheral) {
        // Guard against stale timeout: if the normal path already cleared the
        // task reference, this timeout fired after cancellation took effect.
        guard serviceDiscoveryTimeoutTask != nil else { return }

        switch phase {
        case .discoveringServices(let p, let c),
             .discoveringCharacteristics(let p, _, let c),
             .subscribingToNotifications(let p, _, _, let c):
            guard p.identifier == peripheral.identifier else { return }
            let pState = peripheralStateString(peripheral.state)
            let elapsed = Date().timeIntervalSince(phaseStartTime)
            logger.warning("[BLE] Service discovery timeout: \(peripheral.identifier.uuidString.prefix(8)), phase: \(self.phase.name), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s")
            centralManager.cancelPeripheralConnection(peripheral)
            transition(to: .idle)
            c.resume(throwing: BLEError.connectionTimeout)
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

        guard case .autoReconnecting(let expected, _, _) = phase,
              expected.identifier == peripheral.identifier else {
            return
        }

        let pState = peripheralStateString(peripheral.state)
        let elapsed = Date().timeIntervalSince(phaseStartTime)
        logger.warning(
            "[BLE] Auto-reconnect discovery timeout: \(peripheral.identifier.uuidString.prefix(8)), peripheralState: \(pState), elapsed: \(elapsed.formatted(.number.precision(.fractionLength(2))))s"
        )

        centralManager.cancelPeripheralConnection(peripheral)
        transition(to: .idle)
        onDisconnection?(peripheral.identifier, BLEError.connectionTimeout)
    }
}
