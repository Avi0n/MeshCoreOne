import Foundation
import MeshCore
import os

// MARK: - Message Service Errors

/// Errors that can occur during message operations.
public enum MessageServiceError: Error, Sendable {
    /// Not connected to a device
    case notConnected
    /// Contact not found in database
    case contactNotFound
    /// Channel not found in database
    case channelNotFound
    /// Message send operation failed
    case sendFailed(String)
    /// Attempted to send message to invalid recipient (e.g., repeater)
    case invalidRecipient
    /// Message text exceeds maximum allowed length
    case messageTooLong
    /// Underlying MeshCore session error
    case sessionError(MeshCoreError)
}

extension MessageServiceError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .notConnected: "Not connected to device."
        case .contactNotFound: "Contact not found."
        case .channelNotFound: "Channel not found."
        case .sendFailed(let msg): "Send failed: \(msg)"
        case .invalidRecipient: "Cannot send messages to this recipient."
        case .messageTooLong: "Message exceeds the maximum allowed length."
        case .sessionError(let e): e.localizedDescription
        }
    }
}

// MARK: - Message Service Configuration

/// Configuration for message retry and routing behavior.
///
/// Controls how the message service handles delivery failures and routing fallback.
public struct MessageServiceConfig: Sendable {
    /// Whether to use flood routing when user manually retries a failed message
    public let floodFallbackOnRetry: Bool

    /// Maximum total send attempts for automatic retry
    public let maxAttempts: Int

    /// Maximum attempts to make after switching to flood routing
    public let maxFloodAttempts: Int

    /// Number of direct attempts before switching to flood routing
    public let floodAfter: Int

    /// Minimum timeout in seconds (floor for device-suggested timeout)
    public let minTimeout: TimeInterval

    /// Whether to trigger path discovery after successful flood delivery
    public let triggerPathDiscoveryAfterFlood: Bool

    public init(
        floodFallbackOnRetry: Bool = true,
        maxAttempts: Int = 4,
        maxFloodAttempts: Int = 2,
        floodAfter: Int = 2,
        minTimeout: TimeInterval = 0,
        triggerPathDiscoveryAfterFlood: Bool = true
    ) {
        self.floodFallbackOnRetry = floodFallbackOnRetry
        self.maxAttempts = maxAttempts
        self.maxFloodAttempts = maxFloodAttempts
        self.floodAfter = floodAfter
        self.minTimeout = minTimeout
        self.triggerPathDiscoveryAfterFlood = triggerPathDiscoveryAfterFlood
    }

    public static let `default` = MessageServiceConfig()
}

// MARK: - Pending ACK Tracker

/// Tracks pending ACKs for a single outgoing direct message across retry attempts.
///
/// The firmware hashes the retry attempt index into the expected-ACK CRC, so a
/// single logical message can produce multiple `ackCodes` (one per attempt).
/// All attempts for the same message share one `PendingAck` entry.
///
/// DM-only: channel/room broadcasts do not generate ACKs and are not tracked here.
public struct PendingAck: Sendable {
    public let messageID: UUID
    public let contactID: UUID
    public var ackCodes: Set<Data>
    public var sentAt: Date
    public var timeout: TimeInterval
    public var isDelivered: Bool = false

    public init(
        messageID: UUID,
        contactID: UUID,
        ackCodes: Set<Data>,
        sentAt: Date,
        timeout: TimeInterval,
        isDelivered: Bool = false
    ) {
        self.messageID = messageID
        self.contactID = contactID
        self.ackCodes = ackCodes
        self.sentAt = sentAt
        self.timeout = timeout
        self.isDelivered = isDelivered
    }

    public var isExpired: Bool {
        !isDelivered && Date().timeIntervalSince(sentAt) > timeout
    }
}

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

    /// Upper bound on in-memory retention of recently-failed ACK codes for the
    /// late-ACK grace window.
    let recentlyFailedAcksMaxSize = 64

    /// Ackcode → (messageID that owned it, moment we wrote `.failed`).
    /// Populated by `checkExpiredAcks` when it flips a row to `.failed`, consumed
    /// by `handleAcknowledgement` when no in-memory `pendingAcks` entry matches.
    var recentlyFailedAcks: [Data: (messageID: UUID, failedAt: Date)] = [:]

    /// ACK confirmation callback (ackCode, roundTripTime).
    ///
    /// `roundTripTime` is `nil` when firmware did not supply a `round_trip`
    /// value on the `PUSH_CODE_SEND_CONFIRMED` push (older firmware, truncated
    /// payloads). Callers must handle the nil case rather than substitute a
    /// fabricated value.
    private var ackConfirmationHandler: (@Sendable (UInt32, UInt32?) -> Void)?

    /// Message failure callback (messageID)
    var messageFailedHandler: (@Sendable (UUID) async -> Void)?

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

    /// Starts listening for session events to process message acknowledgements.
    ///
    /// Call this method after connection is established to begin processing ACKs.
    /// The service will automatically update message delivery status when ACKs are received.
    ///
    /// # Important
    /// This must be called for ACK tracking to work. Without event monitoring,
    /// messages will remain in "sent" status even if ACKs are received.
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

    /// Stops monitoring session events.
    ///
    /// Call this when disconnecting from the device.
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
            await reconcileLateAck(code: code, tripTime: tripTime)
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

        ackConfirmationHandler?(ackCodeUInt32, tripTime)

        logger.info("ACK received")
    }

    /// Sets a callback to be invoked when an ACK is received.
    ///
    /// - Parameter handler: Callback receiving (ackCode, roundTripTimeMs)
    public func setAckConfirmationHandler(_ handler: @escaping @Sendable (UInt32, UInt32?) -> Void) {
        ackConfirmationHandler = handler
    }

    /// Sets a callback to be invoked when a message fails after all retries.
    ///
    /// - Parameter handler: Callback receiving the failed message ID
    public func setMessageFailedHandler(_ handler: @escaping @Sendable (UUID) async -> Void) {
        messageFailedHandler = handler
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
