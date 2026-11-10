import Foundation
import os

// MARK: - Chat Send Queue Service Errors

public enum ChatSendQueueServiceError: Error, Sendable, LocalizedError {
  case persistFailed(underlying: Error)
  case notConnected

  public var errorDescription: String? {
    switch self {
    case let .persistFailed(underlying):
      "Failed to queue message for sending: \(underlying.localizedDescription)"
    case .notConnected:
      "Not connected to device."
    }
  }
}

/// Retry timing for `ChatSendQueueService`.
struct ChatSendQueueConfig {
  /// Maximum time to wait for a transport-open trigger before
  /// silently re-attempting the send.
  let transportWaitTimeout: TimeInterval

  /// Number of channel-drain attempts before the queue spends a BLE
  /// round-trip to disambiguate transient NOT_FOUND (pool exhaustion)
  /// from terminal NOT_FOUND (channel deleted on the device).
  let disambiguateAfterAttempts: Int

  /// Backstop cap on consecutive `fetchChannel` throws. After this many
  /// the channel drain treats NOT_FOUND as terminal so the user sees
  /// `.failed` and can manually retry.
  let maxConsecutiveFetchChannelFailures: Int

  init(
    transportWaitTimeout: TimeInterval = 30,
    disambiguateAfterAttempts: Int = 3,
    maxConsecutiveFetchChannelFailures: Int = 16
  ) {
    self.transportWaitTimeout = transportWaitTimeout
    self.disambiguateAfterAttempts = disambiguateAfterAttempts
    self.maxConsecutiveFetchChannelFailures = maxConsecutiveFetchChannelFailures
  }

  static let `default` = ChatSendQueueConfig()
}

/// Owns initial admission and durable DM and channel queues for one connection.
/// ServiceContainer retirement joins admitted work before replacement services hydrate it.
@MainActor
public final class ChatSendQueueService {
  let radioID: UUID
  private let config: ChatSendQueueConfig
  let dataStore: any MessagePersisting & ContactPersisting
  let messageService: MessageService
  private let channelService: ChannelService
  private let reactionService: ReactionService
  private let triggers: BLETransportOpenedSignal
  private let channelFetchFailureCounter = FailureCounter()
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "ChatSendQueueService")
  private let osLogger = Logger(subsystem: "com.mc1", category: "ChatSendQueueService")

  private let dmQueue: SendQueue<DirectMessageEnvelope>
  private let channelQueue: SendQueue<ChannelMessageEnvelope>

  private var hasHydrated = false
  private var isRetired = false
  /// The channel drain reads this without retaining the service. Shutdown sets it before the first suspension.
  private let sendsRetired = OSAllocatedUnfairLock(initialState: false)
  private var admittedWork = 0
  private var admissionWaiters: [CheckedContinuation<Void, Never>] = []
  private var shutdownTask: Task<Void, Never>?

  /// Task consuming the connection-state stream installed by
  /// `observeConnectionState`. Cancelled in `shutdown()`.
  private var connectionStateTask: Task<Void, Never>?

  init(
    radioID: UUID,
    dataStore: any MessagePersisting & ContactPersisting,
    messageService: MessageService,
    channelService: ChannelService,
    reactionService: ReactionService,
    config: ChatSendQueueConfig = .default,
    plainTextSendGate: PlainTextSendGate? = nil
  ) {
    self.radioID = radioID
    self.config = config
    self.dataStore = dataStore
    self.messageService = messageService
    self.channelService = channelService
    self.reactionService = reactionService
    self.triggers = BLETransportOpenedSignal()

    // Capture by value into closures; queues outlive references but
    // are torn down with the service.
    let triggers = triggers
    let dataStoreRef = dataStore
    let messageServiceRef = messageService
    let channelServiceRef = channelService
    let reactionServiceRef = reactionService
    let loggerRef = logger
    let osLoggerRef = osLogger
    let failureCounter = channelFetchFailureCounter
    let configRef = config
    let sendsRetired = self.sendsRetired

    dmQueue = SendQueue<DirectMessageEnvelope>(
      send: { envelope in
        var pendingSendID: UUID?
        // Outer catch: the queue-routed catch sites in MessageService
        // do not broadcast `.failed` themselves; the helper
        // `failMessageAndRethrow` only writes the DB state and
        // rethrows. Any non-`CancellationError` escape from this
        // closure is a terminal failure for the envelope, so the
        // queue calls `notifyMessageFailed` exactly once before
        // letting `SendQueue.drain` propagate the error to `onError`.
        // Park-and-requeue branches throw `CancellationError`, hit
        // the inner re-throw, and bypass the broadcast so a
        // transient error does not produce a `.failed`-then-`.pending`
        // flicker on the UI.
        do {
          let sendLease = try await plainTextSendGate?.acquire()
          func stopIssuing() async {
            if let sendLease {
              await plainTextSendGate?.stopIssuing(sendLease)
            }
          }
          defer { await stopIssuing() }
          try Task.checkCancellation()
          try ChatSendQueueService.cancelSendIfRetired(sendsRetired)

          let contact: ContactDTO
          switch await ChatSendQueueService.classifyRead({ try await dataStoreRef.fetchContact(id: envelope.contactID) }) {
          case let .found(value):
            contact = value
          case .missing:
            loggerRef.info("DM send queue: contact \(envelope.contactID) was deleted; dropping envelope")
            await stopIssuing()
            try? await dataStoreRef.deletePendingSendsForMessage(messageID: envelope.messageID)
            try? await dataStoreRef.updateMessageStatus(id: envelope.messageID, status: .failed)
            return
          case let .transient(error):
            loggerRef.warning("DM drain fetchContact transient error: \(String(describing: error)); parking envelope")
            await stopIssuing()
            try await ChatSendQueueService.parkAndCancel(
              triggers: triggers,
              logger: loggerRef,
              messageID: envelope.messageID,
              kind: "DM",
              timeout: configRef.transportWaitTimeout
            )
          }

          // Persist the attempt after admission but before any command, so replay knows
          // whether transmission may have started and must preserve the timestamp.
          let preserveTimestamp: Bool
          do {
            guard let result = try await ChatSendQueueService.preflightAndBump(
              dataStore: dataStoreRef,
              messageID: envelope.messageID,
              kind: "DM",
              logger: loggerRef,
              osLogger: osLoggerRef
            ) else {
              return
            }
            preserveTimestamp = result.preserveTimestamp
            pendingSendID = result.pendingSendID
          } catch {
            await stopIssuing()
            try await ChatSendQueueService.parkAndCancel(
              triggers: triggers,
              logger: loggerRef,
              messageID: envelope.messageID,
              kind: "DM",
              timeout: configRef.transportWaitTimeout
            )
          }

          do {
            let message = try await messageServiceRef.performQueuedDirectMessageSend(
              messageID: envelope.messageID,
              to: contact,
              preserveTimestamp: preserveTimestamp,
              isResend: envelope.isResend,
              isSingleAttempt: envelope.isSingleAttempt,
              pendingSendID: pendingSendID,
              sendLease: sendLease
            )
            if message.status == .delivered || message.status == .failed, let pendingSendID {
              try? await dataStoreRef.deletePendingSend(id: pendingSendID)
            }
            osLoggerRef.debug("DM drain success messageID=\(envelope.messageID)")
            // Clear the trigger once wire work is complete; an ACK-only row
            // remains durable without needing another transport-open signal.
            await triggers.clear()
          } catch is CancellationError {
            throw CancellationError()
          } catch {
            guard ChatSendQueueService.isTransientDirectMessageError(error) else {
              loggerRef.info("DM drain terminal messageID=\(envelope.messageID) error=\(String(describing: error))")
              throw error
            }
            loggerRef.info("DM drain transient messageID=\(envelope.messageID) error=\(String(describing: error))")
            // Remap a parked failure back to .pending so the bubble does not show Failed.
            // The next pass keeps this timestamp and continues at the stored attempt byte.
            _ = try? await dataStoreRef.updateMessageStatusUnlessDelivered(id: envelope.messageID, status: .pending)
            try await ChatSendQueueService.parkAndCancel(
              triggers: triggers,
              logger: loggerRef,
              messageID: envelope.messageID,
              kind: "DM",
              timeout: configRef.transportWaitTimeout
            )
          }
        } catch let cancellation as CancellationError {
          // The replacement queue hydrates this row. A retired gate cannot retry it.
          if await plainTextSendGate?.isInvalidated == true, !Task.isCancelled { return }
          throw cancellation
        } catch {
          if let pendingSendID {
            try? await dataStoreRef.deletePendingSend(id: pendingSendID)
          }
          await messageServiceRef.notifyMessageFailed(messageID: envelope.messageID)
          throw error
        }
      },
      onError: { _, _ in },
      onDrain: { lastError in
        if let lastError {
          loggerRef.error("DM queue drained with error: \(String(describing: lastError))")
        }
      }
    )

    let channelDrain = ChannelDrainDependencies(
      radioID: radioID,
      dataStore: dataStoreRef,
      messageService: messageServiceRef,
      channelService: channelServiceRef,
      reactionService: reactionServiceRef,
      triggers: triggers,
      logger: loggerRef,
      osLogger: osLoggerRef,
      failureCounter: failureCounter,
      config: configRef,
      sendsRetired: sendsRetired
    )
    channelQueue = SendQueue<ChannelMessageEnvelope>(
      send: { envelope in
        try await ChatSendQueueService.sendQueuedChannel(envelope: envelope, dependencies: channelDrain)
      },
      onError: { _, envelope in
        try? await dataStoreRef.deletePendingSendsForMessage(messageID: envelope.messageID)
      },
      onDrain: { lastError in
        if let lastError {
          loggerRef.error("Channel queue drained with error: \(String(describing: lastError))")
        }
      }
    )
  }

  /// Persists the DM envelope before signaling the drain so reconnect can recover it.
  func enqueueDM(_ envelope: DirectMessageEnvelope) async throws {
    guard beginAdmission() else { throw CancellationError() }
    defer { finishAdmission() }
    try await persist(PendingSendDTO(envelope: envelope, radioID: radioID))
    guard !isRetired else { throw CancellationError() }
    await dmQueue.enqueue(envelope)
  }

  public func enqueueChannel(_ envelope: ChannelMessageEnvelope) async throws {
    guard beginAdmission() else { throw CancellationError() }
    defer { finishAdmission() }
    try await persist(PendingSendDTO(envelope: envelope, radioID: radioID))
    guard !isRetired else { throw CancellationError() }
    await channelQueue.enqueue(envelope)
  }

  public func enqueueReaction(to target: MessageDTO, emoji: String, senderName: String) async throws -> MessageDTO? {
    guard target.radioID == radioID else { throw ChatSendQueueServiceError.notConnected }
    guard beginAdmission() else { throw CancellationError() }
    defer { finishAdmission() }

    let text: String
    if target.channelIndex != nil {
      guard let targetSender = target.isOutgoing ? senderName : target.senderNodeName else { return nil }
      text = reactionService.buildReactionText(
        emoji: emoji, targetSender: targetSender, targetText: target.text, targetTimestamp: target.reactionTimestamp
      )
    } else {
      text = reactionService.buildDMReactionText(
        emoji: emoji, targetText: target.text, targetTimestamp: target.reactionTimestamp
      )
    }
    let model = Message(
      radioID: radioID, contactID: target.contactID, channelIndex: target.channelIndex,
      text: text, timestamp: UInt32(Date().timeIntervalSince1970),
      directionRawValue: MessageDirection.outgoing.rawValue,
      statusRawValue: MessageStatus.pending.rawValue
    )
    model.reactionTargetMessageID = target.id
    model.reactionSenderName = senderName
    let pending = MessageDTO(from: model)
    try await messageService.validateOutgoingReaction(pending)
    guard let message = try await dataStore.admitOutgoingReaction(pending) else { return nil }
    guard !isRetired else { return message }
    if let channelIndex = message.channelIndex {
      await channelQueue.enqueue(ChannelMessageEnvelope(
        messageID: message.id, channelIndex: channelIndex, isResend: false,
        messageText: message.text, messageTimestamp: message.timestamp, localNodeName: senderName
      ))
    } else if let contactID = message.contactID {
      await dmQueue.enqueue(DirectMessageEnvelope(messageID: message.id, contactID: contactID, isSingleAttempt: true))
    }
    return message
  }

  /// Signals the DM queue that a `PendingSend` row already exists for
  /// this envelope. Used by the manual retry path where
  /// `PersistenceStore.replacePendingSendForRetry` has already written
  /// the row in one transaction; calling `enqueueDM` here would
  /// double-persist. The drain still reads the row back via
  /// `hasPendingSend` on the next send attempt, so the in-memory
  /// enqueue is the only step left.
  public func signalDMEnqueued(_ envelope: DirectMessageEnvelope) async {
    guard beginAdmission() else { return }
    defer { finishAdmission() }
    await dmQueue.enqueue(envelope)
  }

  /// Starts observing the connection-state stream and fires the
  /// transport-open trigger exactly once each time the connection enters
  /// `.ready`. This wakes parked retries after initial sync. Queued channel
  /// sends independently verify the current slot before replay. In production the container
  /// is built while still `.connected`, so the `.ready` wake arrives on the
  /// event stream; the initial-value check only fires when the observation is
  /// (re)started against an already-`.ready` connection, waking drains parked in
  /// `withCooperativeTimeout` instead of leaving them to wait out
  /// `transportWaitTimeout`. Firing on "entered `.ready`" covers both the success
  /// edge (`.connected → .ready`) and the failure-recovery edge
  /// (`.syncing → .ready`). Calling again replaces the previous observation.
  func observeConnectionState(
    initial: DeviceConnectionState,
    events: AsyncStream<DeviceConnectionState>
  ) {
    guard !isRetired else { return }
    connectionStateTask?.cancel()
    if initial.canDrainSendQueue {
      transportDidOpen()
    }
    connectionStateTask = Task { [weak self] in
      var previous = initial
      for await state in events {
        guard let self else { return }
        if !previous.canDrainSendQueue, state.canDrainSendQueue {
          transportDidOpen()
        }
        previous = state
      }
    }
  }

  /// Fired by the connection-state observation started by
  /// `observeConnectionState` each time the connection enters `.ready`.
  /// Fires the trigger that wakes any drain attempt suspended in
  /// `withCooperativeTimeout`.
  ///
  /// Fire-and-forget Task is intentional: `triggers.fire` is idempotent
  /// (arming an already-armed bit is a no-op), so a tight reconnect cycle
  /// (`.connected → .connecting → .connected` within a frame) collapses
  /// to a single armed trigger on the actor. Do not extend this function
  /// with per-call state — the fire-and-forget shape would lose ordering.
  func transportDidOpen() {
    guard !isRetired else { return }
    osLogger.debug("transportDidOpen firing trigger")
    Task { [triggers] in
      await triggers.fire()
    }
  }

  /// Park the drain on the transport-open trigger up to `timeout` seconds.
  /// Returns true if the signal fired, false on timeout or
  /// cancelled-mid-wait (caller treats both as "requeue"). `nonisolated`
  /// so the off-main send closures can call it.
  nonisolated static func waitForTransportOpen(
    triggers: BLETransportOpenedSignal,
    logger: PersistentLogger,
    messageID: UUID,
    kind: String,
    timeout: TimeInterval
  ) async throws -> Bool {
    do {
      try await withCooperativeTimeout(seconds: timeout) {
        try await triggers.wait()
      }
      return true
    } catch is CancellationError {
      if Task.isCancelled {
        logger.info("\(kind) drain cancelled mid-wait for \(messageID); requeueing")
      } else {
        logger.info("\(kind) drain timeout-without-fire after \(Int(timeout))s for \(messageID); requeueing")
      }
      return false
    }
  }

  /// Prepares durable work before any radio command and preserves timestamps after the first attempt.
  /// A missing row ends the drain; persistence errors let the caller park and retry.
  nonisolated static func preflightAndBump(
    dataStore: any MessagePersisting,
    messageID: UUID,
    kind: String,
    logger: PersistentLogger,
    osLogger: os.Logger
  ) async throws -> (pendingSendID: UUID, postBumpCount: Int, preserveTimestamp: Bool)? {
    switch await ChatSendQueueService.classifyRead({ () async throws -> Bool? in
      try await dataStore.hasPendingSend(messageID: messageID) ? true : nil
    }) {
    case .found:
      break
    case .missing:
      logger.info("\(kind) drain abandoned messageID=\(messageID) reason=PendingSendGone")
      return nil
    case let .transient(error):
      logger.warning("\(kind) drain hasPendingSend transient error: \(String(describing: error)); parking envelope")
      throw error
    }

    let postBumpCount: Int
    let pendingSendID: UUID
    do {
      guard let bumped = try await dataStore.preparePendingSendAttempt(messageID: messageID) else {
        logger.info("\(kind) drain: PendingSend row gone for messageID=\(messageID); treating as terminal")
        return nil
      }
      postBumpCount = bumped.attemptCount
      pendingSendID = bumped.pendingSendID
    } catch {
      logger.warning("preparePendingSendAttempt failed: \(String(describing: error)); parking envelope for next transport-open")
      throw error
    }

    let preserveTimestamp = postBumpCount > 1
    osLogger.debug("\(kind) drain begin messageID=\(messageID) postBumpCount=\(postBumpCount) preserveTimestamp=\(preserveTimestamp)")
    return (pendingSendID: pendingSendID, postBumpCount: postBumpCount, preserveTimestamp: preserveTimestamp)
  }

  nonisolated static func cancelSendIfRetired(_ sendsRetired: OSAllocatedUnfairLock<Bool>) throws {
    if sendsRetired.withLock({ $0 }) {
      throw CancellationError()
    }
  }

  nonisolated static func validateQueuedChannel(
    envelope: ChannelMessageEnvelope,
    radioID: UUID,
    dataStore: any MessagePersisting,
    channelService: ChannelService,
    triggers: BLETransportOpenedSignal,
    logger: PersistentLogger,
    timeout: TimeInterval
  ) async throws -> Bool {
    do {
      try Task.checkCancellation()
      guard try await dataStore.hasPendingSend(messageID: envelope.messageID) else { return false }
      guard try await channelService.getChannel(radioID: radioID, index: envelope.channelIndex) != nil else {
        throw MessageServiceError.channelNotFound
      }
      try Task.checkCancellation()
      return true
    } catch MessageServiceError.channelNotFound {
      _ = try await dataStore.updateMessageStatusUnlessDelivered(id: envelope.messageID, status: .failed)
      throw MessageServiceError.channelNotFound
    } catch {
      _ = try? await dataStore.updateMessageStatusUnlessDelivered(id: envelope.messageID, status: .pending)
      try await parkAndCancel(
        triggers: triggers, logger: logger, messageID: envelope.messageID,
        kind: "channel", timeout: timeout
      )
    }
  }

  /// Awaits a transport-open trigger up to the bounded timeout, then throws
  /// `CancellationError` to drive `SendQueue.drain`'s requeue protocol.
  /// Sites that need to revert message status to `.pending` before parking
  /// must call `updateMessageStatusUnlessDelivered` themselves — this helper
  /// does not write status.
  nonisolated static func parkAndCancel(
    triggers: BLETransportOpenedSignal,
    logger: PersistentLogger,
    messageID: UUID,
    kind: String,
    timeout: TimeInterval
  ) async throws -> Never {
    _ = try await ChatSendQueueService.waitForTransportOpen(
      triggers: triggers,
      logger: logger,
      messageID: messageID,
      kind: kind,
      timeout: timeout
    )
    throw CancellationError()
  }

  /// Classify a send error as transient (park the envelope and wait on a
  /// transport-open trigger) or terminal (drop the row). The transient
  /// `deviceError` code differs between DM and channel paths — see
  /// `FirmwareDeviceErrorCode`.
  nonisolated static func isTransientError(_ error: Error, deviceCode: UInt8) -> Bool {
    if let serviceError = error as? MessageServiceError {
      switch serviceError {
      case let .sessionError(underlying):
        return isTransientError(underlying, deviceCode: deviceCode)
      case .notConnected, .persistenceError:
        return true
      case .contactNotFound, .channelNotFound, .sendFailed,
           .invalidRecipient, .messageTooLong:
        return false
      }
    }
    guard let meshError = error as? MeshCoreError else { return false }
    switch meshError {
    case .timeout, .notConnected, .connectionLost,
         .bluetoothPoweredOff, .sessionNotStarted:
      return true
    case let .deviceError(code):
      return code == deviceCode
    case .parseError, .commandFailed, .invalidResponse,
         .contactNotFound, .dataTooLarge, .signingFailed, .invalidInput,
         .unknown, .bluetoothUnavailable, .bluetoothUnauthorized,
         .featureDisabled:
      return false
    }
  }

  // MARK: - Store-read classifier

  private nonisolated enum DrainStoreReadOutcome<Value: Sendable> {
    /// Read succeeded with a value.
    case found(Value)
    /// Read succeeded but the row is gone — terminal, drop the envelope.
    case missing
    /// Read threw — transient; park and retry on next transport open.
    case transient(Error)
  }

  /// Wraps a `throws -> T?` store read into a tri-state outcome so the drain
  /// can distinguish "row deleted" (terminal) from "store fault" (transient).
  private nonisolated static func classifyRead<Value: Sendable>(
    _ work: @Sendable () async throws -> Value?
  ) async -> DrainStoreReadOutcome<Value> {
    do {
      if let value = try await work() {
        return .found(value)
      }
      return .missing
    } catch {
      return .transient(error)
    }
  }

  nonisolated static func isTransientDirectMessageError(_ error: Error) -> Bool {
    isTransientError(error, deviceCode: FirmwareDeviceErrorCode.directMessageTableFull)
  }

  nonisolated static func isTransientChannelMessageError(_ error: Error) -> Bool {
    isTransientError(error, deviceCode: FirmwareDeviceErrorCode.channelMessageNotFound)
  }

  /// Returns true if `error` is `MeshCoreError.deviceError(channelMessageNotFound)`
  /// or `MessageServiceError.sessionError` wrapping the same. Used by the channel
  /// drain catch to recognise the firmware NOT_FOUND signal regardless of which
  /// MessageService entrypoint surfaced it.
  ///
  /// `MessageServiceError.sessionError` wraps `MeshCoreError` (a leaf — has no
  /// `.sessionError` case), so the unwrap is single-level by construction. Two
  /// pattern-matches cover both shapes; no recursion needed.
  nonisolated static func isChannelMessageNotFound(_ error: Error) -> Bool {
    if case let MeshCoreError.deviceError(code) = error {
      return code == FirmwareDeviceErrorCode.channelMessageNotFound
    }
    if case let MessageServiceError.sessionError(underlying) = error,
       case let MeshCoreError.deviceError(code) = underlying {
      return code == FirmwareDeviceErrorCode.channelMessageNotFound
    }
    return false
  }

  // MARK: - Persistence

  private func persist(_ dto: PendingSendDTO) async throws {
    do {
      _ = try await dataStore.insertPendingSendAssigningSequence(dto)
    } catch {
      logger.error("Persisting envelope failed: \(String(describing: error))")
      throw ChatSendQueueServiceError.persistFailed(underlying: error)
    }
  }

  // MARK: - Hydration

  /// Hydrates this Radio ID's pending sends once per service instance before app wiring and sync.
  /// `PersistenceStore.warmUp()` must purge legacy nil-attempt rows first; zero-attempt
  /// rows represent unsent work and receive a fresh timestamp on their first attempt.
  func hydrate() async {
    guard !hasHydrated, beginAdmission() else { return }
    defer { finishAdmission() }
    hasHydrated = true
    logger.info("hydrate begin radio=\(radioID)")
    do {
      let rows = try await dataStore.fetchPendingSends(radioID: radioID)
      for dto in rows {
        guard !isRetired else { return }
        guard dto.ackDeadline == nil else { continue }
        osLogger.debug("hydrate enqueue messageID=\(dto.messageID) kind=\(String(describing: dto.kind)) isResend=\(dto.isResend) attemptCount=\(String(describing: dto.attemptCount))")
        switch dto.kind {
        case .dm:
          if let envelope = dto.directMessageEnvelope() {
            await dmQueue.enqueue(envelope)
          }
        case .channel:
          if let envelope = dto.channelMessageEnvelope() {
            await channelQueue.enqueue(envelope)
          }
        }
      }
      logger.info("hydrate complete count=\(rows.count) radio=\(radioID)")
    } catch {
      logger.error("Hydration failed: \(String(describing: error))")
    }
  }

  #if DEBUG
    var isRetiredForTesting: Bool {
      isRetired
    }

    var parkedDrainCountForTesting: Int {
      get async { await triggers.waiterCountForTesting }
    }

    var isDMDrainingForTesting: Bool {
      get async { await dmQueue.isDrainingForTesting }
    }

    /// Test-only: drain readiness for synchronizing tests.
    func awaitDrainCompletion() async {
      await dmQueue.awaitDrainCompletion()
      await channelQueue.awaitDrainCompletion()
    }
  #endif

  /// Cancel both queues' drains so the underlying actors can deinit.
  ///
  /// Without this, send closures suspended in `withCooperativeTimeout`
  /// keep the queue actor alive (and its captured `MessageService` /
  /// `triggers`), respawning on every `.notConnected` requeue. `PendingSend`
  /// rows survive in SwiftData, so the next container's `hydrate()` replays
  /// them on reconnect.
  ///
  /// Cancelling the connection-state observation unregisters its
  /// `EventBroadcaster` subscription so a torn-down container never
  /// receives the next connection's edge.
  func shutdown() async {
    if let shutdownTask { await shutdownTask.value; return }
    sendsRetired.withLock { $0 = true }
    isRetired = true
    let worker = Task { await performShutdown() }
    shutdownTask = worker
    await worker.value
  }

  private func performShutdown() async {
    let observer = connectionStateTask
    connectionStateTask = nil
    observer?.cancel()
    await observer?.value
    if admittedWork != 0 {
      await withCheckedContinuation { admissionWaiters.append($0) }
    }
    await dmQueue.cancelDrain()
    await channelQueue.cancelDrain()
  }

  func beginAdmission() -> Bool {
    guard !isRetired else { return false }
    admittedWork += 1
    return true
  }

  func finishAdmission() {
    admittedWork -= 1
    guard admittedWork == 0 else { return }
    let waiters = admissionWaiters
    admissionWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }
}

/// Tracks consecutive `fetchChannel` throws inside the channel-drain
/// closure, keyed by `messageID` so that one envelope's persistent
/// fetchChannel failures don't terminal-fail a different envelope on its
/// first throw. Wrapped in an actor so the off-main drain closure can
/// safely mutate without crossing main-actor isolation. The map is
/// cleaned up on success and on terminal-fail so it does not grow
/// unboundedly.
actor FailureCounter {
  private var counts: [UUID: Int] = [:]

  @discardableResult
  func increment(for messageID: UUID) -> Int {
    let next = (counts[messageID] ?? 0) + 1
    counts[messageID] = next
    return next
  }

  func reset(for messageID: UUID) {
    counts.removeValue(forKey: messageID)
  }
}
