import MC1Services

@MainActor
extension WatchlistRefreshCoordinator.Operations {
  init(services: ServiceContainer) {
    let remote = services.remoteNodeService
    let admin = services.repeaterAdminService
    let contacts = services.contactService
    self.init(
      savedPassword: { await remote.retrievePassword(forContact: $0) },
      createSession: { try await remote.createSession(radioID: $0.radioID, contact: $0) },
      logIn: { try await remote.login(sessionID: $0, password: $1, pathLength: $2) },
      resetPath: { try await contacts.resetPath(radioID: $0.radioID, publicKey: $0.publicKey) },
      requestStatus: { try await admin.requestStatus(sessionID: $0) },
      requestOwnerInfo: { try await admin.requestOwnerInfo(sessionID: $0) }
    )
  }
}
