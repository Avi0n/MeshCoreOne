import MC1Services

@MainActor
extension WatchlistRefreshCoordinator.Operations {
  init(services: ServiceContainer) {
    let remote = services.remoteNodeService
    let admin = services.repeaterAdminService
    let contacts = services.contactService
    self.init(
      savedPassword: { await remote.retrievePassword(forContact: $0) },
      connectedSession: { try await admin.getConnectedSession(publicKeyPrefix: $0) },
      createSession: { try await remote.createSession(radioID: $0.radioID, contact: $0) },
      logIn: {
        try await remote.login(
          sessionID: $0,
          password: $1,
          priority: .background,
          pathLength: $2,
          samePasswordExemption: $3,
          onTimeoutKnown: $4
        )
      },
      requestStatus: { try await admin.requestStatus(sessionID: $0, priority: .background, onTimeoutKnown: $1) },
      requestOwnerInfo: { try await admin.requestOwnerInfo(sessionID: $0, priority: .background, onTimeoutKnown: $1) },
      attemptFloodFallback: { contact, callerError, operation in
        try await contacts.attemptFloodFallback(
          radioID: contact.radioID,
          publicKey: contact.publicKey,
          rethrowIfUnavailable: callerError,
          operation: operation
        )
      }
    )
  }
}
