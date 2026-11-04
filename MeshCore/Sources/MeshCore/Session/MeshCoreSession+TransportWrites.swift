import Foundation

extension MeshCoreSession {
  /// stop() can leave the transport connected for the next session, so a stopped session does not write.
  func sendSessionData(
    _ data: Data,
    beforeSend: @Sendable () throws -> Void = {},
    identityImport: Bool = false
  ) async throws {
    guard !identityImportInProgress || identityImport else { throw CancellationError() }
    guard sessionIsRunning else { throw CancellationError() }
    try beforeSend()
    do {
      try await transport.send(data)
    } catch MeshTransportError.cancelledBeforeSubmission {
      throw CancellationError()
    } catch {
      await requestResponseSerializer.noteUncertainResponseHistory()
      throw error
    }
  }

  func sendSessionDataWithoutResponse(_ data: Data) async throws {
    guard !identityImportInProgress else { throw CancellationError() }
    guard sessionIsRunning else { throw CancellationError() }
    do {
      try await transport.sendWithoutResponse(data)
    } catch MeshTransportError.cancelledBeforeSubmission {
      throw CancellationError()
    } catch {
      await requestResponseSerializer.noteUncertainResponseHistory()
      throw error
    }
  }
}
