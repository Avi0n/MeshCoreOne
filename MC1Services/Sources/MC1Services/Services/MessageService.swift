import Foundation
import MeshCore

// MARK: - Message Service Actor

/// Actor-isolated service for sending messages with retry logic and ACK tracking.
///
/// `MessageService` manages all message operations including:
/// - Sending direct messages to contacts with single-attempt or automatic retry
/// - Sending channel broadcast messages
/// - Tracking pending message acknowledgements (ACKs)
/// - Handling delivery confirmations and failures
/// - Automatic retry with flood routing fallback
///
/// # Example Usage
///
/// ```swift
/// // Send a message with automatic retry
/// let message = try await messageService.sendMessageWithRetry(
///     text: "Hello!",
///     to: contact
/// ) { messageDTO in
///     // Message saved, update UI immediately
///     await updateUI(with: messageDTO)
/// }
/// ```
///
/// # ACK Tracking
///
/// After sending a message, the service tracks pending ACKs and automatically:
/// - Marks messages as delivered when ACK is received
/// - Marks messages as failed when timeout expires
/// - Tracks repeat acknowledgements for network analysis
///
/// Call `startEventMonitoring()` to begin processing ACKs from the session.
public actor MessageService {

    // MARK: - Properties

    let logger = PersistentLogger(subsystem: "com.mc1", category: "MessageService")

    let session: MeshCoreSession
    let dataStore: PersistenceStore
    let config: MessageServiceConfig

    /// Contact service for path management (optional - retry with reset requires this).
    /// Injected by `ServiceContainer` at construction.
    private let contactService: ContactService?

    /// In-flight messages awaiting ACK, keyed by messageID.
    ///
    /// One entry per message. Each entry carries the full set of expected-ACK
    /// CRCs accumulated across retry attempts, so a late ACK from any attempt
    /// can still mark the message delivered.
    var pendingAcks: [UUID: PendingAck] = [:]

    /// Multicast broadcaster for outbound-message lifecycle events.
    ///
    /// Every yield happens synchronously at its lifecycle site on this actor,
    /// so yield order equals consumption order and multi-ACK bursts reach
    /// subscribers in the order the actor processed them.
    nonisolated let statusEventBroadcaster = EventBroadcaster<MessageStatusEvent>()

    /// Task for periodic ACK expiry checking
    var ackCheckTask: Task<Void, Never>?

    /// Task for listening to session events
    private var eventListenerTask: Task<Void, Never>?

    /// Interval between ACK expiry checks (in seconds)
    var checkInterval: TimeInterval = 5.0

    /// Tracks message IDs currently being retried to prevent concurrent retry attempts
    var inFlightRetries: Set<UUID> = []

    // MARK: - Initialization

    /// Creates a new message service.
    ///
    /// - Parameters:
    ///   - session: The MeshCore session for sending messages
    ///   - dataStore: The persistence store for saving messages
    ///   - contactService: Contact service for path management during retry
    ///   - config: Configuration for retry and routing behavior (defaults to `.default`)
    public init(
        session: MeshCoreSession,
        dataStore: PersistenceStore,
        contactService: ContactService?,
        config: MessageServiceConfig = .default
    ) {
        self.session = session
        self.dataStore = dataStore
        self.contactService = contactService
        self.config = config
    }

    /// Whether a contact service was injected at construction.
    var hasContactServiceWired: Bool { contactService != nil }

    // MARK: - Event Listening

    /// Starts the session ACK event listener.
    ///
    /// Subscribes to `.anyAcknowledgement` events on the session and routes
    /// each one through `handleAcknowledgement`. Call after the connection is
    /// established; without this the listener never runs and pending DMs stay
    /// `.sent` even when ACKs arrive.
    ///
    /// # Lifecycle scope
    ///
    /// This method's counterpart is `stopEventMonitoring()`. It does **not**
    /// start the periodic ACK expiry checker — that's `startAckExpiryChecking()`,
    /// which has its own `stopAckExpiryChecking()` / `stopAndFailAllPending()`
    /// counterparts. `ServiceContainer` pairs both lifecycles together; direct
    /// callers must do the same.
    public func startEventMonitoring() {
        eventListenerTask?.cancel()

        eventListenerTask = Task { [weak self] in
            guard let self else { return }

            for await event in await session.events(filter: .anyAcknowledgement) {
                guard !Task.isCancelled else { break }

                if case .acknowledgement(let code, let tripTime) = event {
                    await handleAcknowledgement(code: code, tripTime: tripTime)
                }
            }
        }
    }

    /// Stops the session ACK event listener.
    ///
    /// Cancels `eventListenerTask` only. Does **not** cancel the periodic ACK
    /// expiry checker — for that, call `stopAckExpiryChecking()` (just stop
    /// the checker, used on a routine disconnect) or `stopAndFailAllPending()`
    /// (stop the checker and fail every in-flight DM, for explicit full teardown).
    public func stopEventMonitoring() {
        eventListenerTask?.cancel()
        eventListenerTask = nil
    }

    // MARK: - ACK Handling

    /// Processes an acknowledgement from the session event stream.
    ///
    /// Finds the in-flight message whose accumulated `ackCodes` contains this
    /// CRC, marks it delivered, writes the delivery status + round-trip time
    /// to the database, and removes the entry. This is the sole DB writer for
    /// delivered status on the late-ACK path.
    func handleAcknowledgement(code: Data, tripTime: UInt32?) async {
        guard let (messageID, tracking) = pendingAcks.first(where: {
            $0.value.ackCodes.contains(code) && !$0.value.isDelivered
        }) else {
            // Diagnostic: the firmware delivered an end-to-end ACK we have no
            // live entry for. This is either a genuinely late ACK that arrived
            // after the message was already failed and removed, or a duplicate
            // for an already-delivered message. Counting these quantifies how
            // many delivery confirmations are being lost to premature give-up.
            logger.warning("[ack-diag] unmatched ACK code=\(code.uppercaseHexString()) livePending=\(pendingAcks.count) (late post-teardown or duplicate)")
            return
        }

        pendingAcks[messageID]?.isDelivered = true

        // Persist `tripTime` only when firmware supplied it. Date()-based
        // fallbacks against `tracking.sentAt` would be wrong in either
        // direction once retries reset the timestamp, so we prefer a nil
        // `roundTripTime` over a fabricated value.
        let ackCodeUInt32 = code.ackCodeUInt32

        do {
            try await dataStore.updateMessageAck(
                id: messageID,
                ackCode: ackCodeUInt32,
                status: .delivered,
                roundTripTime: tripTime
            )
        } catch {
            logger.error("Failed to write delivered status: \(error.localizedDescription)")
        }

        do {
            try await dataStore.updateContactLastMessage(contactID: tracking.contactID, date: Date())
        } catch {
            logger.error("Failed to update contact lastMessageDate: \(error.localizedDescription)")
        }

        pendingAcks.removeValue(forKey: messageID)

        statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .delivered, roundTripTime: tripTime))

        // Diagnostic: how long the ACK took relative to the last send attempt
        // (sentAt is re-stamped per retry), the firmware-reported round trip,
        // and how many other entries were in flight. Distinguishes late-but-
        // arriving ACKs from never-arriving ones and measures table pressure.
        let ackDelta = Date().timeIntervalSince(tracking.sentAt)
        logger.info("[ack-diag] ACK matched deltaSinceLastSend=\(String(format: "%.2f", ackDelta))s firmwareTrip=\(tripTime.map { "\($0)ms" } ?? "nil") livePending=\(pendingAcks.count)")
    }

    // MARK: - Status Events

    /// Returns a fresh stream of outbound-message lifecycle events.
    /// Registration is synchronous, so events yielded after this call are
    /// never dropped. Consumers must re-subscribe per connection because the
    /// owning `ServiceContainer` is rebuilt on every connection.
    public nonisolated func statusEvents() -> AsyncStream<MessageStatusEvent> {
        statusEventBroadcaster.subscribe()
    }

    /// Ends every `statusEvents()` subscriber's for-await loop. Called by
    /// `ServiceContainer.tearDown()` so consumer tasks release the service
    /// references they hold.
    nonisolated func finishStatusEvents() {
        statusEventBroadcaster.finish()
    }

    /// Broadcasts `.failed` for a message from outside the actor. Sole
    /// purpose: the queue-side terminal catch in `ChatSendQueueService` runs
    /// off-actor and needs to surface the failure. Do not grow other callers;
    /// the inline send paths yield the event directly because they own the
    /// catch.
    public func notifyMessageFailed(messageID: UUID) async {
        statusEventBroadcaster.yield(.failed(messageID: messageID))
    }
}
