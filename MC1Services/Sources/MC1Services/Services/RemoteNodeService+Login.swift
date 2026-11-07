import Foundation
import MeshCore

private let loginPublicKeyPrefixLength = 6

public extension RemoteNodeService {
  // MARK: - Login

  /// Login to a remote node.
  /// Works for sensors, repeaters, and room servers.
  /// - Parameters:
  ///   - sessionID: The remote session ID.
  ///   - password: Optional password (uses stored password if nil).
  ///   - pathLength: Path length hint for timeout calculation.
  ///   - onTimeoutKnown: Optional callback invoked with timeout in seconds once firmware responds.
  func login(
    sessionID: UUID,
    password: String? = nil,
    pathLength: UInt8 = 0,
    onTimeoutKnown: (@Sendable (Int) async -> Void)? = nil
  ) async throws -> LoginResult {
    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    // Get password from parameter or keychain
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
    cancelPendingLogin(for: prefix)
    let attempt = RemoteLoginAttempt(sessionID: sessionID)
    let attemptID = attempt.id
    loginAttempts[prefix] = attempt
    defer {
      if loginAttempts[prefix]?.id == attemptID { loginAttempts[prefix] = nil }
    }

    let targetType: CommandAuditLogger.Target = CommandAuditLogger.target(for: remoteSession.role)
    await auditLogger.logLoginRequest(target: targetType, publicKey: remoteSession.publicKey, pathLength: pathLength)

    let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
    logger.info("login: registering pending login for prefix \(prefixHex)")
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { continuation in
        guard !Task.isCancelled, loginAttempts[prefix]?.id == attemptID, !isRetired else {
          continuation.resume(throwing: RemoteNodeError.cancelled)
          return
        }
        // Registration and worker installation share one actor turn so a reply can claim either send phase.
        let worker = Task { [self] in
          await runLoginAttempt(
            remoteSession, password: pwd, prefix: prefix, attemptID: attemptID,
            pathLength: pathLength, onTimeoutKnown: onTimeoutKnown
          )
        }
        loginAttempts[prefix]?.beginSubmission(continuation: continuation, worker: worker)
      }
    } onCancel: { [weak self] in
      Task { [weak self] in
        await self?.cancelPendingLogin(for: prefix, attemptID: attemptID)
      }
    }
  }

  private func runLoginAttempt(
    _ remoteSession: RemoteNodeSessionDTO, password: String, prefix: Data, attemptID: UUID,
    pathLength: UInt8, onTimeoutKnown: (@Sendable (Int) async -> Void)?
  ) async {
    let sentInfo: MessageSentInfo
    do {
      sentInfo = try await sendLoginHealingIfNeeded(
        publicKey: remoteSession.publicKey, radioID: remoteSession.radioID, password: password
      )
    } catch {
      guard !Task.isCancelled, !isRetired else { return }
      let rnError = (error as? RemoteNodeError)
        ?? .sessionError(error as? MeshCoreError ?? .connectionLost(underlying: error))
      claimLoginCompletion(for: prefix, attemptID: attemptID)?.continuation.resume(throwing: rnError)
      return
    }

    guard !Task.isCancelled, loginAttempts[prefix]?.id == attemptID, !isRetired,
          loginAttempts[prefix]?.didSubmit() == true else { return }
    let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
    let timeout = RemoteOperationTimeoutPolicy.loginTimeout(for: sentInfo, pathLength: pathLength)
    logger.info("login: send succeeded, starting \(timeout) timeout for prefix \(prefixHex)")

    if let onTimeoutKnown {
      let timeoutSeconds = max(1, Int(timeInterval(for: timeout).rounded(.up)))
      await onTimeoutKnown(timeoutSeconds)
    }
    guard !Task.isCancelled, loginAttempts[prefix]?.id == attemptID, !isRetired,
          loginAttempts[prefix]?.isAwaitingResult == true else { return }

    let deadline = ContinuousClock.now.advanced(by: timeout)
    let retransmitInterval = max(
      RemoteOperationTimeoutPolicy.loginRetransmitInterval,
      .milliseconds(Int(sentInfo.suggestedTimeoutMs))
    )
    var retransmitCount = 0
    while !Task.isCancelled {
      let remaining = deadline - ContinuousClock.now
      guard remaining > .zero else { break }
      let sleepFor = remaining < retransmitInterval ? remaining : retransmitInterval
      try? await Task.sleep(for: sleepFor)
      guard !Task.isCancelled, loginAttempts[prefix]?.id == attemptID, !isRetired,
            loginAttempts[prefix]?.isAwaitingResult == true else { return }
      if ContinuousClock.now >= deadline { break }
      retransmitCount += 1
      do {
        logger.info("login: retransmit #\(retransmitCount) for \(prefixHex)")
        _ = try await session.sendLogin(to: remoteSession.publicKey, password: password)
      } catch is CancellationError {
        return
      } catch {
        logger.warning("login: retransmit #\(retransmitCount) failed for \(prefixHex): \(error.localizedDescription)")
      }
    }

    guard !Task.isCancelled, !isRetired else { return }
    if let completion = claimLoginCompletion(for: prefix, attemptID: attemptID) {
      logger.warning("Login timeout after \(timeout) for session \(remoteSession.id), prefix \(prefixHex)")
      completion.continuation.resume(throwing: RemoteNodeError.timeout)
    }
  }

  private func claimLoginCompletion(for prefix: Data, attemptID: UUID? = nil) -> RemoteLoginAttempt.Completion? {
    if let attemptID, loginAttempts[prefix]?.id != attemptID { return nil }
    return loginAttempts[prefix]?.claimCompletion()
  }

  /// Sends the login; if the radio reports the contact is missing from its table, pushes the local
  /// copy (flood-routed) and retries once. A contact can be in the app database but absent on the
  /// radio after a backup restore or a radio swap, which the firmware answers with notFound (0x02).
  /// The parked login continuation is covered here by the session commands' own timeouts, not the
  /// login timeout, which only starts after this returns.
  internal func sendLoginHealingIfNeeded(
    publicKey: Data,
    radioID: UUID,
    password: String
  ) async throws -> MessageSentInfo {
    do {
      return try await session.sendLogin(to: publicKey, password: password)
    } catch let error as MeshCoreError {
      guard case let .deviceError(code) = error, code == ProtocolError.notFound.rawValue else {
        throw error
      }
      try Task.checkCancellation()
      try await addLocalContactToRadio(publicKey: publicKey, radioID: radioID)
      // Bounded to a single retry: a second notFound after a successful add is a firmware
      // inconsistency, left to propagate rather than loop.
      return try await session.sendLogin(to: publicKey, password: password)
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

  /// Handle login result push from device.
  internal func handleLoginResult(_ result: LoginResult, fromPublicKeyPrefix: Data) async {
    guard fromPublicKeyPrefix.count >= loginPublicKeyPrefixLength else {
      logger.warning("Login result has invalid prefix length: \(fromPublicKeyPrefix.count)")
      return
    }

    let prefix = Data(fromPublicKeyPrefix.prefix(loginPublicKeyPrefixLength))
    let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
    let pendingKeys = loginAttempts.filter(\.value.isAwaitingResult).keys.map { $0.map { String(format: "%02x", $0) }.joined() }
    logger.info("handleLoginResult: looking for prefix \(prefixHex), pending keys: \(pendingKeys)")
    guard let completion = claimLoginCompletion(for: prefix) else {
      logger.warning("Login result with no pending request. Prefix: \(prefixHex)")
      return
    }
    logger.info("handleLoginResult: found continuation for prefix \(prefixHex)")

    do {
      guard !isRetired else { throw RemoteNodeError.cancelled }
      guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: completion.sessionID) else {
        throw RemoteNodeError.sessionNotFound
      }
      guard loginAttempts[prefix]?.id == completion.attemptID, !isRetired else { throw RemoteNodeError.cancelled }
      let target = CommandAuditLogger.target(for: remoteSession.role)
      guard result.success else {
        await auditLogger.logLoginFailed(target: target, publicKey: prefix, reason: "authentication failed")
        throw RemoteNodeError.loginFailed("authentication failed")
      }
      await auditLogger.logLoginSuccess(target: target, publicKey: prefix, isAdmin: result.isAdmin)
      guard loginAttempts[prefix]?.id == completion.attemptID, !isRetired else { throw RemoteNodeError.cancelled }
      try await dataStore.updateRemoteNodeSessionConnection(
        id: remoteSession.id,
        isConnected: true,
        permissionLevel: result.permissionLevel(for: remoteSession.role)
      )
      guard loginAttempts[prefix]?.id == completion.attemptID, !isRetired else {
        // A cancelled store turn may already have committed; retire it unless a new login owns the row.
        if loginAttempts[prefix] == nil { try? await dataStore.markSessionDisconnected(remoteSession.id) }
        throw RemoteNodeError.cancelled
      }
      eventBroadcaster.yield(.sessionStateChanged(sessionID: remoteSession.id, isConnected: true))
      if remoteSession.isRoom { keepAliveIntervals[remoteSession.id] = Self.defaultKeepAliveInterval }
      completion.continuation.resume(returning: result)
    } catch {
      completion.continuation.resume(throwing: error)
    }
  }
}
