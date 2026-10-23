import Foundation

extension MeshCoreSession {
  /// stop() can leave the transport connected for the next session, so a stopped session does not write.
  func sendSessionData(
    _ data: Data,
    beforeSend: @Sendable () throws -> Void = {}
  ) async throws {
    guard sessionIsRunning else { throw CancellationError() }
    try beforeSend()
    try await transport.send(data)
  }

  func sendSessionDataWithoutResponse(_ data: Data) async throws {
    guard sessionIsRunning else { throw CancellationError() }
    try await transport.sendWithoutResponse(data)
  }
}
