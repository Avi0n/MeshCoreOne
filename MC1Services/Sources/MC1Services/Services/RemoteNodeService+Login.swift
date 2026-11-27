import Foundation
import MeshCore

private let loginPublicKeyPrefixLength = 6

public extension RemoteNodeService {
  // MARK: - Login

  /// Login to a remote node.
  /// Works for sensors, repeaters, and room servers.
  func login(
    sessionID: UUID,
    password: String? = nil,
    priority: MeshRequestPriority,
    pathLength: UInt8 = 0,
    samePasswordExemption: Bool = false,
    onTimeoutKnown: (@Sendable (Int) async -> Void)? = nil
  ) async throws -> LoginResult {
    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    let pwd: String
    if let password {
      pwd = password
    } else if let stored = try await keychainService.retrievePassword(forNodeKey: remoteSession.publicKey) {
      pwd = stored
    } else {
      throw RemoteNodeError.passwordNotFound
    }

    guard pwd.utf8.count <= ProtocolLimits.maxRemotePasswordBytes else { throw RemoteNodeError.invalidPasswordLength }
    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }

    let prefix = Data(remoteSession.publicKey.prefix(loginPublicKeyPrefixLength))
    let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
    let targetType = CommandAuditLogger.target(for: remoteSession.role)
    await auditLogger.logLoginRequest(target: targetType, publicKey: remoteSession.publicKey, pathLength: pathLength)

    let exchange: LoginExchangeResult
    do {
      exchange = try await loginHealingIfNeeded(
        publicKey: remoteSession.publicKey,
        radioID: remoteSession.radioID,
        password: pwd,
        priority: priority,
        pathLength: pathLength,
        samePasswordExemption: samePasswordExemption,
        onTimeoutKnown: onTimeoutKnown.map { callback in
          { @Sendable seconds in
            let roundedSeconds = max(1, Int(seconds.rounded(.up)))
            await callback(roundedSeconds)
          }
        }
      )
    } catch is CancellationError {
      throw CancellationError()
    } catch let error as MeshCoreError {
      if case .timeout = error {
        logger.warning("Login timeout for session \(remoteSession.id), prefix \(prefixHex)")
        throw RemoteNodeError.timeout
      }
      throw RemoteNodeError.sessionError(error)
    }

    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }
    guard let info = exchange.info, exchange.succeeded else {
      await auditLogger.logLoginFailed(target: targetType, publicKey: prefix, reason: "authentication failed")
      throw RemoteNodeError.loginFailed("authentication failed")
    }

    await auditLogger.logLoginSuccess(target: targetType, publicKey: prefix, isAdmin: info.isAdmin)
    let result = LoginResult(
      success: true,
      isAdmin: info.isAdmin,
      aclPermissions: info.permissions,
      publicKeyPrefix: info.publicKeyPrefix,
      serverTime: info.serverTime,
      firmwareLevel: info.firmwareLevel,
      mayAnswerEarlierLogin: exchange.mayAnswerEarlierLogin
    )

    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }
    try await dataStore.updateRemoteNodeSessionConnection(
      id: remoteSession.id,
      isConnected: true,
      permissionLevel: result.permissionLevel(for: remoteSession.role)
    )
    if Task.isCancelled || isRetired {
      try? await dataStore.markSessionDisconnected(remoteSession.id)
      if Task.isCancelled {
        throw CancellationError()
      }
      throw RemoteNodeError.notConnected
    }

    eventBroadcaster.yield(.sessionStateChanged(sessionID: remoteSession.id, isConnected: true))
    if remoteSession.isRoom { keepAliveIntervals[remoteSession.id] = Self.defaultKeepAliveInterval }
    return result
  }

  /// Sends login via `MeshCoreSession.login`; if the radio reports `notFound` on the first write,
  /// pushes the local contact with flood routing and retries once.
  internal func loginHealingIfNeeded(
    publicKey: Data,
    radioID: UUID,
    password: String,
    priority: MeshRequestPriority = .interactive,
    pathLength: UInt8 = 0,
    samePasswordExemption: Bool = false,
    onTimeoutKnown: (@Sendable (TimeInterval) async -> Void)? = nil
  ) async throws -> LoginExchangeResult {
    let budget: @Sendable (MessageSentInfo) -> TimeInterval = { sentInfo in
      let duration = RemoteOperationTimeoutPolicy.loginTimeout(for: sentInfo, pathLength: pathLength)
      return Self.timeInterval(for: duration)
    }
    do {
      return try await session.login(
        to: publicKey,
        password: password,
        priority: priority,
        budget: budget,
        onTimeoutKnown: onTimeoutKnown,
        samePasswordExemption: samePasswordExemption
      )
    } catch let error as MeshCoreError {
      guard case let .deviceError(code) = error, code == ProtocolError.notFound.rawValue else {
        throw error
      }
      try Task.checkCancellation()
      try await addLocalContactToRadio(publicKey: publicKey, radioID: radioID)
      // Bounded to a single retry: a second notFound after a successful add is a firmware
      // inconsistency, left to propagate rather than loop.
      return try await session.login(
        to: publicKey,
        password: password,
        priority: priority,
        budget: budget,
        onTimeoutKnown: onTimeoutKnown,
        samePasswordExemption: samePasswordExemption
      )
    }
  }

  /// Pushes the local contact to the radio's contact table with flood routing, then reconciles the
  /// local row so keep-alive routing agrees with the radio.
  private func addLocalContactToRadio(publicKey: Data, radioID: UUID) async throws {
    guard let owner = contactRadioOwner.resolve() else {
      throw RemoteNodeError.contactNotFound
    }
    let healed: Bool
    do {
      healed = try await owner.healAbsentRadioContact(radioID: radioID, publicKey: publicKey)
    } catch let error as ContactServiceError {
      switch error {
      case .contactNotFound:
        throw RemoteNodeError.contactNotFound
      case .contactTableFull:
        throw RemoteNodeError.radioContactsFull
      case let .sessionError(sessionError):
        throw sessionError
      default:
        throw error
      }
    }
    if healed {
      logger.info("Re-added missing contact to radio during login")
    }
  }
}
