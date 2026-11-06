import Foundation

extension MeshCoreSession {
  /// stop() can leave the transport connected for the next session, so a stopped session does not write.
  func sendSessionData(
    _ data: Data,
    beforeSend: @Sendable () throws -> Void = {},
    identityImport: Bool = false
  ) async throws {
    guard !identityImportInProgress || identityImport else { throw CancellationError() }
    guard sessionIsRunning, !floodScopeRecoveryRequired else { throw CancellationError() }
    try beforeSend()
    do {
      try await transport.send(data)
    } catch MeshTransportError.cancelledBeforeSubmission {
      throw CancellationError()
    } catch {
      if !Self.linkRejectedWriteBeforeSubmission(error) {
        await requestResponseSerializer.noteUncertainResponseHistory()
      }
      throw error
    }
  }

  func sendSessionDataWithoutResponse(_ data: Data) async throws {
    guard !identityImportInProgress else { throw CancellationError() }
    guard sessionIsRunning, !floodScopeRecoveryRequired else { throw CancellationError() }
    do {
      try await transport.sendWithoutResponse(data)
    } catch MeshTransportError.cancelledBeforeSubmission {
      throw CancellationError()
    } catch {
      if !Self.linkRejectedWriteBeforeSubmission(error) {
        await requestResponseSerializer.noteUncertainResponseHistory()
      }
      throw error
    }
  }

  /// A frame rejected before submission cannot leave a pending host reply.
  static func linkRejectedWriteBeforeSubmission(_ error: Error) -> Bool {
    switch error {
    case MeshTransportError.notConnected,
         MeshCoreError.notConnected,
         MeshCoreError.bluetoothUnavailable,
         MeshCoreError.bluetoothUnauthorized,
         MeshCoreError.bluetoothPoweredOff,
         MeshCoreError.sessionNotStarted:
      return true
    default:
      if case MeshCoreError.connectionLost = error { return true }
      return false
    }
  }
}
