import Foundation
import MeshCore
import os

// MARK: - Binary Protocol Errors

public enum BinaryProtocolError: Error, Sendable {
  case notConnected
  case sendFailed
  case timeout
  case invalidResponse
  case sessionError(MeshCoreError)
}

extension BinaryProtocolError: LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .notConnected: "Not connected to device."
    case .sendFailed: "Failed to send request."
    case .timeout: "Request timed out."
    case .invalidResponse: "Invalid response from device."
    case let .sessionError(e): e.localizedDescription
    }
  }
}

// MARK: - Binary Protocol Service

/// Service for binary protocol operations with remote mesh nodes.
/// Handles status, telemetry, neighbours, and ACL requests via MeshCore session.
public actor BinaryProtocolService {
  // MARK: - Properties

  private let session: any DiagnosticsSessionOps & SessionEventStreaming & ContactSessionOps
  private let dataStore: any PersistenceStoreProtocol
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "BinaryProtocol")

  /// Handler for status responses (from push notifications)
  private var statusResponseHandler: (@Sendable (StatusResponse) async -> Void)?

  /// Handler for telemetry responses (from push notifications)
  private var telemetryResponseHandler: (@Sendable (TelemetryResponse) async -> Void)?

  /// Handler for neighbours responses (from push notifications)
  private var neighboursResponseHandler: (@Sendable (NeighboursResponse) async -> Void)?

  /// Event monitoring task
  private var eventMonitorTask: Task<Void, Never>?

  // MARK: - Initialization

  public init(
    session: any DiagnosticsSessionOps & SessionEventStreaming & ContactSessionOps,
    dataStore: any PersistenceStoreProtocol
  ) {
    self.session = session
    self.dataStore = dataStore
  }

  deinit {
    eventMonitorTask?.cancel()
  }

  // MARK: - Event Handlers

  /// Set handler for status responses
  public func setStatusResponseHandler(_ handler: @escaping @Sendable (StatusResponse) async -> Void) {
    statusResponseHandler = handler
  }

  /// Set handler for telemetry responses
  public func setTelemetryResponseHandler(_ handler: @escaping @Sendable (TelemetryResponse) async -> Void) {
    telemetryResponseHandler = handler
  }

  /// Set handler for neighbours responses
  public func setNeighboursResponseHandler(_ handler: @escaping @Sendable (NeighboursResponse) async -> Void) {
    neighboursResponseHandler = handler
  }

  // MARK: - Event Monitoring

  /// Start monitoring MeshCore events for binary protocol responses
  public func startEventMonitoring() {
    eventMonitorTask?.cancel()

    eventMonitorTask = Task { [weak self] in
      guard let self else { return }
      let events = await session.events()

      for await event in events {
        guard !Task.isCancelled else { break }
        await handleEvent(event)
      }
    }
  }

  /// Stop monitoring events
  public func stopEventMonitoring() {
    eventMonitorTask?.cancel()
    eventMonitorTask = nil
  }

  /// Handle incoming MeshCore event
  private func handleEvent(_ event: MeshEvent) async {
    switch event {
    case let .statusResponse(response):
      await statusResponseHandler?(response)

    case let .telemetryResponse(response):
      await telemetryResponseHandler?(response)

    case let .neighboursResponse(response):
      await neighboursResponseHandler?(response)

    default:
      break
    }
  }

  // MARK: - Status Request

  /// Request status from a remote node (blocking, waits for response)
  /// - Parameter publicKey: The remote node's full 32-byte public key
  /// - Returns: StatusResponse with device stats
  public func requestStatus(from publicKey: Data) async throws -> StatusResponse {
    try await performWithPathResetOnTimeout(publicKey: publicKey, operationName: "status") {
      try await self.session.requestStatus(from: publicKey)
    }
  }

  /// Request status from a remote node (blocking, waits for response)
  /// - Parameters:
  ///   - publicKey: The remote node's full 32-byte public key
  ///   - type: The target node type used to select the correct firmware status layout
  /// - Returns: StatusResponse with device stats
  public func requestStatus(
    from publicKey: Data,
    type: ContactType
  ) async throws -> StatusResponse {
    try await performWithPathResetOnTimeout(publicKey: publicKey, operationName: "status") {
      try await self.session.requestStatus(from: publicKey, type: type)
    }
  }

  // MARK: - Telemetry Request

  /// Request telemetry from a remote node (blocking, waits for response)
  /// - Parameter publicKey: The remote node's public key (full or prefix)
  /// - Returns: TelemetryResponse with sensor data
  public func requestTelemetry(from publicKey: Data) async throws -> TelemetryResponse {
    try await performWithPathResetOnTimeout(publicKey: publicKey, operationName: "telemetry") {
      try await self.session.requestTelemetry(from: publicKey)
    }
  }

  // MARK: - Neighbours Request

  /// Default pubkey prefix length for neighbour queries.
  /// Stored to ensure response parsing uses matching length.
  public static let defaultPubkeyPrefixLength: UInt8 = 6

  /// Request neighbours list from a remote node (blocking, waits for response)
  /// - Parameters:
  ///   - publicKey: The remote node's public key
  ///   - count: Maximum number of neighbours to return (default 255 = all)
  ///   - offset: Pagination offset
  ///   - orderBy: Sort order for results (0 = newest first)
  ///   - pubkeyPrefixLength: Length of public key prefix in response
  /// - Returns: NeighboursResponse with neighbour list
  public func requestNeighbours(
    from publicKey: Data,
    count: UInt8 = 255,
    offset: UInt16 = 0,
    orderBy: UInt8 = 0,
    pubkeyPrefixLength: UInt8 = defaultPubkeyPrefixLength
  ) async throws -> NeighboursResponse {
    try await performWithPathResetOnTimeout(publicKey: publicKey, operationName: "neighbours") {
      try await self.session.requestNeighbours(
        from: publicKey,
        count: count,
        offset: offset,
        orderBy: orderBy,
        pubkeyPrefixLength: pubkeyPrefixLength
      )
    }
  }

  /// Fetch all neighbours from a remote node with automatic pagination
  /// - Parameters:
  ///   - publicKey: The remote node's public key
  ///   - orderBy: Sort order for results
  ///   - pubkeyPrefixLength: Length of public key prefix in response
  /// - Returns: NeighboursResponse with complete neighbour list
  public func fetchAllNeighbours(
    from publicKey: Data,
    orderBy: UInt8 = 0,
    pubkeyPrefixLength: UInt8 = defaultPubkeyPrefixLength
  ) async throws -> NeighboursResponse {
    try await performWithPathResetOnTimeout(publicKey: publicKey, operationName: "neighbours") {
      try await self.session.fetchAllNeighbours(
        from: publicKey,
        orderBy: orderBy,
        pubkeyPrefixLength: pubkeyPrefixLength
      )
    }
  }

  // MARK: - MMA Request

  /// Request min/max/average telemetry data from a remote node
  /// - Parameters:
  ///   - publicKey: The remote node's public key
  ///   - start: Start of time range
  ///   - end: End of time range
  /// - Returns: MMAResponse with aggregated telemetry
  public func requestMMA(
    from publicKey: Data,
    start: Date,
    end: Date
  ) async throws -> MMAResponse {
    try await performWithPathResetOnTimeout(publicKey: publicKey, operationName: "mma") {
      try await self.session.requestMMA(from: publicKey, start: start, end: end)
    }
  }

  // MARK: - Direct-path flood recovery

  /// On mesh timeout, calls `resetPath` and retries once. Always resets because
  /// this path has no radio-scoped contact to check for flood routing.
  private func performWithPathResetOnTimeout<T: Sendable>(
    publicKey: Data,
    operationName: String,
    operation: () async throws -> T
  ) async throws -> T {
    do {
      return try await operation()
    } catch let error as MeshCoreError {
      guard case .timeout = error else { throw BinaryProtocolError.sessionError(error) }
      logger.info(
        "\(operationName): mesh timeout; resetting path to flood and retrying once"
      )
      let firstTimeout = error
      do {
        try await session.resetPath(publicKey: publicKey)
      } catch is CancellationError {
        throw CancellationError()
      } catch {
        logger.warning(
          "\(operationName): path reset failed (\(error.localizedDescription)); not retrying"
        )
        throw BinaryProtocolError.sessionError(firstTimeout)
      }
      do {
        return try await operation()
      } catch let retryError as MeshCoreError {
        throw BinaryProtocolError.sessionError(retryError)
      }
    }
  }

  // MARK: - ACL Request

  /// Request access control list from a remote node
  /// - Parameter publicKey: The remote node's public key
  /// - Returns: ACLResponse with permission entries
  public func requestACL(from publicKey: Data) async throws -> ACLResponse {
    try await performWithPathResetOnTimeout(publicKey: publicKey, operationName: "acl") {
      try await self.session.requestACL(from: publicKey)
    }
  }

  // MARK: - Self Telemetry

  /// Get telemetry from the local device
  /// - Returns: TelemetryResponse with local sensor data
  public func getSelfTelemetry() async throws -> TelemetryResponse {
    do {
      return try await session.getSelfTelemetry()
    } catch let error as MeshCoreError {
      throw BinaryProtocolError.sessionError(error)
    }
  }

  // MARK: - Path Discovery

  /// Send path discovery request to a contact
  /// - Parameter publicKey: The contact's public key
  /// - Returns: MessageSentInfo with expected ACK code
  public func sendPathDiscovery(to publicKey: Data) async throws -> MessageSentInfo {
    do {
      return try await session.sendPathDiscovery(to: publicKey)
    } catch let error as MeshCoreError {
      throw BinaryProtocolError.sessionError(error)
    }
  }

  // MARK: - Trace Route

  /// Send a trace route request
  /// - Parameters:
  ///   - tag: Optional trace tag (random if nil)
  ///   - authCode: Optional auth code (random if nil)
  ///   - flags: Trace flags
  ///   - path: Optional fixed path to trace
  /// - Returns: MessageSentInfo with expected ACK code
  public func sendTrace(
    tag: UInt32? = nil,
    authCode: UInt32? = nil,
    flags: UInt8 = 0,
    path: Data? = nil
  ) async throws -> MessageSentInfo {
    do {
      return try await session.sendTrace(tag: tag, authCode: authCode, flags: flags, path: path)
    } catch let error as MeshCoreError {
      throw BinaryProtocolError.sessionError(error)
    }
  }
}
