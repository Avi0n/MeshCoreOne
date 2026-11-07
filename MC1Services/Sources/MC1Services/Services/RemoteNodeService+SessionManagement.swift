import Foundation
import MeshCore

public extension RemoteNodeService {
  // MARK: - Keep-Alive (Room Servers)

  /// Start periodic keep-alive for a room server session.
  /// Sends an immediate keep-alive on start (for connectivity check + sync_since update),
  /// then continues at the configured interval. Transient failures are retried up to
  /// `KeepAliveRetryPolicy.maxConsecutiveFailures` times before disconnecting.
  private func startKeepAlive(sessionID: UUID, publicKey: Data) {
    guard !isRetired else { return }
    stopKeepAlive(sessionID: sessionID)

    let interval = keepAliveIntervals[sessionID] ?? Self.defaultKeepAliveInterval

    // The actor retains this task via keepAliveTasks, so the loop captures
    // self weakly and rebinds per tick; a strong capture would keep the
    // actor (and its session/dataStore) alive for the app lifetime.
    let task = Task { [weak self] in
      var consecutiveFailures = 0

      while !Task.isCancelled {
        guard let tick = await self?.performKeepAliveTick(
          sessionID: sessionID,
          publicKey: publicKey,
          consecutiveFailures: consecutiveFailures
        ), tick.shouldContinue else { return }
        consecutiveFailures = tick.consecutiveFailures

        try? await Task.sleep(for: interval)
      }
    }

    keepAliveTasks[sessionID] = task
  }

  /// Runs one keep-alive attempt for the loop in `startKeepAlive`.
  /// Returns whether the loop should continue and the updated failure count.
  private func performKeepAliveTick(
    sessionID: UUID,
    publicKey: Data,
    consecutiveFailures: Int
  ) async -> (shouldContinue: Bool, consecutiveFailures: Int) {
    var failures = consecutiveFailures
    do {
      try await sendScheduledKeepAlive(sessionID: sessionID, publicKey: publicKey)
      KeepAliveRetryPolicy.recordSuccess(consecutiveFailures: &failures)
      return (shouldContinue: true, consecutiveFailures: failures)
    } catch {
      let action = KeepAliveRetryPolicy.evaluate(error: error, consecutiveFailures: &failures)
      switch action {
      case .stop:
        break
      case .skip:
        logger.info("Skipping keep-alive for flood-routed session \(sessionID)")
      case .retryNextInterval:
        let reason = KeepAliveRetryPolicy.failureReason(for: error)
        logger.warning("Keep-alive \(failures)/\(KeepAliveRetryPolicy.maxConsecutiveFailures) failed for \(sessionID): \(reason)")
      case .disconnect, .disconnectNow:
        let reason = KeepAliveRetryPolicy.failureReason(for: error)
        logger.warning("Keep-alive failed for \(sessionID): \(reason)")
        do {
          try await dataStore.markSessionDisconnected(sessionID)
        } catch {
          logger.error("Failed to persist disconnected state for session \(sessionID): \(error)")
        }
        eventBroadcaster.yield(.sessionStateChanged(sessionID: sessionID, isConnected: false))
      }
      return (shouldContinue: !action.shouldExitLoop, consecutiveFailures: failures)
    }
  }

  /// Stop keep-alive for a session
  internal func stopKeepAlive(sessionID: UUID) {
    guard let worker = keepAliveTasks.removeValue(forKey: sessionID) else { return }
    worker.cancel()
    retiredKeepAliveTasks.append(worker)
  }

  /// Send a manual keep-alive for a directly routed session.
  func sendKeepAlive(sessionID: UUID) async throws {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    guard !remoteSession.isSensor else { throw RemoteNodeError.invalidResponse }
    if remoteSession.isRoom {
      try await refreshRoom(remoteSession)
      return
    }

    let publicKey = remoteSession.publicKey
    guard let contact = try await dataStore.fetchContact(radioID: remoteSession.radioID, publicKey: publicKey) else {
      throw RemoteNodeError.contactNotFound
    }

    guard !contact.isFloodRouted else { throw RemoteNodeError.floodRouted }

    await auditLogger.logKeepAlive(target: .repeater, publicKey: publicKey)

    do {
      _ = try await session.sendKeepAlive(to: publicKey, syncSince: remoteSession.lastSyncTimestamp)
    } catch let error as MeshCoreError {
      throw RemoteNodeError.sessionError(error)
    }
  }

  /// Start keep-alive for a room session (called when room view appears).
  func startSessionKeepAlive(sessionID: UUID, publicKey: Data) {
    startKeepAlive(sessionID: sessionID, publicKey: publicKey)
  }

  /// Stop keep-alive for a room session (called when room view disappears).
  func stopSessionKeepAlive(sessionID: UUID) {
    stopKeepAlive(sessionID: sessionID)
  }

  // MARK: - History Sync

  /// Request message history from a room server.
  func requestHistorySync(sessionID: UUID) async throws {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    guard remoteSession.isRoom else {
      throw RemoteNodeError.invalidResponse
    }

    // Check for direct route
    guard let contact = try await dataStore.fetchContact(radioID: remoteSession.radioID, publicKey: remoteSession.publicKey) else {
      throw RemoteNodeError.contactNotFound
    }

    if contact.isFloodRouted {
      throw RemoteNodeError.floodRouted
    }

    // Request status (which triggers sync)
    do {
      let contactType: ContactType = .room
      _ = try await session.requestStatus(from: remoteSession.publicKey, type: contactType)
    } catch let error as MeshCoreError {
      throw RemoteNodeError.sessionError(error)
    }

    logger.info("Requested history sync for room \(remoteSession.name)")
  }

  // MARK: - Logout

  /// Explicitly logout from a remote node.
  func logout(sessionID: UUID) async throws {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    if remoteSession.isSensor {
      await disconnect(sessionID: sessionID)
      return
    }

    // Log logout
    let targetType: CommandAuditLogger.Target = CommandAuditLogger.target(for: remoteSession.role)
    await auditLogger.logLogout(target: targetType, publicKey: remoteSession.publicKey)

    stopKeepAlive(sessionID: sessionID)

    do {
      try await session.sendLogout(to: remoteSession.publicKey)
    } catch {
      // Ignore errors - we're disconnecting anyway
      logger.info("Logout send failed (ignoring): \(error)")
    }

    try await dataStore.updateRemoteNodeSessionConnection(
      id: sessionID,
      isConnected: false,
      permissionLevel: .guest
    )

    // Notify UI of session state change
    eventBroadcaster.yield(.sessionStateChanged(sessionID: sessionID, isConnected: false))
  }
}
