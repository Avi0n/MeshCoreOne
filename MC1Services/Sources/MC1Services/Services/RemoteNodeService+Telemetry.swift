import Foundation
import MeshCore

public extension RemoteNodeService {
  // MARK: - Status

  /// Request status from a remote node.
  func requestStatus(sessionID: UUID, timeout: Duration? = nil) async throws -> StatusResponse {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    // Log status request
    let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
    await auditLogger.logStatusRequest(target: targetType, publicKey: remoteSession.publicKey)

    do {
      let effectiveTimeout = timeout ?? RemoteOperationTimeoutPolicy.binaryMaximum
      return try await withTimeout(effectiveTimeout, operationName: "remoteStatus") {
        let contactType: ContactType = remoteSession.isRoom ? .room : .repeater
        return try await self.session.requestStatus(from: remoteSession.publicKey, type: contactType)
      }
    } catch is TimeoutError {
      throw RemoteNodeError.timeout
    } catch let error as MeshCoreError {
      throw RemoteNodeError.sessionError(error)
    }
  }

  // MARK: - Telemetry

  /// Request telemetry from a remote node
  func requestTelemetry(sessionID: UUID, timeout: Duration? = nil) async throws -> TelemetryResponse {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    // Log telemetry request
    let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
    await auditLogger.logTelemetryRequest(target: targetType, publicKey: remoteSession.publicKey)

    do {
      let effectiveTimeout = timeout ?? RemoteOperationTimeoutPolicy.binaryMaximum
      return try await withTimeout(effectiveTimeout, operationName: "remoteTelemetry") {
        try await self.session.requestTelemetry(from: remoteSession.publicKey)
      }
    } catch is TimeoutError {
      throw RemoteNodeError.timeout
    } catch let error as MeshCoreError {
      throw RemoteNodeError.sessionError(error)
    }
  }

  // MARK: - Owner Info

  /// Request owner info from a repeater using binary protocol.
  func requestOwnerInfo(sessionID: UUID, timeout: Duration? = nil) async throws -> OwnerInfoResponse {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    do {
      let effectiveTimeout = timeout ?? RemoteOperationTimeoutPolicy.binaryMaximum
      return try await withTimeout(effectiveTimeout, operationName: "remoteOwnerInfo") {
        try await self.session.requestOwnerInfo(from: remoteSession.publicKey)
      }
    } catch is TimeoutError {
      throw RemoteNodeError.timeout
    } catch let error as MeshCoreError {
      throw RemoteNodeError.sessionError(error)
    }
  }
}
