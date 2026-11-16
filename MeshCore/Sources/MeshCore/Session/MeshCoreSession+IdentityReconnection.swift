import Foundation

public extension MeshCoreSession {
  /// Stops ordinary commands before the owner joins workers that could issue them.
  func prepareForIdentityImport() async throws {
    guard !identityImportInProgress else { throw CancellationError() }
    try await requestResponseSerializer.requireIdentityImportResponseAttribution()
    identityImportInProgress = true
  }

  /// Closes ordinary command admission while identity and SelfInfo change together.
  /// The owner replaces this session after the authenticated observation settles.
  func importPrivateKeyForReconnection(
    _ key: Data, binding: SessionTransportBinding, onAcknowledged: @escaping @Sendable () -> Void
  ) async throws -> SelfInfo {
    guard key.count == PacketBuilder.privateKeySize else {
      throw MeshCoreError.invalidInput("Full \(PacketBuilder.privateKeySize)-byte private key required for importPrivateKey")
    }
    guard identityImportInProgress, !identityImportHasStarted else { throw CancellationError() }
    identityImportHasStarted = true
    return try await requestResponseSerializer.withSerialization { [self] in
      defer { binding.revoke() }
      await binding.waitForQuiescence()
      try await requestResponseSerializer.requireIdentityImportResponseAttribution()
      do {
        let accepted: Bool = try await sendAndMatchHoldingSerialization(
          PacketBuilder.importPrivateKey(key), beforeSend: { try binding.checkActive() }, identityImport: true
        ) { event in
          switch event {
          case .ok(value: nil): .success(true)
          case .disabled: .success(false)
          case let .error(code?): .failure(.deviceError(code: code))
          default: .ignore
          }
        }
        guard accepted else { throw IdentityImportReconnectionError.rejected(.featureDisabled) }
      } catch let failure as MeshCoreError {
        if case .deviceError = failure { throw IdentityImportReconnectionError.rejected(failure) }
        throw failure
      }
      onAcknowledged()
      let info: SelfInfo = try await sendAndMatchHoldingSerialization(
        PacketBuilder.appStart(clientId: configuration.clientIdentifier),
        beforeSend: { try binding.checkActive() }, identityImport: true
      ) { event in
        if case let .selfInfo(info) = event { return .success(info) }
        return .ignore
      }
      await cacheImportedSelfInfo(info)
      return info
    }
  }

  private func cacheImportedSelfInfo(_ info: SelfInfo) {
    selfInfo = info
  }
}
