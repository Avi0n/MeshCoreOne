import Foundation

/// Session operations for authenticated access to remote nodes (room servers
/// and repeaters): login, commands, keep-alive, and remote queries.
public protocol RemoteAccessSessionOps: Actor {
  /// Sends a login request to a remote node.
  ///
  /// - Parameters:
  ///   - destination: The node's public key (6+ bytes).
  ///   - password: The authentication password.
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func sendLogin(to destination: Data, password: String) async throws -> MessageSentInfo

  /// Sends a logout request to a remote node.
  ///
  /// - Parameter destination: The node's public key (6+ bytes).
  /// - Throws: `MeshCoreError` on timeout or device error.
  func sendLogout(to destination: Data) async throws

  /// Sends a command message to a remote node.
  ///
  /// - Parameters:
  ///   - destination: The destination public key (6+ bytes, uses first 6 as prefix).
  ///   - command: The command string to send.
  ///   - timestamp: Message timestamp.
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func sendCommand(to destination: Data, command: String, timestamp: Date) async throws -> MessageSentInfo

  /// Sends a keep-alive request to a room server with the client's sync watermark.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the room server.
  ///   - syncSince: The client's last-received message timestamp.
  /// - Returns: Information about the sent message.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func sendKeepAlive(to publicKey: Data, syncSince: UInt32) async throws -> MessageSentInfo

  /// Requests owner information from a repeater using the binary protocol.
  ///
  /// - Parameter publicKey: The full 32-byte public key of the repeater.
  /// - Returns: An ``OwnerInfoResponse`` containing firmware version, node name, and owner info.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func requestOwnerInfo(from publicKey: Data) async throws -> OwnerInfoResponse

  /// Requests status information from a remote node.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the remote node.
  ///   - type: The target node type used to choose the correct firmware status layout.
  /// - Returns: A status response containing battery, uptime, and other metrics.
  /// - Throws: `MeshCoreError` on timeout, device error, or unexpected response.
  func requestStatus(from publicKey: Data, type: ContactType) async throws -> StatusResponse

  /// Requests telemetry data from a remote node using the binary protocol.
  ///
  /// - Parameter publicKey: The full 32-byte public key of the remote node.
  /// - Returns: Telemetry response containing sensor data and device status.
  /// - Throws: `MeshCoreError` on timeout, device error, or unexpected response.
  func requestTelemetry(from publicKey: Data) async throws -> TelemetryResponse

  /// Requests the neighbor list from a remote node.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the remote node.
  ///   - count: Maximum number of neighbors to return.
  ///   - offset: Starting offset for pagination.
  ///   - orderBy: Sort order (0 = by RSSI).
  ///   - pubkeyPrefixLength: Length of public key prefix to include.
  /// - Returns: Neighbors response containing the list of adjacent nodes.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func requestNeighbours(
    from publicKey: Data,
    count: UInt8,
    offset: UInt16,
    orderBy: UInt8,
    pubkeyPrefixLength: UInt8
  ) async throws -> NeighboursResponse

  /// Returns one message at a time from the device's message queue. Call repeatedly
  /// until ``MessageResult/noMoreMessages`` is returned to drain the queue.
  ///
  /// - Parameter timeout: Optional timeout override in seconds. Uses the session's default timeout when `nil`.
  /// - Returns: A ``MessageResult`` containing the fetched message, if any.
  /// - Throws: `MeshCoreError` if the fetch fails.
  func getMessage(timeout: TimeInterval?) async throws -> MessageResult

  /// Sends a message with automatic retry logic and optional path reset.
  ///
  /// - Parameters:
  ///   - destination: The full 32-byte public key of the recipient.
  ///   - text: The message text to send.
  ///   - timestamp: The message timestamp.
  ///   - maxAttempts: The maximum number of total attempts to make.
  ///   - floodAfter: The number of failed attempts after which to reset the path to flood.
  ///   - maxFloodAttempts: The maximum number of attempts to make while in flood mode.
  ///   - timeout: The acknowledgment timeout per attempt. If `nil`, uses the suggested
  ///              timeout provided by the device.
  /// - Returns: Information about the sent message if an acknowledgment was received,
  ///            otherwise `nil` if all attempts failed.
  /// - Throws: `MeshCoreError/invalidInput` if the destination key is not 32 bytes.
  func sendMessageWithRetry(
    to destination: Data,
    text: String,
    timestamp: Date,
    maxAttempts: Int,
    floodAfter: Int,
    maxFloodAttempts: Int,
    timeout: TimeInterval?
  ) async throws -> MessageSentInfo?

  /// Initiates path discovery to a remote node.
  ///
  /// - Parameter destination: The node's public key (6+ bytes).
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func sendPathDiscovery(to destination: Data) async throws -> MessageSentInfo
}

// MARK: - Default Implementations

public extension RemoteAccessSessionOps {
  /// Sends a command message stamped with the current time.
  func sendCommand(to destination: Data, command: String) async throws -> MessageSentInfo {
    try await sendCommand(to: destination, command: command, timestamp: Date())
  }

  /// Sends a message with retry using the firmware-suggested acknowledgment timeout.
  func sendMessageWithRetry(
    to destination: Data,
    text: String,
    timestamp: Date,
    maxAttempts: Int,
    floodAfter: Int,
    maxFloodAttempts: Int
  ) async throws -> MessageSentInfo? {
    try await sendMessageWithRetry(
      to: destination,
      text: text,
      timestamp: timestamp,
      maxAttempts: maxAttempts,
      floodAfter: floodAfter,
      maxFloodAttempts: maxFloodAttempts,
      timeout: nil
    )
  }
}
