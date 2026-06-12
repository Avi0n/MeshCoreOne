import Foundation

/// Store operations for remote node sessions and room messages.
public protocol RoomPersisting: Actor {

    // MARK: - Room Session State

    /// Fetch a remote node session by ID
    func fetchRemoteNodeSession(id: UUID) async throws -> RemoteNodeSessionDTO?

    /// Fetch a remote node session by 32-byte public key
    func fetchRemoteNodeSession(publicKey: Data) async throws -> RemoteNodeSessionDTO?

    /// Fetch a remote node session by 6-byte public key prefix
    func fetchRemoteNodeSessionByPrefix(_ prefix: Data) async throws -> RemoteNodeSessionDTO?

    /// Fetch all remote node sessions for a device
    func fetchRemoteNodeSessions(radioID: UUID) async throws -> [RemoteNodeSessionDTO]

    /// Fetch all connected sessions for re-authentication after BLE reconnection
    func fetchConnectedRemoteNodeSessions() async throws -> [RemoteNodeSessionDTO]

    /// Save or update a remote node session
    func saveRemoteNodeSessionDTO(_ dto: RemoteNodeSessionDTO) async throws

    /// Update session connection state
    func updateRemoteNodeSessionConnection(id: UUID, isConnected: Bool, permissionLevel: RoomPermissionLevel) async throws

    /// Clean up duplicate remote node sessions with the same public key.
    /// Keeps the session with the specified ID and deletes any others.
    func cleanupDuplicateRemoteNodeSessions(publicKey: Data, keepID: UUID) async throws

    /// Delete remote node session and all associated room messages
    func deleteRemoteNodeSession(id: UUID) async throws

    /// Mark a session as disconnected without changing permission level.
    /// Use for transient disconnections (BLE drop, keep-alive failure, re-auth failure).
    func markSessionDisconnected(_ sessionID: UUID) async throws

    /// Mark a room session as connected. Returns true if the state actually changed.
    @discardableResult
    func markRoomSessionConnected(_ sessionID: UUID) async throws -> Bool

    /// Update room activity timestamps (sort date and optional sync bookmark).
    func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32?) async throws

    // MARK: - Room Message Operations

    /// Save a new room message
    func saveRoomMessage(_ dto: RoomMessageDTO) async throws

    /// Fetch a room message by ID
    func fetchRoomMessage(id: UUID) async throws -> RoomMessageDTO?

    /// Fetch room messages for a session
    func fetchRoomMessages(sessionID: UUID, limit: Int?, offset: Int?) async throws -> [RoomMessageDTO]

    /// Check for duplicate room message
    func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) async throws -> Bool

    /// Update room message status after send attempt
    func updateRoomMessageStatus(
        id: UUID,
        status: MessageStatus,
        ackCode: UInt32?,
        roundTripTime: UInt32?
    ) async throws

    /// Update room message retry status
    func updateRoomMessageRetryStatus(
        id: UUID,
        status: MessageStatus,
        retryAttempt: Int,
        maxRetryAttempts: Int
    ) async throws

    /// Increment unread message count for a room session
    func incrementRoomUnreadCount(_ sessionID: UUID) async throws

    /// Reset unread count to zero (called when user views conversation)
    func resetRoomUnreadCount(_ sessionID: UUID) async throws
}

// MARK: - Default Parameter Values

extension RoomPersisting {
    /// Update room activity with nil sync timestamp (sort date only)
    func updateRoomActivity(_ sessionID: UUID) async throws {
        try await updateRoomActivity(sessionID, syncTimestamp: nil)
    }
}
