import Foundation
import MeshCore
import os

// MARK: - Room Server Errors

public enum RoomServerError: Error, Sendable {
  case notConnected
  case sessionNotFound
  case sendFailed(String)
  case permissionDenied
  case invalidResponse
  case sessionError(MeshCoreError)
}

extension RoomServerError: LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .notConnected: "Not connected to device."
    case .sessionNotFound: "Room session not found."
    case let .sendFailed(msg): "Send failed: \(msg)"
    case .permissionDenied: "Permission denied."
    case .invalidResponse: "Invalid response from device."
    case let .sessionError(e): e.localizedDescription
    }
  }
}

// MARK: - Room Server Service

/// Service for room server interactions.
/// Handles joining rooms, posting messages, and receiving room messages.
public actor RoomServerService {
  // MARK: - Properties

  private let session: any RemoteAccessSessionOps
  private let remoteNodeService: RemoteNodeService
  private let dataStore: PersistenceStore
  private let textRulesPreference: @Sendable () async -> TextRulesPreference?
  private let radioID: UUID
  private let receiveLifetime: ReceiveScope.Lifetime
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "RoomServer")
  private let auditLogger = CommandAuditLogger()

  /// Self public key prefix for author comparison.
  /// Set from SelfInfo when device connects.
  private var selfPublicKeyPrefix: Data?

  private static let initialSendAttempts = 4
  private static let fallbackSendAttempts = 1
  private static let totalSendAttempts = initialSendAttempts + fallbackSendAttempts
  private let plainTextSendGate: PlainTextSendGate?

  /// Multicast broadcaster for status-update and connection-recovery events.
  private nonisolated let eventBroadcaster = EventBroadcaster<RoomServerEvent>()

  /// Tracks message IDs currently being retried to prevent concurrent retry attempts
  private var inFlightRetries: Set<UUID> = []

  private static let wireTimestampStep: TimeInterval = 1
  private var lastWireTimestampByRoom: [Data: TimeInterval] = [:]

  // MARK: - Initialization

  init(
    session: any RemoteAccessSessionOps,
    remoteNodeService: RemoteNodeService,
    dataStore: PersistenceStore,
    radioID: UUID,
    plainTextSendGate: PlainTextSendGate? = nil,
    textRulesPreference: @escaping @Sendable () async -> TextRulesPreference? = { nil },
    receiveLifetime: ReceiveScope.Lifetime = .init()
  ) {
    self.session = session
    self.remoteNodeService = remoteNodeService
    self.dataStore = dataStore
    self.radioID = radioID
    self.plainTextSendGate = plainTextSendGate
    self.textRulesPreference = textRulesPreference
    self.receiveLifetime = receiveLifetime
  }

  /// Set self public key prefix from SelfInfo.
  /// Call this when device info is received.
  public func setSelfPublicKeyPrefix(_ prefix: Data) {
    selfPublicKeyPrefix = prefix.prefix(4)
  }

  // MARK: - Events

  /// Returns a fresh stream of room-server events. Registration is
  /// synchronous, so events yielded after this call are never dropped.
  /// Consumers must re-subscribe per connection because the owning
  /// `ServiceContainer` is rebuilt on every connection.
  public nonisolated func events() -> AsyncStream<RoomServerEvent> {
    eventBroadcaster.subscribe()
  }

  /// Ends every `events()` subscriber's for-await loop. Called by
  /// `ServiceContainer.tearDown()` so consumer tasks release the service
  /// references they hold.
  nonisolated func finishEvents() {
    eventBroadcaster.finish()
  }

  // MARK: - Room Management

  /// Join a room server by creating a session and authenticating.
  /// Automatically syncs message history based on local state.
  /// - Parameters:
  ///   - radioID: The companion radio device ID
  ///   - contact: The room server contact
  ///   - password: Authentication password (uses keychain if not provided)
  ///   - rememberPassword: Whether to store password in keychain
  ///   - pathLength: Path length for timeout calculation (0 = direct)
  ///   - onTimeoutKnown: Optional callback invoked with timeout in seconds once firmware responds.
  /// - Returns: The authenticated session
  public func joinRoom(
    radioID: UUID,
    contact: ContactDTO,
    password: String?,
    rememberPassword: Bool = true,
    pathLength: UInt8 = 0,
    onTimeoutKnown: (@Sendable (Int) async -> Void)? = nil
  ) async throws -> RemoteNodeSessionDTO {
    let remoteSession = try await remoteNodeService.createSession(
      radioID: radioID,
      contact: contact
    )

    // Login to the room
    _ = try await remoteNodeService.login(
      sessionID: remoteSession.id,
      password: password,
      pathLength: pathLength,
      onTimeoutKnown: onTimeoutKnown
    )

    // Store password only after successful login
    if let password, rememberPassword {
      try await remoteNodeService.storePassword(password, forNodeKey: contact.publicKey)
    }

    // Attempt additional history sync if needed (non-blocking)
    await syncHistoryIfPossible(sessionID: remoteSession.id)

    guard let updatedSession = try await dataStore.fetchRemoteNodeSession(id: remoteSession.id) else {
      throw RemoteNodeError.sessionNotFound
    }
    return updatedSession
  }

  /// Reconnect to an existing room session and sync any missed messages.
  /// Use this when re-authenticating to a room after app restart or BLE reconnection.
  /// - Parameters:
  ///   - sessionID: The existing room session ID
  ///   - pathLength: Optional path length hint (0 = use shortest known path)
  /// - Returns: Updated session DTO
  /// - Throws: RemoteNodeError if reconnection fails
  public func reconnectRoom(
    sessionID: UUID,
    pathLength: UInt8 = 0
  ) async throws -> RemoteNodeSessionDTO {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    guard remoteSession.isRoom else {
      throw RemoteNodeError.invalidResponse
    }

    // Re-authenticate to the room
    _ = try await remoteNodeService.login(
      sessionID: sessionID,
      pathLength: pathLength
    )

    // Attempt additional history sync if needed (non-blocking)
    await syncHistoryIfPossible(sessionID: sessionID)

    guard let updatedSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }
    return updatedSession
  }

  /// Leave a room by sending logout and removing the session.
  /// - Parameters:
  ///   - sessionID: The session to leave
  ///   - publicKey: The room's public key (for keychain cleanup)
  public func leaveRoom(sessionID: UUID, publicKey: Data) async throws {
    try await remoteNodeService.logout(sessionID: sessionID)
    try await remoteNodeService.removeSession(id: sessionID, publicKey: publicKey)
  }

  /// Requests a fresh replay from this room's saved bookmark.
  public func refreshMessages(for room: RemoteNodeSessionDTO) async throws {
    guard room.radioID == radioID else { throw RoomServerError.notConnected }
    try await remoteNodeService.refreshRoom(room)
  }

  // MARK: - Message Posting

  /// Post a message to a room server.
  ///
  /// Posts use `TextType.plain`. The room server converts to `signedPlain`
  /// when pushing to other clients. The server does not push messages back
  /// to their authors, so the local message record is created immediately.
  /// - Parameters:
  ///   - sessionID: The room session
  ///   - text: The message text
  /// - Returns: The saved message DTO
  public func postMessage(sessionID: UUID, text: String) async throws -> RoomMessageDTO {
    let remoteSession = try await postableRoomSession(id: sessionID)

    let timestamp = Date()
    let messageID = UUID()

    // Create local message record immediately with pending status
    let messageDTO = RoomMessageDTO(
      id: messageID,
      sessionID: sessionID,
      authorKeyPrefix: selfPublicKeyPrefix ?? Data(repeating: 0, count: 4),
      authorName: "Me",
      text: text,
      timestamp: UInt32(timestamp.timeIntervalSince1970),
      isFromSelf: true,
      status: .pending,
      maxRetryAttempts: Self.totalSendAttempts
    )

    try await dataStore.saveRoomMessage(messageDTO)

    // Log room message posted (metadata only, no content)
    await auditLogger.logRoomMessagePosted(publicKey: remoteSession.publicKey, messageLength: text.count)

    // Send message in background so UI can show pending message immediately
    Task { [weak self] in
      guard let self else { return }

      await performRoomMessageSend(id: messageID, room: remoteSession)
    }

    // Return pending message immediately so UI can display it
    return messageDTO
  }

  /// Retry sending a failed room message.
  /// - Parameter id: The message ID to retry
  /// - Returns: Updated message DTO
  public func retryMessage(id: UUID) async throws -> RoomMessageDTO {
    // Guard against concurrent retries
    guard !inFlightRetries.contains(id) else {
      logger.warning("Retry already in progress for message: \(id)")
      throw RoomServerError.sendFailed("Retry already in progress")
    }

    inFlightRetries.insert(id)
    defer { inFlightRetries.remove(id) }

    guard let message = try await dataStore.fetchRoomMessage(id: id) else {
      throw RoomServerError.sendFailed("Message not found")
    }

    guard message.status == .failed else {
      throw RoomServerError.sendFailed("Message is not in failed state")
    }

    let remoteSession = try await postableRoomSession(id: message.sessionID)

    // Update to pending/retrying
    let newRetryAttempt = message.retryAttempt + 1
    try await dataStore.updateRoomMessageRetryStatus(
      id: id,
      status: .pending,
      retryAttempt: newRetryAttempt,
      maxRetryAttempts: Self.totalSendAttempts
    )
    eventBroadcaster.yield(.statusUpdated(messageID: id, status: .pending))

    await performRoomMessageSend(id: id, room: remoteSession)

    guard let updatedMessage = try await dataStore.fetchRoomMessage(id: id) else {
      throw RoomServerError.sendFailed("Failed to fetch message after retry")
    }
    return updatedMessage
  }

  private func performRoomMessageSend(id: UUID, room: RemoteNodeSessionDTO) async {
    do {
      let sendLease = try await plainTextSendGate?.acquire()
      defer {
        if let sendLease {
          await plainTextSendGate?.stopIssuing(sendLease)
        }
      }
      let sentInfo = try await sendRoomMessage(id: id, roomPublicKey: room.publicKey)
      let status: MessageStatus = sentInfo == nil ? .sent : .delivered

      // The radio accepted the post even when every ACK wait expired.
      // A status-write failure must not turn that acceptance into a failed send.
      do {
        try await dataStore.updateRoomMessageStatus(
          id: id,
          status: status,
          ackCode: sentInfo?.expectedAck.ackCodeUInt32,
          roundTripTime: sentInfo.map { UInt32($0.suggestedTimeoutMs) }
        )
      } catch {
        logger.error("Failed to update room message status after send: \(error)")
      }
      eventBroadcaster.yield(.statusUpdated(messageID: id, status: status))
      try? await dataStore.updateRoomActivity(room.id)
    } catch {
      do {
        try await dataStore.updateRoomMessageStatus(
          id: id,
          status: .failed,
          ackCode: nil,
          roundTripTime: nil
        )
      } catch let dbError {
        logger.error("Failed to update room message status after send error: \(dbError)")
      }
      eventBroadcaster.yield(.statusUpdated(messageID: id, status: .failed))
      logger.warning("Room message send failed: \(error)")
    }
  }

  private func postableRoomSession(id: UUID) async throws -> RemoteNodeSessionDTO {
    guard let room = try await dataStore.fetchRemoteNodeSession(id: id) else {
      throw RoomServerError.sessionNotFound
    }
    guard room.canPost else { throw RoomServerError.permissionDenied }
    return room
  }

  private func sendRoomMessage(id: UUID, roomPublicKey: Data) async throws -> MessageSentInfo? {
    // The room treats equal timestamps as retries. Wait for the next wall-clock
    // second to avoid sending a future timestamp.
    let now = Date().timeIntervalSince1970
    let second = now.rounded(.down)
    if lastWireTimestampByRoom[roomPublicKey] == second {
      try await Task.sleep(for: .seconds(second + Self.wireTimestampStep - now))
    }

    guard let message = try await dataStore.fetchRoomMessage(id: id) else {
      throw RoomServerError.sendFailed("Message not found")
    }
    if let plainTextSendGate, await plainTextSendGate.isInvalidated {
      throw CancellationError()
    }
    let room = try await postableRoomSession(id: message.sessionID)
    let timestamp = Date().timeIntervalSince1970.rounded(.down)
    if let previous = lastWireTimestampByRoom[room.publicKey], timestamp <= previous {
      throw RoomServerError.sendFailed("Room timestamp did not advance")
    }
    lastWireTimestampByRoom[room.publicKey] = timestamp

    return try await session.sendMessageWithRetry(
      to: room.publicKey,
      text: message.text,
      timestamp: Date(timeIntervalSince1970: timestamp),
      maxAttempts: Self.totalSendAttempts,
      floodAfter: Self.initialSendAttempts,
      maxFloodAttempts: Self.fallbackSendAttempts
    )
  }

  // MARK: - Incoming Messages

  /// Handle incoming room message.
  /// Called by MessagePollingService when a signedPlain message arrives from a room.
  ///
  /// Messages arrive as `TextType.signedPlain` with the room server's key as
  /// `senderPublicKeyPrefix` and the original author's 4-byte key prefix in
  /// the payload (extracted to `extraData` by `decodeMessageV3`).
  ///
  /// Since room servers don't push messages back to their authors, incoming
  /// messages should not be from self. However, we check defensively.
  /// - Parameters:
  ///   - senderPublicKeyPrefix: The room server's 6-byte key prefix
  ///   - timestamp: Message timestamp from server
  ///   - authorPrefix: The original author's 4-byte key prefix
  ///   - text: The message text
  /// - Returns: The saved message DTO, or nil if the message was a duplicate
  @discardableResult
  public func handleIncomingMessage(
    senderPublicKeyPrefix: Data,
    timestamp: UInt32,
    authorPrefix: Data,
    text: String
  ) async throws -> RoomMessageDTO? {
    guard let scope = try await dataStore.captureReceiveScope(radioID: radioID, link: receiveLifetime) else {
      return nil
    }
    return try await handleIncomingMessage(
      senderPublicKeyPrefix: senderPublicKeyPrefix,
      timestamp: timestamp,
      authorPrefix: authorPrefix,
      text: text,
      scope: scope
    )?.message
  }

  func handleIncomingMessage(
    senderPublicKeyPrefix: Data,
    timestamp: UInt32,
    authorPrefix: Data,
    text: String,
    scope: ReceiveScope
  ) async throws -> (message: RoomMessageDTO, room: RemoteNodeSessionDTO)? {
    let arrivedAt = Date()
    let preference = await textRulesPreference()
    guard scope.isCurrent, scope.radioID == radioID,
          let room = try await dataStore.fetchRemoteNodeSessions(radioID: radioID)
            .first(where: { $0.publicKey.prefix(6) == senderPublicKeyPrefix && $0.isRoom }) else {
      return nil
    }

    let post = RoomMessageDTO(
      sessionID: room.id,
      authorKeyPrefix: authorPrefix,
      text: text,
      timestamp: timestamp,
      createdAt: arrivedAt,
      isFromSelf: selfPublicKeyPrefix == authorPrefix
    )
    let result = try await dataStore.receiveRoomPost(post, in: room, scope: scope, preference: preference)
    guard scope.isCurrent else { return nil }
    if result.recovered {
      eventBroadcaster.yield(.connectionRecovered(sessionID: room.id))
    }
    guard let message = result.message else { return nil }
    await auditLogger.logRoomMessageReceived(
      roomPublicKey: senderPublicKeyPrefix,
      authorPrefix: authorPrefix,
      messageLength: text.count
    )
    return (message, room)
  }

  // MARK: - Message Retrieval

  /// Fetch messages for a room session.
  /// - Parameters:
  ///   - sessionID: The room session ID
  ///   - limit: Maximum number of messages to return
  ///   - offset: Offset for pagination
  /// - Returns: Array of room message DTOs
  public func fetchMessages(sessionID: UUID, limit: Int? = nil, offset: Int? = nil) async throws -> [RoomMessageDTO] {
    try await dataStore.fetchRoomMessages(sessionID: sessionID, limit: limit, offset: offset)
  }

  /// Mark room as read (reset unread count).
  /// Call when user views the conversation.
  /// - Parameter sessionID: The room session ID
  public func markAsRead(sessionID: UUID) async throws {
    try await dataStore.resetRoomUnreadCount(sessionID)
  }

  // MARK: - Session Queries

  /// Fetch all room sessions for a device.
  /// - Parameter radioID: The companion radio device ID
  /// - Returns: Array of room session DTOs
  public func fetchRoomSessions(radioID: UUID) async throws -> [RemoteNodeSessionDTO] {
    let sessions = try await dataStore.fetchRemoteNodeSessions(radioID: radioID)
    return sessions.filter(\.isRoom)
  }

  /// Check if a contact is a known room server with an active session.
  /// - Parameter publicKeyPrefix: The 6-byte public key prefix
  /// - Returns: The session if found and connected, nil otherwise
  public func getConnectedSession(publicKeyPrefix: Data) async throws -> RemoteNodeSessionDTO? {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSessions(radioID: radioID)
      .first(where: { $0.publicKey.prefix(6) == publicKeyPrefix && $0.isRoom && $0.isConnected }) else {
      return nil
    }
    return remoteSession
  }

  // MARK: - Private Helpers

  /// Attempt to sync history, using advert path first, then falling back to path discovery.
  private func syncHistoryIfPossible(sessionID: UUID) async {
    do {
      guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID),
            let contact = try await dataStore.findContactByPublicKey(remoteSession.publicKey) else {
        return
      }

      // Strategy:
      // 1. If contact has a path from advertisement (not flood-routed), try it first
      // 2. If that fails or contact is flood-routed, trigger path discovery
      // 3. Wait for discovery result and retry

      if !contact.isFloodRouted {
        // Contact has a path from advertisement - try it directly
        logger.info("Trying advert path for room \(remoteSession.name)")
        do {
          try await remoteNodeService.requestHistorySync(sessionID: sessionID)
          logger.info("History sync succeeded using advert path")
          return
        } catch {
          // Advert path didn't work - fall through to path discovery
          logger.info("Advert path failed for \(remoteSession.name): \(error), trying path discovery")
        }
      } else {
        logger.info("Room \(remoteSession.name) is flood-routed, attempting path discovery")
      }

      // Path discovery fallback
      let hasDirectRoute = try await discoverPathAndWait(sessionID: sessionID)
      if !hasDirectRoute {
        logger.info("Could not establish direct route for \(remoteSession.name), skipping history sync")
        return
      }

      // Retry with newly discovered path
      try await remoteNodeService.requestHistorySync(sessionID: sessionID)
      logger.info("History sync succeeded after path discovery")
    } catch {
      logger.warning("Failed to sync history for session \(sessionID): \(error)")
      // Don't fail the join - messages will arrive via normal flow
    }
  }

  /// Discover path and wait for direct route.
  private func discoverPathAndWait(sessionID: UUID, timeout: Duration = .seconds(10)) async throws -> Bool {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID),
          let contact = try await dataStore.findContactByPublicKey(remoteSession.publicKey) else {
      return false
    }

    // Already direct?
    if !contact.isFloodRouted {
      return true
    }

    // Trigger path discovery via MeshCore session
    do {
      _ = try await session.sendPathDiscovery(to: remoteSession.publicKey)
    } catch {
      logger.warning("Path discovery send failed: \(error)")
      return false
    }

    // Wait for result
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
      try await Task.sleep(for: .milliseconds(500))

      if let updated = try await dataStore.findContactByPublicKey(remoteSession.publicKey),
         !updated.isFloodRouted {
        return true
      }
    }

    return false
  }
}
