import Foundation

/// Session operations for binary-protocol diagnostics against remote nodes:
/// status, telemetry, neighbours, ACL, MMA, traces, and path discovery.
public protocol DiagnosticsSessionOps: Actor {
  /// Requests status information from a remote node using the repeater status layout.
  func requestStatus(from publicKey: Data, priority: MeshRequestPriority) async throws -> StatusResponse

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

  /// Fetches all neighbors from a remote node with automatic pagination.
  func fetchAllNeighbours(
    from publicKey: Data,
    orderBy: UInt8,
    pubkeyPrefixLength: UInt8,
    priority: MeshRequestPriority
  ) async throws -> NeighboursResponse

  /// Requests Min-Max-Average (MMA) data for a time range.
  func requestMMA(from publicKey: Data, start: Date, end: Date, priority: MeshRequestPriority) async throws -> MMAResponse

  /// Requests the Access Control List (ACL) from a remote node.
  func requestACL(from publicKey: Data, priority: MeshRequestPriority) async throws -> ACLResponse

  /// Retrieves telemetry data from the local device.
  func getSelfTelemetry() async throws -> TelemetryResponse

  /// Discovers a path to a remote node once and awaits its `PathInfo` reply.
  func discoverPath(
    to destination: Data,
    priority: MeshRequestPriority,
    budget: @escaping @Sendable (MessageSentInfo) -> TimeInterval,
    onTimeoutKnown: (@Sendable (TimeInterval) async -> Void)?
  ) async throws -> PathInfo

  /// Sends a trace packet through the mesh network.
  func sendTrace(
    tag: UInt32?,
    authCode: UInt32?,
    flags: UInt8,
    path: Data?
  ) async throws -> MessageSentInfo
}
