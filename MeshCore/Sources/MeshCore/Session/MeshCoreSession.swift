import Foundation
import os

/// Main session actor for MeshCore device communication.
///
/// `MeshCoreSession` coordinates all communication with a MeshCore mesh networking device
/// over a transport layer (typically Bluetooth LE). It provides high-level APIs for:
///
/// - **Connection management**: Start and stop device sessions
/// - **Contact discovery**: Query and manage the device's contact list
/// - **Messaging**: Send and receive messages to/from mesh contacts
/// - **Device configuration**: Set device name, coordinates, radio parameters
/// - **Telemetry**: Request sensor data and device statistics
///
/// ## Usage
///
/// ```swift
/// // Create a session with a transport
/// let transport = WiFiTransport()
/// await transport.setConnectionInfo(host: "192.168.1.100", port: 5000)
/// let session = MeshCoreSession(transport: transport)
///
/// // Connect and start the session
/// try await session.start()
///
/// // Get contacts from the device
/// let contacts = try await session.getContacts()
///
/// // Send a message to a contact
/// if let contact = contacts.first {
///     let result = try await session.sendMessage(
///         to: contact.publicKey,
///         text: "Hello from Swift!"
///     )
/// }
///
/// // Stop when done
/// await session.stop()
/// ```
///
/// ## Event Streaming
///
/// Subscribe to device events using the async event stream:
///
/// ```swift
/// Task {
///     for await event in await session.events() {
///         switch event {
///         case .contactMessageReceived(let msg):
///             print("Message: \(msg.text)")
///         case .advertisement(let publicKey):
///             print("Saw advertisement from \(publicKey.hexString)")
///         default:
///             break
///         }
///     }
/// }
/// ```
///
/// ## Thread Safety
///
/// `MeshCoreSession` is an actor, ensuring all operations are serialized and thread-safe.
/// All public methods are async and can be called safely from any context.
///
/// ## Testing
///
/// Inject a custom `Clock` to control timing in tests:
///
/// ```swift
/// let testClock = TestClock()
/// let session = MeshCoreSession(
///     transport: MockTransport(),
///     clock: testClock
/// )
/// ```
public actor MeshCoreSession: MeshCoreSessionProtocol {

    // MARK: - Properties

    let logger = Logger(subsystem: "MeshCore", category: "Session")

    let transport: any MeshTransport
    let configuration: SessionConfiguration
    let clock: any Clock<Duration>
    let dispatcher = EventDispatcher()
    let requestResponseSerializer = RequestResponseSerializer()

    // Serializes the read-modify-write of the granular "other params" setters. The
    // inner wire exchanges already serialize on requestResponseSerializer, but a
    // read-modify-write spans several of them, so without this outer guard two
    // concurrent setters each write back the full config and silently revert each
    // other's change. This is a distinct lock from requestResponseSerializer, which
    // is not reentrant; nesting the two would deadlock.
    let otherParamsSerializer = RequestResponseSerializer()

    // State
    var contactManager = ContactManager()
    var selfInfo: SelfInfo?
    private var cachedTime: Date?

    /// Returns the device's self info after session start.
    ///
    /// This is populated after `start()` completes successfully.
    public var currentSelfInfo: SelfInfo? { selfInfo }

    /// Returns the last known device time.
    ///
    /// This is updated when the device reports its current time. Returns `nil` if
    /// the time has not been queried. Use ``getTime()`` to explicitly request it.
    public var deviceTime: Date? { cachedTime }
    private var isRunning = false
    private var receiveTask: Task<Void, Never>?
    private var autoMessageFetchTask: Task<Void, Never>?
    private var autoMessageDrainTask: Task<Void, Never>?
    private var autoContactRefreshTask: Task<Void, Never>?
    private var isAutoFetchingMessages = false
    private var autoMessageDrainRequested = false
    private var autoContactRefreshRequested = false
    var inFlightGetMessage: Task<MessageResult, Error>?

    // MARK: - Connection State

    private var _connectionState: ConnectionState = .disconnected
    private var connectionStateContinuations: [UUID: AsyncStream<ConnectionState>.Continuation] = [:]

    /// Provides an observable connection state stream for UI binding.
    ///
    /// The stream yields the current state immediately upon subscription,
    /// and then yields subsequent state changes as they occur.
    public var connectionState: AsyncStream<ConnectionState> {
        AsyncStream { continuation in
            let id = UUID()
            // Yield current state immediately. The build closure runs on the actor,
            // so registering synchronously here means no transition can slip in
            // between the first yield and registration.
            continuation.yield(_connectionState)
            connectionStateContinuations[id] = continuation
            continuation.onTermination = { _ in
                Task { await self.removeConnectionStateContinuation(id: id) }
            }
        }
    }

    private func removeConnectionStateContinuation(id: UUID) {
        connectionStateContinuations.removeValue(forKey: id)
    }

    private func updateConnectionState(_ state: ConnectionState) {
        logger.info("Session connection state -> \(String(describing: state))")
        _connectionState = state
        for continuation in connectionStateContinuations.values {
            continuation.yield(state)
        }
    }

    // MARK: - Lifecycle

    /// Creates a new MeshCore session.
    ///
    /// The session is created in a disconnected state. Call ``start()`` to connect
    /// to the device and begin communication.
    ///
    /// - Parameters:
    ///   - transport: The transport layer for device communication (e.g., ``WiFiTransport``).
    ///   - configuration: Session configuration options. Defaults to ``SessionConfiguration/default``.
    ///   - clock: The clock for timing operations. Defaults to `ContinuousClock` for production use.
    ///            Inject a test clock for deterministic testing of timeouts.
    public init(
        transport: any MeshTransport,
        configuration: SessionConfiguration = .default,
        clock: (any Clock<Duration>)? = nil
    ) {
        self.transport = transport
        self.configuration = configuration
        self.clock = clock ?? ContinuousClock()
    }

    /// Connects to the device and starts the session.
    ///
    /// This method performs the following steps:
    /// 1. Connects via the transport layer
    /// 2. Sends the `appStart` command to initialize communication
    /// 3. Receives device self-info (public key, name, capabilities)
    /// 4. Starts the background receive loop for incoming data
    ///
    /// The session becomes ready for use after this method returns successfully.
    /// Subscribe to events via ``events()`` to receive incoming messages and notifications.
    ///
    /// - Parameter reconnectingAttempt: When non-nil, publishes `.reconnecting(attempt:)`
    ///   instead of `.connecting` before establishing the transport/session.
    /// - Throws: ``MeshTransportError`` if the transport connection fails.
    ///           ``MeshCoreError/timeout`` if the device doesn't respond to appStart.
    public func start(reconnectingAttempt: Int? = nil) async throws {
        // Guard against being called multiple times
        if isRunning {
            logger.warning("Session already running - skipping redundant start()")
            return
        }

        logger.info("Starting MeshCore session...")
        if let reconnectingAttempt {
            updateConnectionState(.reconnecting(attempt: max(1, reconnectingAttempt)))
        } else {
            updateConnectionState(.connecting)
        }
        logger.info("Connecting via transport...")
        do {
            try await transport.connect()
        } catch {
            logger.warning("Transport connection failed: \(error.localizedDescription)")
            updateConnectionState(.failed(error as? MeshTransportError ?? .connectionFailed(error.localizedDescription)))
            throw error
        }
        isRunning = true
        updateConnectionState(.connected)

        // Start receiving data with weak self to prevent retain cycle
        receiveTask = Task { [weak self] in
            guard let self else { return }
            await self.receiveLoop()
        }

        // Send appstart
        logger.info("Sending appStart command...")
        do {
            selfInfo = try await sendAppStart()
        } catch {
            // Unwind the half-started session so a retry start() runs the full
            // handshake instead of hitting the isRunning guard and no-opping.
            logger.warning("appStart failed: \(error.localizedDescription)")
            isRunning = false
            receiveTask?.cancel()
            receiveTask = nil
            await transport.disconnect()
            updateConnectionState(.failed(error as? MeshTransportError ?? .connectionFailed(error.localizedDescription)))
            throw error
        }
        logger.info("MeshCore session started")
    }

    /// Stops the session and disconnects from the device.
    ///
    /// This method is safe to call multiple times. It cancels all pending operations,
    /// stops the receive loop, and closes the transport connection.
    ///
    /// After calling this method, the session cannot be reused. Create a new session
    /// to reconnect.
    public func stop() async {
        logger.info("Stopping MeshCore session...")
        isRunning = false
        stopAutoMessageFetching()
        autoContactRefreshTask?.cancel()
        autoContactRefreshTask = nil
        autoContactRefreshRequested = false
        receiveTask?.cancel()
        await dispatcher.finishAllSubscriptions()
        logger.info("Disconnecting transport...")
        await transport.disconnect()
        updateConnectionState(.disconnected)
        logger.info("MeshCore session stopped")
    }

    // MARK: - Events

    /// Subscribes to all events from the device.
    ///
    /// Each subscriber receives all events independently. Supports bounded buffering of up to 100 events.
    ///
    /// - Returns: An async stream of mesh events that yields ``MeshEvent`` values as they are received.
    public func events() async -> AsyncStream<MeshEvent> {
        await dispatcher.subscribe()
    }

    /// Subscribes to events passing the given filter.
    ///
    /// Prefer this over ``events()`` when the consumer only cares about a
    /// narrow slice of events. The filter is evaluated at dispatch time,
    /// so non-matching events never enter the subscription's 100-slot
    /// bounded buffer (`.bufferingNewest`) — unrelated traffic cannot
    /// evict matching events even if the consumer is slow to drain the stream.
    ///
    /// - Parameter filter: The ``EventFilter`` that determines which events reach the stream.
    /// - Returns: An async stream yielding only events that pass `filter`.
    public func events(filter: EventFilter) async -> AsyncStream<MeshEvent> {
        await dispatcher.subscribe(filter: filter.matches)
    }

    /// Subscribes to all events with an explicit teardown handle.
    ///
    /// Use this when the listener has a bounded lifetime (e.g., a timed scan) and needs the
    /// `for await` loop to exit promptly when the work is done. Pair with ``finishEvents(id:)``.
    ///
    /// - Returns: A tuple of the subscription id (pass to ``finishEvents(id:)``) and the event stream.
    public func eventsTracked() async -> (id: UUID, stream: AsyncStream<MeshEvent>) {
        await dispatcher.subscribeTracked()
    }

    /// Finishes a subscription created via ``eventsTracked()``.
    ///
    /// Causes the corresponding `for await` loop to exit. Safe to call with an unknown id.
    public func finishEvents(id: UUID) async {
        await dispatcher.finishSubscription(id: id)
    }

    // MARK: - Auto Message Fetching

    /// Starts automatic message fetching.
    ///
    /// When enabled, the session automatically fetches pending messages from the
    /// device whenever it receives a `messagesWaiting` notification.
    ///
    /// Call ``stopAutoMessageFetching()`` to disable.
    public func startAutoMessageFetching() async {
        guard !isAutoFetchingMessages else { return }
        isAutoFetchingMessages = true

        // Subscribe before spawning the loop so a messagesWaiting event
        // dispatched right after this method returns cannot be missed.
        let events = await dispatcher.subscribe()
        autoMessageFetchTask = Task { [weak self] in
            guard let self else { return }
            await self.autoMessageFetchLoop(events: events)
        }

        // The auto-fetch loop polls messages in response to messagesWaiting events.
        // For immediate polling, callers should use getMessage() or consume the events directly.
    }

    /// Stops automatic message fetching.
    ///
    /// Call this to disable the automatic fetching started by ``startAutoMessageFetching()``.
    public func stopAutoMessageFetching() {
        isAutoFetchingMessages = false
        autoMessageDrainRequested = false
        autoMessageFetchTask?.cancel()
        autoMessageFetchTask = nil
        autoMessageDrainTask?.cancel()
        autoMessageDrainTask = nil
    }

    private func autoMessageFetchLoop(events: AsyncStream<MeshEvent>) async {
        for await event in events {
            guard isAutoFetchingMessages else { break }

            if case .messagesWaiting = event {
                requestAutoMessageDrain()
            }
        }
    }

    private func requestAutoMessageDrain() {
        autoMessageDrainRequested = true

        guard autoMessageDrainTask == nil else { return }
        autoMessageDrainTask = Task { [weak self] in
            guard let self else { return }
            await self.runAutoMessageDrainLoop()
        }
    }

    private func runAutoMessageDrainLoop() async {
        defer { autoMessageDrainTask = nil }

        while isAutoFetchingMessages, autoMessageDrainRequested, !Task.isCancelled {
            autoMessageDrainRequested = false

            do {
                while isAutoFetchingMessages, !Task.isCancelled {
                    let result = try await getMessage()
                    if case .noMoreMessages = result { break }
                    try await Task.sleep(for: .milliseconds(100))
                }
            } catch {
                logger.debug("Auto message fetch error: \(error.localizedDescription)")
            }
        }
    }

    private func requestAutoContactRefresh() {
        autoContactRefreshRequested = true

        guard autoContactRefreshTask == nil else { return }
        autoContactRefreshTask = Task { [weak self] in
            guard let self else { return }
            await self.runAutoContactRefreshLoop()
        }
    }

    private func runAutoContactRefreshLoop() async {
        defer { autoContactRefreshTask = nil }

        while autoContactRefreshRequested, !Task.isCancelled {
            autoContactRefreshRequested = false

            do {
                _ = try await ensureContacts(force: true)
            } catch is CancellationError {
                break
            } catch {
                logger.warning("Auto contact refresh failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Receive Path

    /// The background loop for receiving data from the transport.
    private func receiveLoop() async {
        logger.info("Receive loop started")
        for await data in await transport.receivedData {
            await handleReceivedData(data)
        }
        // Stream ended - transport disconnected
        logger.info("Receive loop ended (stream exhausted)")
        // Publish the loss only when the stream ended unexpectedly. During stop() or a
        // failed-start unwind the session has already published its terminal state
        // (.disconnected or .failed); a late .disconnected here would overwrite it.
        guard isRunning else { return }
        await dispatcher.dispatch(.connectionStateChanged(.disconnected))
        updateConnectionState(.disconnected)
        isRunning = false
    }

    /// Handles raw data received from the device.
    ///
    /// Per the MeshCore Companion Radio Protocol, each BLE notification is a complete frame.
    /// No reassembly or buffering is needed - we parse each packet directly.
    private func handleReceivedData(_ data: Data) async {
        guard !data.isEmpty else { return }

        var event = PacketParser.parse(data)

        if case .parseFailure(_, let reason) = event {
            logger.warning("Failed to parse packet: \(data.hexString) - \(reason)")
        } else {
            logger.debug("Received event: \(String(describing: event))")
        }

        // Re-parse push status responses with correct layout for room servers
        if case .statusResponse(let response) = event,
           response.layout == .repeater,
           let contact = contactManager.getByKeyPrefix(response.publicKeyPrefix),
           contact.type == .room {
            event = Parsers.StatusResponse.parse(Data(data.dropFirst()), layout: .roomServer)
        }

        trackContactChanges(event: event)
        await dispatcher.dispatch(event)
    }

    /// Tracks contact-related changes from received events.
    private func trackContactChanges(event: MeshEvent) {
        // Track contact-related events in ContactManager
        contactManager.trackChanges(from: event)

        // Auto-refresh contacts if enabled and contacts became dirty
        if contactManager.isAutoUpdateEnabled && contactManager.needsRefresh {
            switch event {
            case .advertisement, .pathUpdate, .newContact:
                requestAutoContactRefresh()
            default:
                break
            }
        }

        // Track non-contact state
        switch event {
        case .currentTime(let time):
            cachedTime = time
        case .selfInfo(let info):
            selfInfo = info
        default:
            break
        }
    }

    // MARK: - Test Support

    /// Dispatches an event directly to subscribers, bypassing the transport and parser.
    ///
    /// For tests only — use to verify subscriber behavior without crafting wire bytes.
    func dispatchForTesting(_ event: MeshEvent) async {
        await dispatcher.dispatch(event)
    }

    /// Seeds `selfInfo` for tests so callers that depend on
    /// ``currentSelfInfo`` (e.g. ACK precompute) can run without simulating
    /// an `APP_START` round-trip.
    func installSelfInfoForTest(_ info: SelfInfo) {
        selfInfo = info
    }

    /// Returns the dispatcher's active subscription count. For tests only.
    ///
    /// Integration tests use this to synchronize with a listener task's
    /// subscribe call before dispatching events, avoiding the dispatch-vs-subscribe race.
    var subscriberCountForTest: Int {
        get async { await dispatcher.subscriberCountForTest }
    }
}
