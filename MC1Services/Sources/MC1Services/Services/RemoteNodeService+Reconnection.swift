import Foundation

public extension RemoteNodeService {
  // MARK: - BLE Disconnection

  /// Called when BLE connection is lost.
  /// Marks all connected sessions as disconnected, stops keep-alive timers,
  /// and broadcasts `RemoteNodeEvent.sessionStateChanged` for each.
  /// Returns the set of session IDs that were connected, for re-auth on reconnect.
  func handleBLEDisconnection() async -> Set<UUID> {
    let connectedSessions: [RemoteNodeSessionDTO]
    do {
      connectedSessions = try await dataStore.fetchConnectedRemoteNodeSessions()
    } catch {
      logger.error("Failed to fetch connected sessions for BLE disconnection: \(error)")
      return []
    }

    guard !connectedSessions.isEmpty else { return [] }

    logger.info("BLE disconnection: marking \(connectedSessions.count) session(s) disconnected")
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

  // MARK: - BLE Reconnection

  /// Called when BLE connection is re-established.
  /// Re-authenticates sessions that were connected before BLE loss.
  /// - Parameter sessionIDs: Session IDs from `handleBLEDisconnection()`.
  ///   If empty (e.g., after app restart), no sessions are re-authenticated;
  ///   the user can manually reconnect.
  func handleBLEReconnection(sessionIDs: Set<UUID>) async {
    guard !isReauthenticating else {
      logger.info("Skipping re-auth: already in progress")
      return
    }

    guard !sessionIDs.isEmpty else { return }

    // Fetch current session state for each ID
    var sessionsToReauth: [RemoteNodeSessionDTO] = []
    for id in sessionIDs {
      if let session = try? await dataStore.fetchRemoteNodeSession(id: id) {
        sessionsToReauth.append(session)
      } else {
        logger.warning("Session \(id) not found for re-auth, skipping")
      }
    }

    guard !sessionsToReauth.isEmpty else { return }

    logger.info("BLE reconnection: re-authenticating \(sessionsToReauth.count) session(s)")
    isReauthenticating = true
    defer { isReauthenticating = false }

    await withTaskGroup(of: Void.self) { group in
      for remoteSession in sessionsToReauth {
        group.addTask { [self] in
          let previousPermission = remoteSession.permissionLevel
          do {
            let result = try await login(sessionID: remoteSession.id)
            let newPermission = result.permissionLevel
            if newPermission < previousPermission {
              logger.warning(
                "Re-auth returned degraded permission for session \(remoteSession.id): "
                  + "\(previousPermission) -> \(newPermission), marking disconnected"
              )
              try? await dataStore.markSessionDisconnected(remoteSession.id)
              eventBroadcaster.yield(.sessionStateChanged(sessionID: remoteSession.id, isConnected: false))
            }
          } catch {
            logger.warning("Re-auth failed for session \(remoteSession.id): \(error)")
            do {
              try await dataStore.markSessionDisconnected(remoteSession.id)
            } catch {
              logger.error("Failed to persist disconnected state for session \(remoteSession.id): \(error)")
            }
            eventBroadcaster.yield(.sessionStateChanged(sessionID: remoteSession.id, isConnected: false))
          }
        }
      }
    }
  }
}
