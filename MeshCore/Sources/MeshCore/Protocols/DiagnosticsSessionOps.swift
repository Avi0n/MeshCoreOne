import Foundation

/// Session operations for binary-protocol diagnostics against remote nodes:
/// status, telemetry, neighbours, ACL, MMA, traces, and path discovery.
public protocol DiagnosticsSessionOps: Actor {
  /// Requests status information from a remote node using the repeater status layout.
  ///
  /// - Parameter publicKey: The full 32-byte public key of the remote node.
  /// - Returns: A status response containing battery, uptime, and other metrics.
  /// - Throws: `MeshCoreError` on timeout, device error, or unexpected response.
  func requestStatus(from publicKey: Data) async throws -> StatusResponse

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

  /// Fetches all neighbors from a remote node with automatic pagination.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the remote node.
  ///   - orderBy: Sort order (0 = by RSSI).
  ///   - pubkeyPrefixLength: Length of public key prefix to include.
  /// - Returns: Complete neighbors response with all neighbors.
  /// - Throws: `MeshCoreError` on timeout or invalid response.
  func fetchAllNeighbours(
    from publicKey: Data,
    orderBy: UInt8,
    pubkeyPrefixLength: UInt8
  ) async throws -> NeighboursResponse

  /// Requests Min-Max-Average (MMA) data for a time range.
  ///
  /// - Parameters:
  ///   - publicKey: The full 32-byte public key of the remote node.
  ///   - start: Start of the time range.
  ///   - end: End of the time range.
  /// - Returns: MMA response containing aggregated statistics.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func requestMMA(from publicKey: Data, start: Date, end: Date) async throws -> MMAResponse

  /// Requests the Access Control List (ACL) from a remote node.
  ///
  /// - Parameter publicKey: The full 32-byte public key of the remote node.
  /// - Returns: ACL response containing authorized public keys.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func requestACL(from publicKey: Data) async throws -> ACLResponse

  /// Retrieves telemetry data from the local device.
  ///
  /// - Returns: Device telemetry including battery, temperature, and sensor data.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func getSelfTelemetry() async throws -> TelemetryResponse

  /// Initiates path discovery to a remote node.
  ///
  /// - Parameter destination: The node's public key (6+ bytes).
  /// - Returns: Information about the sent message, including the expected ACK code.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func sendPathDiscovery(to destination: Data) async throws -> MessageSentInfo

  /// Sends a trace packet through the mesh network.
  ///
  /// - Parameters:
  ///   - tag: Optional trace identifier. Random value generated if nil.
  ///   - authCode: Optional authentication code. Random value generated if nil.
  ///   - flags: Trace flags controlling behavior.
  ///   - path: Initial path to follow; firmware requires at least one path byte.
  /// - Returns: Information about the sent message, including tag and auth code.
  /// - Throws: `MeshCoreError` on invalid input or timeout.
  func sendTrace(
    tag: UInt32?,
    authCode: UInt32?,
    flags: UInt8,
    path: Data?
  ) async throws -> MessageSentInfo
}
