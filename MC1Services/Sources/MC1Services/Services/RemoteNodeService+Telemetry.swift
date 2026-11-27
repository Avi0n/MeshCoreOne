import Foundation
import MeshCore

public extension RemoteNodeService {
  // MARK: - Status

  /// Request status from a remote node.
  func requestStatus(
    sessionID: UUID,
    priority: MeshRequestPriority,
    onTimeoutKnown: (@Sendable (Int) async -> Void)? = nil
  ) async throws -> StatusResponse {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    guard !remoteSession.isSensor else { throw RemoteNodeError.invalidResponse }

    let targetType = CommandAuditLogger.target(for: remoteSession.role)
    await auditLogger.logStatusRequest(target: targetType, publicKey: remoteSession.publicKey)

    do {
      let contactType: ContactType = remoteSession.isRoom ? .room : .repeater
      return try await session.requestStatus(
        from: remoteSession.publicKey,
        type: contactType,
        priority: priority,
        onTimeoutKnown: onTimeoutKnown.map { callback in
          { @Sendable seconds in
            let roundedSeconds = max(1, Int(seconds.rounded(.up)))
            await callback(roundedSeconds)
          }
        }
      )
    } catch let error as MeshCoreError {
      if case .timeout = error {
        throw RemoteNodeError.timeout
      }
      throw RemoteNodeError.sessionError(error)
    }
  }

  // MARK: - Telemetry

  /// Request telemetry from a remote node
  func requestTelemetry(sessionID: UUID, priority: MeshRequestPriority) async throws -> TelemetryResponse {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    let targetType = CommandAuditLogger.target(for: remoteSession.role)
    await auditLogger.logTelemetryRequest(target: targetType, publicKey: remoteSession.publicKey)

    do {
      return try await session.requestTelemetry(from: remoteSession.publicKey, priority: priority)
    } catch let error as MeshCoreError {
      if case .timeout = error {
        throw RemoteNodeError.timeout
      }
      throw RemoteNodeError.sessionError(error)
    }
  }

  // MARK: - Owner Info

  /// Request owner info from a repeater using binary protocol.
  func requestOwnerInfo(
    sessionID: UUID,
    priority: MeshRequestPriority,
    onTimeoutKnown: (@Sendable (Int) async -> Void)? = nil
  ) async throws -> OwnerInfoResponse {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    guard !remoteSession.isSensor else { throw RemoteNodeError.invalidResponse }

    do {
      return try await session.requestOwnerInfo(
        from: remoteSession.publicKey,
        priority: priority,
        onTimeoutKnown: onTimeoutKnown.map { callback in
          { @Sendable seconds in
            let roundedSeconds = max(1, Int(seconds.rounded(.up)))
            await callback(roundedSeconds)
          }
        }
      )
    } catch let error as MeshCoreError {
      if case .timeout = error {
        throw RemoteNodeError.timeout
      }
      throw RemoteNodeError.sessionError(error)
    }
  }
}
