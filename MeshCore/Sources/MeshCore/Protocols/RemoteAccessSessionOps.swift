import Foundation

/// Session operations for authenticated access to remote nodes (room servers
/// and repeaters): login, commands, keep-alive, and remote queries.
public protocol RemoteAccessSessionOps: Actor {
  /// Authenticates with a remote node and awaits its reply through the mesh request gate.
  func login(
    to destination: Data,
    password: String,
    priority: MeshRequestPriority,
    budget: @escaping @Sendable (MessageSentInfo) -> TimeInterval,
    onTimeoutKnown: (@Sendable (TimeInterval) async -> Void)?,
    samePasswordExemption: Bool
  ) async throws -> LoginExchangeResult

  /// Sends a logout request to a remote node.
  func sendLogout(to destination: Data) async throws

  /// Sends a command message to a remote node.
  func sendCommand(
    to destination: Data,
    command: String,
    textType: TextType,
    timestamp: Date
  ) async throws -> MessageSentInfo

  /// Resolves the room bookmark after serializing the request and checks ownership just before sending.
  func sendKeepAlive(
    to publicKey: Data,
    syncSince: @escaping @Sendable () async throws -> UInt32,
    beforeSend: @escaping @Sendable () throws -> Void
  ) async throws -> MessageSentInfo

  /// Requests owner information from a repeater using the binary protocol.
  func requestOwnerInfo(from publicKey: Data, priority: MeshRequestPriority) async throws -> OwnerInfoResponse

  /// Requests status information from a remote node.
  func requestStatus(from publicKey: Data, type: ContactType, priority: MeshRequestPriority) async throws -> StatusResponse

  /// Requests telemetry data from a remote node using the binary protocol.
  func requestTelemetry(from publicKey: Data, priority: MeshRequestPriority) async throws -> TelemetryResponse

  /// Requests the neighbor list from a remote node.
  func requestNeighbours(
    from publicKey: Data,
    count: UInt8,
    offset: UInt16,
    orderBy: UInt8,
    pubkeyPrefixLength: UInt8,
    priority: MeshRequestPriority
  ) async throws -> NeighboursResponse

  /// Returns one message at a time from the device's message queue.
  func getMessage(timeout: TimeInterval?) async throws -> MessageResult

  /// Sends a message with automatic retry logic and optional path reset.
  func sendMessageWithRetry(
    to destination: Data,
    text: String,
    timestamp: Date,
    maxAttempts: Int,
    floodAfter: Int,
    maxFloodAttempts: Int,
    timeout: TimeInterval?
  ) async throws -> MessageSentInfo?

  /// Discovers a path to a remote node once and awaits its `PathInfo` reply.
  func discoverPath(
    to destination: Data,
    priority: MeshRequestPriority,
    budget: @escaping @Sendable (MessageSentInfo) -> TimeInterval,
    onTimeoutKnown: (@Sendable (TimeInterval) async -> Void)?
  ) async throws -> PathInfo
}

// MARK: - Default Implementations

public extension RemoteAccessSessionOps {
  func sendKeepAlive(to publicKey: Data, syncSince: UInt32) async throws -> MessageSentInfo {
    try await sendKeepAlive(to: publicKey, syncSince: { syncSince }, beforeSend: {})
  }

  /// Sends a command message stamped with the current time.
  func sendCommand(
    to destination: Data,
    command: String,
    textType: TextType
  ) async throws -> MessageSentInfo {
    try await sendCommand(to: destination, command: command, textType: textType, timestamp: Date())
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
