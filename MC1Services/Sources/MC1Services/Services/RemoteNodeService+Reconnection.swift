import Foundation

public extension RemoteNodeService {
  // MARK: - Link Disconnection

  /// Stops remote work and publishes disconnected sessions, returning their IDs
  /// so only previously connected sessions are re-authenticated after link recovery.
  func handleLinkDisconnection(radioID: UUID) async -> Set<UUID> {
    beginRetirement()
    await stopEventMonitoring()
    let connectedSessions: [RemoteNodeSessionDTO]
    do {
      connectedSessions = try await dataStore.fetchRemoteNodeSessions(radioID: radioID).filter(\.isConnected)
    } catch {
      logger.error("Failed to fetch connected sessions for Link disconnection: \(error)")
      return []
    }

    guard !connectedSessions.isEmpty else { return [] }

    logger.info("Link disconnection: marking \(connectedSessions.count) session(s) disconnected")
    var sessionIDs: Set<UUID> = []

    for session in connectedSessions {
      sessionIDs.insert(session.id)
      stopKeepAlive(sessionID: session.id)
      do {
        try await dataStore.markSessionDisconnected(session.id)
      } catch {
        logger.error("Failed to mark session \(session.id) disconnected: \(error)")
      }
      eventBroadcaster.yield(.sessionStateChanged(sessionID: session.id, isConnected: false))
    }

    return sessionIDs
  }

  // MARK: - Link Reconnection

  /// Re-authenticates the sessions captured before link loss.
  /// An empty set leaves reconnection to the user.
  func handleLinkReconnection(radioID: UUID, sessionIDs: Set<UUID>) async {
    guard !isReauthenticating else {
      logger.info("Skipping re-auth: already in progress")
      return
    }

    guard !sessionIDs.isEmpty, !isRetired else { return }
    isReauthenticating = true
    defer { isReauthenticating = false }

    // Fetch current session state for each ID
    var sessionsToReauth: [RemoteNodeSessionDTO] = []
    for id in sessionIDs {
      if let session = try? await dataStore.fetchRemoteNodeSession(id: id), session.radioID == radioID {
        sessionsToReauth.append(session)
      } else {
        logger.warning("Session \(id) not found for re-auth, skipping")
      }
    }

    guard !sessionsToReauth.isEmpty else { return }

    logger.info("Link reconnection: re-authenticating \(sessionsToReauth.count) session(s)")
    for remoteSession in sessionsToReauth {
      guard !Task.isCancelled, !isRetired else { return }
      let previousPermission = remoteSession.permissionLevel
      do {
        let result = try await login(sessionID: remoteSession.id)
        guard !Task.isCancelled, !isRetired else { return }
        let permission = result.permissionLevel(for: remoteSession.role)
        if permission < previousPermission {
          await disconnect(sessionID: remoteSession.id)
        }
      } catch RemoteNodeError.cancelled {
        // A newer login for this node can supersede background re-authentication.
        continue
      } catch {
        guard !Task.isCancelled, !isRetired else { return }
        await disconnect(sessionID: remoteSession.id)
        logger.warning("Re-auth failed for session \(remoteSession.id): \(error)")
      }
    }
  }
}
