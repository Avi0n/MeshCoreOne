import Foundation
import os
import SwiftData

public extension PersistenceStore {
  // MARK: - RemoteNodeSession Operations

  /// Fetch remote node session by UUID
  func fetchRemoteNodeSession(id: UUID) throws -> RemoteNodeSessionDTO? {
    let targetID = id
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first.map { RemoteNodeSessionDTO(from: $0) }
  }

  /// Fetch remote node session by 32-byte public key
  func fetchRemoteNodeSession(publicKey: Data) throws -> RemoteNodeSessionDTO? {
    let targetKey = publicKey
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.publicKey == targetKey
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first.map { RemoteNodeSessionDTO(from: $0) }
  }

  /// Fetch remote node session by 6-byte public key prefix
  func fetchRemoteNodeSessionByPrefix(_ prefix: Data) throws -> RemoteNodeSessionDTO? {
    // SwiftData predicates don't support prefix matching directly
    // Fetch all sessions and filter in memory
    let sessions = try modelContext.fetch(FetchDescriptor<RemoteNodeSession>())
    return sessions.first { $0.publicKey.prefix(6) == prefix }.map { RemoteNodeSessionDTO(from: $0) }
  }

  /// Fetch all connected sessions for re-authentication after BLE reconnection
  func fetchConnectedRemoteNodeSessions() throws -> [RemoteNodeSessionDTO] {
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.isConnected == true
    }
    let descriptor = FetchDescriptor(predicate: predicate)
    let sessions = try modelContext.fetch(descriptor)
    return sessions.map { RemoteNodeSessionDTO(from: $0) }
  }

  /// Fetch all remote node sessions for a device
  func fetchRemoteNodeSessions(radioID: UUID) throws -> [RemoteNodeSessionDTO] {
    let targetRadioID = radioID
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.radioID == targetRadioID
    }
    let descriptor = FetchDescriptor(
      predicate: predicate,
      sortBy: [SortDescriptor(\RemoteNodeSession.name)]
    )
    let sessions = try modelContext.fetch(descriptor)
    return sessions.map { RemoteNodeSessionDTO(from: $0) }
  }

  /// Save or update a remote node session (void version for cross-actor calls)
  func saveRemoteNodeSessionDTO(_ dto: RemoteNodeSessionDTO) throws {
    _ = try saveRemoteNodeSession(dto)
  }

  /// Save or update a remote node session
  @discardableResult
  private func saveRemoteNodeSession(_ dto: RemoteNodeSessionDTO) throws -> RemoteNodeSession {
    let targetID = dto.id
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let existing = try modelContext.fetch(descriptor).first {
      existing.apply(dto)
      try modelContext.save()
      return existing
    } else {
      let session = RemoteNodeSession(dto: dto)
      modelContext.insert(session)
      try modelContext.save()
      return session
    }
  }

  /// Update session connection state
  func updateRemoteNodeSessionConnection(
    id: UUID,
    isConnected: Bool,
    permissionLevel: RoomPermissionLevel
  ) throws {
    let targetID = id
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let session = try modelContext.fetch(descriptor).first {
      session.isConnected = isConnected
      session.permissionLevelRawValue = permissionLevel.rawValue
      if isConnected {
        session.lastConnectedDate = Date()
      }
      try modelContext.save()
    }
  }

  /// Reset all remote node sessions to disconnected state.
  /// Call this on app launch since connections don't persist across restarts.
  func resetAllRemoteNodeSessionConnections() throws {
    let descriptor = FetchDescriptor<RemoteNodeSession>()
    let sessions = try modelContext.fetch(descriptor)
    for session in sessions {
      session.isConnected = false
    }
    try modelContext.save()
  }

  /// Clean up duplicate remote node sessions with the same public key.
  /// Keeps the session with the specified ID and deletes any others.
  /// This prevents stale sessions from causing connection state issues.
  func cleanupDuplicateRemoteNodeSessions(publicKey: Data, keepID: UUID) throws {
    let targetKey = publicKey
    let targetKeepID = keepID
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.publicKey == targetKey && session.id != targetKeepID
    }
    let duplicates = try modelContext.fetch(FetchDescriptor(predicate: predicate))

    if !duplicates.isEmpty {
      let logger = Logger(subsystem: "com.mc1", category: "PersistenceStore")
      logger.warning("Found \(duplicates.count) duplicate session(s) for public key, cleaning up")

      for duplicate in duplicates {
        // Delete associated room messages first
        let duplicateID = duplicate.id
        let messagePredicate = #Predicate<RoomMessage> { message in
          message.sessionID == duplicateID
        }
        let messages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))
        for message in messages {
          modelContext.delete(message)
        }

        modelContext.delete(duplicate)
      }
      try modelContext.save()
    }
  }

  /// Delete remote node session and all associated room messages
  func deleteRemoteNodeSession(id: UUID) throws {
    let targetID = id

    // Delete associated room messages
    let messagePredicate = #Predicate<RoomMessage> { message in
      message.sessionID == targetID
    }
    let messages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))
    for message in messages {
      modelContext.delete(message)
    }

    // Delete the session
    let sessionPredicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    if let session = try modelContext.fetch(FetchDescriptor(predicate: sessionPredicate)).first {
      modelContext.delete(session)
    }

    try modelContext.save()
  }

  /// Mark a room session as connected. Called when an incoming message proves
  /// the session is active. Only sets isConnected; does not change permissionLevel.
  /// - Returns: true if the session was actually changed (was disconnected, now connected).
  @discardableResult
  func markRoomSessionConnected(_ sessionID: UUID) throws -> Bool {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let session = try modelContext.fetch(descriptor).first else { return false }
    guard !session.isConnected else { return false }

    session.isConnected = true
    try modelContext.save()
    return true
  }

  /// Mark a session as disconnected without changing permission level.
  /// Use for transient disconnections (BLE drop, keep-alive failure, re-auth failure).
  /// Only logout() should reset permissionLevel to .guest.
  func markSessionDisconnected(_ sessionID: UUID) throws {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let session = try modelContext.fetch(descriptor).first {
      session.isConnected = false
      try modelContext.save()
    }
  }

  /// Update room activity timestamps.
  /// - Parameters:
  ///   - sessionID: The room session ID
  ///   - syncTimestamp: Optional sender-clock timestamp for sync bookmark advancement.
  ///     Only provided on the receive path. Omit on the send path to avoid clock skew
  ///     issues where local send timestamps could advance the sync bookmark past
  ///     messages the server hasn't delivered yet.
  func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32? = nil) throws {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let session = try modelContext.fetch(descriptor).first {
      if let syncTimestamp, syncTimestamp > session.lastSyncTimestamp {
        session.lastSyncTimestamp = syncTimestamp
      }
      session.lastMessageDate = Date()
      try modelContext.save()
    }
  }

  // MARK: - RoomMessage Operations

  /// Check for duplicate room message using deduplication key
  func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) throws -> Bool {
    let targetSessionID = sessionID
    let targetKey = deduplicationKey
    let predicate = #Predicate<RoomMessage> { message in
      message.sessionID == targetSessionID && message.deduplicationKey == targetKey
    }
    return try modelContext.fetchCount(FetchDescriptor(predicate: predicate)) > 0
  }

  /// Save room message (checks deduplication automatically)
  func saveRoomMessage(_ dto: RoomMessageDTO) throws {
    // Check for duplicate first
    if try isDuplicateRoomMessage(sessionID: dto.sessionID, deduplicationKey: dto.deduplicationKey) {
      return // Silently ignore duplicates
    }

    modelContext.insert(RoomMessage(dto: dto))
    try modelContext.save()
  }

  /// Fetch a room message by ID
  func fetchRoomMessage(id: UUID) throws -> RoomMessageDTO? {
    let targetID = id
    let predicate = #Predicate<RoomMessage> { message in
      message.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    guard let message = try modelContext.fetch(descriptor).first else {
      return nil
    }
    return RoomMessageDTO(from: message)
  }

  /// Update room message status after send attempt
  func updateRoomMessageStatus(
    id: UUID,
    status: MessageStatus,
    ackCode: UInt32? = nil,
    roundTripTime: UInt32? = nil
  ) throws {
    let targetID = id
    let predicate = #Predicate<RoomMessage> { message in
      message.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    guard let message = try modelContext.fetch(descriptor).first else {
      return
    }
    message.statusRawValue = status.rawValue
    if let ackCode {
      message.ackCode = ackCode
    }
    if let roundTripTime {
      message.roundTripTime = roundTripTime
    }
    try modelContext.save()
  }

  /// Update room message retry status
  func updateRoomMessageRetryStatus(
    id: UUID,
    status: MessageStatus,
    retryAttempt: Int,
    maxRetryAttempts: Int
  ) throws {
    let targetID = id
    let predicate = #Predicate<RoomMessage> { message in
      message.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    guard let message = try modelContext.fetch(descriptor).first else {
      return
    }
    message.statusRawValue = status.rawValue
    message.retryAttempt = retryAttempt
    message.maxRetryAttempts = maxRetryAttempts
    try modelContext.save()
  }

  /// Increment unread message count for a room session
  func incrementRoomUnreadCount(_ sessionID: UUID) throws {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let session = try modelContext.fetch(descriptor).first {
      session.unreadCount += 1
      try modelContext.save()
    }
  }

  /// Reset unread count to zero (called when user views conversation)
  func resetRoomUnreadCount(_ sessionID: UUID) throws {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { session in
      session.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let session = try modelContext.fetch(descriptor).first {
      session.unreadCount = 0
      try modelContext.save()
    }
  }

  /// Sets the muted state for a remote node session
  func setSessionMuted(_ sessionID: UUID, isMuted: Bool) throws {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { $0.id == targetID }
    var descriptor = FetchDescriptor<RemoteNodeSession>(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let session = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.remoteNodeSessionNotFound
    }

    session.notificationLevel = isMuted ? .muted : .all
    try modelContext.save()
  }

  /// Sets the notification level for a remote node session
  func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) throws {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { $0.id == targetID }
    var descriptor = FetchDescriptor<RemoteNodeSession>(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let session = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.remoteNodeSessionNotFound
    }

    session.notificationLevel = level
    try modelContext.save()
  }

  /// Sets the favorite state for a remote node session
  func setSessionFavorite(_ sessionID: UUID, isFavorite: Bool) throws {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { $0.id == targetID }
    var descriptor = FetchDescriptor<RemoteNodeSession>(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let session = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.remoteNodeSessionNotFound
    }

    session.isFavorite = isFavorite
    try modelContext.save()
  }

  /// Fetch room messages for a session, ordered by timestamp
  func fetchRoomMessages(sessionID: UUID, limit: Int? = nil, offset: Int? = nil) throws -> [RoomMessageDTO] {
    let targetSessionID = sessionID
    let predicate = #Predicate<RoomMessage> { message in
      message.sessionID == targetSessionID
    }
    var descriptor = FetchDescriptor(
      predicate: predicate,
      sortBy: [
        SortDescriptor(\RoomMessage.timestamp, order: .forward),
        SortDescriptor(\RoomMessage.createdAt, order: .forward)
      ]
    )
    if let limit {
      descriptor.fetchLimit = limit
    }
    if let offset {
      descriptor.fetchOffset = offset
    }
    let messages = try modelContext.fetch(descriptor)
    return messages.map { RoomMessageDTO(from: $0) }
  }
}
