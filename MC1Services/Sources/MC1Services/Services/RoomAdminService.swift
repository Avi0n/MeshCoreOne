import Foundation
import MeshCore
import os

/// Service for room server admin interactions.
/// Handles viewing status/telemetry and sending CLI commands to room servers.
/// Room authentication is handled by `RoomServerService.joinRoom()` via `NodeAuthenticationSheet`.
public actor RoomAdminService {
  // MARK: - Properties

  private let remoteNodeService: RemoteNodeService
  private let dataStore: any PersistenceStoreProtocol
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "RoomAdmin")
  private let auditLogger = CommandAuditLogger()

  private var telemetryResponseHandler: (@Sendable (TelemetryResponse) async -> Void)?
  private var statusResponseHandler: (@Sendable (StatusResponse) async -> Void)?
  private var cliResponseHandler: (@Sendable (ContactMessage, ContactDTO) async -> Void)?

  // MARK: - Initialization

  public init(
    remoteNodeService: RemoteNodeService,
    dataStore: any PersistenceStoreProtocol
  ) {
    self.remoteNodeService = remoteNodeService
    self.dataStore = dataStore
  }

  // MARK: - Status

  /// Request status from a room server.
  public func requestStatus(sessionID: UUID, timeout: Duration? = nil) async throws -> StatusResponse {
    try await remoteNodeService.requestStatus(sessionID: sessionID, timeout: timeout)
  }

  // MARK: - Telemetry

  /// Request telemetry from a room server.
  public func requestTelemetry(sessionID: UUID, timeout: Duration? = nil) async throws -> TelemetryResponse {
    try await remoteNodeService.requestTelemetry(sessionID: sessionID, timeout: timeout)
  }

  // MARK: - CLI Commands

  /// Send a CLI command to a room server and wait for response (admin only).
  /// Uses content-based matching for structured CLI responses.
  public func sendCommand(
    sessionID: UUID,
    command: String,
    timeout: Duration = .seconds(10)
  ) async throws -> String {
    try await remoteNodeService.sendCLICommand(
      sessionID: sessionID,
      command: command,
      timeout: timeout
    )
  }

  /// Send a raw CLI command using FIFO response matching (admin only).
  public func sendRawCommand(
    sessionID: UUID,
    command: String,
    timeout: Duration = .seconds(10)
  ) async throws -> String {
    try await remoteNodeService.sendRawCLICommand(
      sessionID: sessionID,
      command: command,
      timeout: timeout
    )
  }

  // MARK: - Session Queries

  /// Fetch all room admin sessions for a device.
  public func fetchRoomAdminSessions(radioID: UUID) async throws -> [RemoteNodeSessionDTO] {
    let sessions = try await dataStore.fetchRemoteNodeSessions(radioID: radioID)
    return sessions.filter(\.isRoom)
  }

  /// Check if a contact is a known room with an active session.
  public func getConnectedSession(publicKeyPrefix: Data) async throws -> RemoteNodeSessionDTO? {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSessionByPrefix(publicKeyPrefix),
          remoteSession.isRoom, remoteSession.isConnected else {
      return nil
    }
    return remoteSession
  }

  // MARK: - Handler Invocation

  /// Invoke the status response handler safely from actor context
  public func invokeStatusHandler(_ status: StatusResponse) async {
    await auditLogger.logStatusResponse(
      target: .room,
      publicKey: status.publicKeyPrefix,
      batteryMv: status.batteryMillivolts,
      uptimeSec: status.uptimeSeconds
    )

    guard let handler = statusResponseHandler else {
      let prefixHex = status.publicKeyPrefix.map { String(format: "%02x", $0) }.joined()
      logger.debug("No status handler registered for room response from \(prefixHex), ignoring")
      return
    }
    await handler(status)
  }

  /// Invoke the telemetry response handler safely from actor context
  public func invokeTelemetryHandler(_ response: TelemetryResponse) async {
    await auditLogger.logTelemetryResponse(
      target: .room,
      publicKey: response.publicKeyPrefix,
      pointCount: response.dataPoints.count
    )

    guard let handler = telemetryResponseHandler else {
      logger.debug("No telemetry handler registered for room, ignoring response")
      return
    }
    await handler(response)
  }

  /// Invoke the CLI response handler safely from actor context
  public func invokeCLIHandler(_ message: ContactMessage, fromContact contact: ContactDTO) async {
    await auditLogger.logCLIResponse(publicKey: contact.publicKey, response: message.text)

    guard let handler = cliResponseHandler else {
      logger.debug("No CLI handler registered for room, ignoring response from \(contact.displayName)")
      return
    }
    await handler(message, contact)
  }

  // MARK: - Handler Setters

  public func setStatusHandler(_ handler: @escaping @Sendable (StatusResponse) async -> Void) {
    statusResponseHandler = handler
  }

  public func setTelemetryHandler(_ handler: @escaping @Sendable (TelemetryResponse) async -> Void) {
    telemetryResponseHandler = handler
  }

  public func setCLIHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO) async -> Void) {
    cliResponseHandler = handler
  }

  /// Clear all handlers (called when view disappears)
  public func clearHandlers() {
    statusResponseHandler = nil
    telemetryResponseHandler = nil
    cliResponseHandler = nil
  }

  /// Clears only the status-surface handlers so the merged admin surface can tear down its
  /// status segment without dropping the settings VM's CLI handler on the shared per-connection service.
  public func clearStatusHandlers() {
    statusResponseHandler = nil
    telemetryResponseHandler = nil
  }
}
