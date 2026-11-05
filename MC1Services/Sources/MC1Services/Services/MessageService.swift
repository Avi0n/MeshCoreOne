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

  let session: any MeshCoreSessionProtocol
  let dataStore: any PersistenceStoreProtocol
  let config: MessageServiceConfig
  /// Read once at the start of each DM retry loop.
  let dmSendBudgetProvider: @Sendable () -> DMSendBudget
  let plainTextSendGate: PlainTextSendGate?

  /// Contact service for path management (optional - retry with reset requires this).
  /// Injected by `ServiceContainer` at construction.
  let contactService: ContactService?

  /// In-flight messages awaiting ACK, keyed by messageID.
  ///
  /// One entry per message. Each entry carries the full set of expected-ACK
  /// CRCs accumulated across retry attempts, so a late ACK from any attempt
  /// can still mark the message delivered.
  var pendingAcks: [UUID: PendingAck] = [:]

  /// Owner saves inside `persistAcknowledgement`, keyed by message.
  var acknowledgementSavesInFlight: [UUID: Int] = [:]
  /// Saves whose store commit has not returned. A nil-owner status write waits for that commit.
  var uncommittedAcknowledgementSaves: Set<UUID> = []
  var inlineSendsInFlight = 0
  var inlineSendAdmissionClosed = false
  var inlineSendWaiters: [CheckedContinuation<Void, Never>] = []

  var acknowledgementSaveWaiters: [CheckedContinuation<Void, Never>] = []

  #if DEBUG
    /// Runs before each expired entry's status write. A renewal of a later
    /// candidate can land while the checker is still on an earlier one.
    var ackExpiryWriteGateForTesting: (@Sendable (UUID) async -> Void)?
    var inlineSendAcceptedGateForTesting: (@Sendable () async -> Void)?
    /// Runs after an owner save is entered and before its store commit.
    var acknowledgementSaveGateForTesting: (@Sendable () async -> Void)?
    /// Runs after the owner row commits and before `pendingSendID` is attached.
    var acknowledgementInstallGateForTesting: (@Sendable () async -> Void)?

    func setAckExpiryWriteGateForTesting(_ gate: (@Sendable (UUID) async -> Void)?) {
      ackExpiryWriteGateForTesting = gate
    }

    func setInlineSendAcceptedGateForTesting(_ gate: (@Sendable () async -> Void)?) {
      inlineSendAcceptedGateForTesting = gate
    }

    func setAcknowledgementSaveGateForTesting(_ gate: (@Sendable () async -> Void)?) {
      acknowledgementSaveGateForTesting = gate
    }

    func setAcknowledgementInstallGateForTesting(_ gate: (@Sendable () async -> Void)?) {
      acknowledgementInstallGateForTesting = gate
    }

    var acknowledgementSaveWaiterCountForTesting: Int {
      acknowledgementSaveWaiters.count
    }

    var inlineSendWaiterCountForTesting: Int {
      inlineSendWaiters.count
    }
  #endif

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
  private var bufferedAckEvents: AsyncStream<MeshEvent>?

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
  init(
    session: any MeshCoreSessionProtocol,
    dataStore: any PersistenceStoreProtocol,
    contactService: ContactService?,
    config: MessageServiceConfig = .default,
    ackEvents: AsyncStream<MeshEvent>? = nil,
    dmSendBudget: @escaping @Sendable () -> DMSendBudget = { .default },
    plainTextSendGate: PlainTextSendGate? = nil
  ) {
    self.session = session
    self.dataStore = dataStore
    self.contactService = contactService
    self.config = config
    dmSendBudgetProvider = dmSendBudget
    self.plainTextSendGate = plainTextSendGate
    bufferedAckEvents = ackEvents
  }

  /// Whether a contact service was injected at construction.
  var hasContactServiceWired: Bool {
    contactService != nil
  }

  // MARK: - Event Listening

  func captureAcknowledgementEvents() async {
    if bufferedAckEvents == nil {
      bufferedAckEvents = await session.events(filter: .anyAcknowledgement)
    }
  }

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
  public func startEventMonitoring() async {
    await stopEventMonitoring()
    await captureAcknowledgementEvents()
    guard let events = bufferedAckEvents else { return }
    bufferedAckEvents = nil
    eventListenerTask = Task { [weak self] in
      guard let self else { return }

      for await event in events {
        guard !Task.isCancelled else { break }

        if case let .acknowledgement(code, tripTime) = event {
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
  public func stopEventMonitoring() async {
    let eventListenerTaskToStop = eventListenerTask
    eventListenerTask = nil
    eventListenerTaskToStop?.cancel()
    await eventListenerTaskToStop?.value
  }

  // MARK: - ACK Handling

  /// Processes an acknowledgement from the session event stream.
  ///
  /// Finds the in-flight message whose accumulated `ackCodes` contains this
  /// CRC and commits that match. The entry stays until the terminal save
  /// succeeds, so a thrown write can be retried with the same code.
  func handleAcknowledgement(code: Data, tripTime: UInt32?) async {
    guard let (messageID, tracking) = pendingAcks.first(where: {
      $0.value.ackCodes.contains(code) && !$0.value.isDelivered
    }) else {
      // Diagnostic: the firmware delivered an end-to-end ACK we have no
      // live entry for. This is either a genuinely late ACK that arrived
      // after the message was already failed and removed, or a duplicate
      // for an already-delivered message. `orphanSentRow` flags a `.sent`
      // DM that lost its pending entry to a teardown and would otherwise
      // show "Sent" forever; its field rate informs whether a
      // reconnect-time orphan sweep is worth adding.
      let orphanSentRow = await (try? dataStore.hasOutgoingSentDM(ackCode: code.ackCodeUInt32)) ?? false
      logger.warning("[ack-diag] unmatched ACK code=\(code.uppercaseHexString()) livePending=\(pendingAcks.count) orphanSentRow=\(orphanSentRow) (late post-teardown or duplicate)")
      return
    }

    pendingAcks[messageID]?.matchedAckCode = code.ackCodeUInt32
    pendingAcks[messageID]?.matchedTripTime = tripTime
    do {
      _ = try await commitPendingAcknowledgement(messageID: messageID, sentAt: tracking.sentAt)
    } catch {
      logger.error("Failed to write delivered status: \(error.localizedDescription)")
    }
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
