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

    /// Contact service for path management (optional - retry with reset requires this)
    private var contactService: ContactService?

    /// In-flight messages awaiting ACK, keyed by messageID.
    ///
    /// One entry per message. Each entry carries the full set of expected-ACK
    /// CRCs accumulated across retry attempts, so a late ACK from any attempt
    /// can still mark the message delivered.
    var pendingAcks: [UUID: PendingAck] = [:]

    /// ACK confirmation callback (messageID).
    ///
    /// The handler receives the resolved messageID rather than the raw ackCode,
    /// so consumers can gate on conversation membership without re-walking
    /// `pendingAcks`. Round-trip time is persisted to the data store via
    /// `updateMessageAck` and read back through the DTO; passing it through
    /// the callback would duplicate that path.
    ///
    /// The handler is `async` so `MessageEventDispatcher` can hop to the
    /// main actor via `await MainActor.run { ... }`, matching the other
    /// service callbacks and giving FIFO order across multi-ACK bursts.
    ///
    /// Status and round-trip time are passed alongside the messageID so the
    /// UI dispatcher can fold both into the in-place bubble update without
    /// re-reading the DTO.
    var ackConfirmationHandler: (@Sendable (UUID, MessageStatus, UInt32?) async -> Void)?

    /// Message failure callback (messageID)
    var messageFailedHandler: (@Sendable (UUID) async -> Void)?

    /// Send-success callback (messageID, status, roundTripTime). Fired after
    /// `updateMessageStatus(.sent)` succeeds in the DM send and original
    /// channel send paths. Used by the UI event stream to update the rendered
    /// bubble in place. Channel resends use the separate `messageResentHandler`
    /// because they mutate `heardRepeats` and `sendCount` and need a full
    /// DTO refresh.
    ///
    /// Distinct from `ackConfirmationHandler`, which fires only on end-to-end
    /// ACK (DM `.delivered`). Channel broadcasts on LoRa have no recipient,
    /// so they never raise an ACK — conflating the two handlers would mean
    /// "the radio queued the broadcast" and "the peer confirmed receipt" both
    /// arrive on the same wire.
    var messageSentHandler: (@Sendable (UUID, MessageStatus, UInt32?) async -> Void)?

    /// Channel-resend completion callback (messageID). Fires after
    /// `resendChannelMessage` writes `.sent` to the DB. Carries no status
    /// payload because the resend path also mutates `heardRepeats` and
    /// `sendCount`; consumers must refresh the entire DTO rather than
    /// applying a status-only in-place update.
    var messageResentHandler: (@Sendable (UUID) async -> Void)?

    /// Event broadcaster for retry status updates (messageID, attempt, maxAttempts)
    var retryStatusHandler: (@Sendable (UUID, Int, Int) async -> Void)?

    /// Handler for routing change events (contactID, isFlood)
    var routingChangedHandler: (@Sendable (UUID, Bool) async -> Void)?

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
    ///   - config: Configuration for retry and routing behavior (defaults to `.default`)
    public init(
        session: MeshCoreSession,
        dataStore: PersistenceStore,
        config: MessageServiceConfig = .default
    ) {
        self.session = session
        self.dataStore = dataStore
        self.config = config
    }

    /// Sets the contact service for path management during retry.
    ///
    /// The contact service is used to reset contact paths when switching to flood routing.
    ///
    /// - Parameter service: The contact service to use
    public func setContactService(_ service: ContactService) {
        self.contactService = service
    }

    /// Whether a contact service has been wired via `setContactService`.
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
    /// the checker) or `stopAndFailAllPending()` (stop the checker and fail
    /// every in-flight DM, used during disconnect teardown).
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
            logger.warning("[ack-diag] unmatched ACK code=\(code.hexString()) livePending=\(pendingAcks.count) (late post-teardown or duplicate)")
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

        await ackConfirmationHandler?(messageID, .delivered, tripTime)

        // Diagnostic: how long the ACK took relative to the last send attempt
        // (sentAt is re-stamped per retry), the firmware-reported round trip,
        // and how many other entries were in flight. Distinguishes late-but-
        // arriving ACKs from never-arriving ones and measures table pressure.
        let ackDelta = Date().timeIntervalSince(tracking.sentAt)
        logger.info("[ack-diag] ACK matched deltaSinceLastSend=\(String(format: "%.2f", ackDelta))s firmwareTrip=\(tripTime.map { "\($0)ms" } ?? "nil") livePending=\(pendingAcks.count)")
    }

    /// Sets a callback to be invoked when an ACK is received.
    ///
    /// - Parameter handler: Callback receiving the resolved messageID. The
    ///   handler is awaited inside `handleAcknowledgement`, so consumers
    ///   that hop to another actor preserve submission order across
    ///   multi-ACK bursts (each `await MainActor.run { ... }` is a
    ///   continuation-resume onto the main queue and arrives in
    ///   submission order).
    public func setAckConfirmationHandler(_ handler: @escaping @Sendable (UUID, MessageStatus, UInt32?) async -> Void) {
        ackConfirmationHandler = handler
    }

    /// Sets a callback to be invoked when a message fails after all retries.
    ///
    /// - Parameter handler: Callback receiving the failed message ID
    public func setMessageFailedHandler(_ handler: @escaping @Sendable (UUID) async -> Void) {
        messageFailedHandler = handler
    }

    /// Fire `messageFailedHandler` from outside the actor. Sole purpose: the
    /// queue-side terminal catch in `ChatSendQueueService` runs off-actor and
    /// needs to invoke the handler. Do not grow other callers — the inline
    /// send paths fire the handler directly because they own the catch.
    public func notifyMessageFailed(messageID: UUID) async {
        await messageFailedHandler?(messageID)
    }

    /// Sets a callback to be invoked after a message reaches `.sent` status.
    ///
    /// Fires for both DM and original channel sends once the persistence write
    /// succeeds. Consumers use it to refresh the rendered bubble for channel
    /// broadcasts, which have no end-to-end ACK and therefore never fire
    /// `ackConfirmationHandler`. Channel resends use `messageResentHandler`
    /// instead because they additionally mutate `heardRepeats` and `sendCount`.
    ///
    /// - Parameter handler: Callback receiving messageID, status, and (for ACKs) roundTripTime
    public func setMessageSentHandler(_ handler: @escaping @Sendable (UUID, MessageStatus, UInt32?) async -> Void) {
        messageSentHandler = handler
    }

    /// Sets a callback to be invoked after a channel-message resend completes.
    ///
    /// - Parameter handler: Callback receiving the resent message ID
    public func setMessageResentHandler(_ handler: @escaping @Sendable (UUID) async -> Void) {
        messageResentHandler = handler
    }

    /// Sets a callback to be invoked during retry attempts.
    ///
    /// Use this to update UI with retry progress.
    ///
    /// - Parameter handler: Callback receiving (messageID, currentAttempt, maxAttempts)
    public func setRetryStatusHandler(_ handler: @escaping @Sendable (UUID, Int, Int) async -> Void) {
        retryStatusHandler = handler
    }

    /// Sets a callback to be invoked when routing mode changes during retry.
    ///
    /// - Parameter handler: Callback receiving (contactID, isFloodRouting)
    public func setRoutingChangedHandler(_ handler: @escaping @Sendable (UUID, Bool) async -> Void) {
        routingChangedHandler = handler
    }
}
