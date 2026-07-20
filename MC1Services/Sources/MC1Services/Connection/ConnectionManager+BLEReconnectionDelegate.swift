import Foundation
import MeshCore

// MARK: - BLEReconnectionDelegate

extension ConnectionManager: BLEReconnectionDelegate {
  func setConnectionState(_ state: DeviceConnectionState) {
    let previousState = connectionState
    connectionState = state
    if state == .disconnected, previousState != .disconnected {
      let transportName = switch currentTransportType {
      case .bluetooth: "bluetooth"
      case .wifi: "wifi"
      case nil: "none"
      }
      persistDisconnectDiagnostic(
        "source=reconnectionCoordinator.setConnectionState, " +
          "previousState=\(String(describing: previousState)), " +
          "transport=\(transportName), " +
          "intent=\(connectionIntent)"
      )
    }
  }

  func setConnectedDevice(_ device: DeviceDTO?) {
    connectedDevice = device
  }

  func teardownSessionForReconnect() async {
    // Capture and clear synchronously so a concurrent rebuildSession can
    // assume nil-and-rebuild without racing the terminal writes that used
    // to land at the end of this method. Subsequent awaits operate on the
    // captured local — they no longer touch self.services / self.session.
    let oldServices = services
    let oldSession = session
    services = nil
    session = nil

    // Stop the old session (keeping the transport for the pending reconnect)
    // so its receive and auto-fetch loops end now instead of parking on the
    // finished dispatcher stream until the object deallocates.
    await oldSession?.stop(disconnectTransport: false)
    // Symmetry with other stack teardowns: drop session-live so RSSI cannot
    // refresh a bond stamp while the app stack is gone (phase may still be
    // `.connected` briefly during auto-reconnect entry).
    await stateMachine.setAppSessionLive(deviceID: nil)

    if let oldServices {
      sessionsAwaitingReauth = await oldServices.remoteNodeService.handleBLEDisconnection()
      await oldServices.tearDown()
    }
    cancelResyncLoop()

    // Reset sync state on the captured services to prevent stuck "Syncing" pill
    if let oldServices {
      await oldServices.syncCoordinator.onDisconnected(notificationService: oldServices.notificationService)
    }
  }

  /// Background execution note: iOS provides ~10s of background execution time.
  /// Session rebuild (transport + session.start) should complete within this window.
  /// Full sync is deferred until performInitialSync returns to foreground via onConnectionEstablished.
  func rebuildSession(deviceID: UUID) async throws {
    logger.info("[BLE] Rebuilding session for auto-reconnect: \(deviceID.uuidString.prefix(8))")
    let expectedGeneration = reconnectionCoordinator.reconnectGeneration
    // Nested claim: health-check rebuild may already hold sessionRebuildDeviceID;
    // only clear on exit when this call installed the claim.
    let claimedHere = sessionRebuildDeviceID == nil
    if claimedHere {
      sessionRebuildDeviceID = deviceID
    } else {
      assert(
        sessionRebuildDeviceID == deviceID,
        "Nested rebuildSession claim must match outer sessionRebuildDeviceID"
      )
    }
    defer {
      if claimedHere, sessionRebuildDeviceID == deviceID {
        sessionRebuildDeviceID = nil
      }
    }

    // The auto-reconnect link is already live, so surface .connected up front,
    // matching fresh connect: the syncing pill stays visible through rebuild and
    // initial sync instead of a .connecting window the reconnect UI never bounds.
    connectionState = .connected

    // Session teardown in this rebuild never disconnects the transport: the
    // link belongs to the reconnect cycle (or, when superseded, to a newer
    // one), and an explicit disconnect here cancels the OS pending connect
    // that is recovering it. User-initiated disconnects sever the transport
    // through disconnect(reason:) independently.

    // Stop any existing session to prevent multiple receive loops racing for transport data
    await session?.stop(disconnectTransport: false)
    session = nil
    // App stack is down until handshake succeeds; prevent RSSI from refreshing
    // the bond shield over a dead stack.
    await stateMachine.setAppSessionLive(deviceID: nil)

    // The stopped session's receive-loop cancellation terminated the vended
    // stream's shared storage, so the transport must re-vend before the new
    // session reads receivedData. Ordered after the stop above so the stop
    // cannot finish the fresh stream.
    await transport.refreshDataStream()

    let newSession = MeshCoreSession(transport: transport)
    session = newSession

    do {
      try await withTimeout(.seconds(10), operationName: "session.start") {
        try await newSession.start(reconnectingAttempt: 1, disconnectTransportOnFailure: false)
      }
    } catch {
      logger.warning("[BLE] rebuildSession: session.start() failed: \(error.localizedDescription)")
      throw error
    }

    // Session traffic flowed over the encrypted UART link, so the bond is
    // proven healthy as of now. Also marks the app session live for RSSI refresh.
    await recordBondVerification(deviceID: deviceID)

    // Check after await — user may have disconnected or a new reconnect cycle may have started
    guard connectionIntent.wantsConnection else {
      logger.info("User disconnected during session setup")
      await abandonRebuildAfterHandshake(
        session: newSession,
        setDisconnected: true
      )
      return
    }
    guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
      logger.info("[BLE] rebuildSession superseded by new reconnect cycle during session setup")
      await abandonRebuildAfterHandshake(session: newSession)
      return
    }

    guard let selfInfo = await newSession.currentSelfInfo else {
      logger.warning("[BLE] rebuildSession: selfInfo is nil after start()")
      throw ConnectionError.initializationFailed("No self info")
    }
    let capabilities: DeviceCapabilities
    do {
      capabilities = try await withTimeout(.seconds(10), operationName: "queryDevice") {
        try await newSession.queryDevice()
      }
    } catch {
      logger.warning("[BLE] rebuildSession: queryDevice() failed: \(error.localizedDescription)")
      throw error
    }

    // Configure BLE write pacing based on device platform
    await configureBLEPacing(for: capabilities)

    // Check after await
    guard connectionIntent.wantsConnection else {
      logger.info("User disconnected during device query")
      await abandonRebuildAfterHandshake(
        session: newSession,
        setDisconnected: true
      )
      return
    }
    guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
      logger.info("[BLE] rebuildSession superseded by new reconnect cycle during device query")
      await abandonRebuildAfterHandshake(session: newSession)
      return
    }

    let (newServices, radioID) = try await buildServicesAndSaveDevice(
      deviceID: deviceID,
      session: newSession,
      selfInfo: selfInfo,
      capabilities: capabilities
    )

    // Check after await — user may have disconnected or new reconnect cycle started
    guard connectionIntent.wantsConnection else {
      logger.info("User disconnected during service wiring")
      await abandonRebuildAfterHandshake(
        session: newSession,
        services: newServices,
        setDisconnected: true
      )
      return
    }
    guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
      logger.info("[BLE] rebuildSession superseded by new reconnect cycle during service wiring")
      await abandonRebuildAfterHandshake(session: newSession, services: newServices)
      return
    }

    // Notify observers before sync starts so they can wire callbacks
    await onConnectionReady?()
    // onConnectionReady can suspend; a reentrant main-actor disconnect or
    // reconnect-UI timeout may clear connectedDevice or replace services during
    // that await. Recheck so an aborted reconnect bails here instead of syncing
    // a torn-down session.
    guard connectionIntent.wantsConnection,
          reconnectionCoordinator.reconnectGeneration == expectedGeneration,
          services === newServices,
          connectedDevice != nil
    else {
      logger.info("[BLE] rebuildSession aborted after onConnectionReady: reconnect state changed")
      await abandonRebuildAfterHandshake(session: newSession, services: newServices)
      return
    }
    let syncSucceeded = await performInitialSync(radioID: radioID, services: newServices, context: "[BLE] iOS auto-reconnect")

    // Caller-specific guard: generation check for superseded reconnects
    guard connectionIntent.wantsConnection,
          reconnectionCoordinator.reconnectGeneration == expectedGeneration,
          services === newServices
    else {
      await abandonRebuildAfterHandshake(session: newSession, services: newServices)
      return
    }

    if syncSucceeded {
      // Re-authenticate room sessions (sends BLE commands — skip on failure path).
      let sessionIDs = sessionsAwaitingReauth
      await newServices.remoteNodeService.handleBLEReconnection(sessionIDs: sessionIDs)

      guard connectionIntent.wantsConnection,
            reconnectionCoordinator.reconnectGeneration == expectedGeneration,
            services === newServices
      else {
        // IDs preserved for next reconnect cycle — new IDs may have
        // arrived during handleBLEReconnection if BLE dropped mid-reauth.
        await abandonRebuildAfterHandshake(session: newSession, services: newServices)
        return
      }

      // Only clear consumed IDs after confirming this cycle is still authoritative.
      // Any IDs appended during the await (via teardownSessionForReconnect) survive.
      sessionsAwaitingReauth.subtract(sessionIDs)
    }

    guard await promoteToReady(
      syncSucceeded: syncSucceeded,
      expectedServices: newServices,
      transportType: .bluetooth,
      additionalGuard: { [reconnectionCoordinator] in
        reconnectionCoordinator.reconnectGeneration == expectedGeneration
      }
    ) else {
      await abandonRebuildAfterHandshake(session: newSession, services: newServices)
      return
    }

    recordConnectionSuccess()
    stopReconnectionWatchdog()
    logger.info("[BLE] iOS auto-reconnect: session ready, device: \(deviceID.uuidString.prefix(8))")
  }

  /// Stops a mid-rebuild session that already completed the handshake (session-live
  /// may be set), nils the stored session/services when they match, and clears
  /// session-live so RSSI cannot refresh over a dead stack while phase stays
  /// `.connected`.
  private func abandonRebuildAfterHandshake(
    session rebuildSession: MeshCoreSession,
    services rebuildServices: ServiceContainer? = nil,
    setDisconnected: Bool = false
  ) async {
    await rebuildSession.stop(disconnectTransport: false)
    if let rebuildServices {
      await rebuildServices.tearDown()
      if services === rebuildServices {
        services = nil
      }
    }
    if session === rebuildSession {
      session = nil
    }
    await stateMachine.setAppSessionLive(deviceID: nil)
    if setDisconnected {
      connectionState = .disconnected
      connectedDevice = nil
      allowedRepeatFreqRanges = []
    }
  }

  func disconnectTransport() async {
    await transport.disconnect()
  }

  func notifyAutoReconnectStarted() async {
    await onAutoReconnectStarted?()
  }

  func notifyConnectionLost() async {
    await onConnectionLost?()
    // Reaching UI connection-loss while intent still wants a connection means
    // the automatic reconnect paths have been abandoned; the watchdog is the
    // remaining recovery. It stands down by itself while iOS auto-reconnect
    // is in progress, so arming it alongside a live pending connect is safe.
    if connectionIntent.wantsConnection,
       connectionState == .disconnected,
       currentTransportType == nil || currentTransportType == .bluetooth {
      startReconnectionWatchdog()
    }
  }

  func isTransportAutoReconnecting() async -> Bool {
    await stateMachine.isAutoReconnecting
  }

  func handleReconnectionFailure() async {
    logger.error("[BLE] Auto-reconnect session rebuild failed")

    // Budget only advances when intent still wants a connection. The user-disconnect
    // path (coordinator retry under .userDisconnected) reaches here with
    // wantsConnection == false; burning the counter on that path would exhaust
    // preserve budget after a few disconnect-during-retry events so a later real
    // failure severs early.
    if connectionIntent.wantsConnection {
      consecutiveRebuildFailures += 1
    }

    // Capture and clear synchronously *before* any await so a concurrent rebuild
    // that installs session/services during SM queries cannot be torn down by
    // re-reading self. Coordinator claim is held through this entire method
    // (cleared only after return) so health-check should not start a rebuild
    // under us either.
    let oldSession = session
    let oldServices = services
    session = nil
    services = nil
    connectionState = .disconnected
    connectedDevice = nil
    allowedRepeatFreqRanges = []

    // A rebuild failure is an app-layer failure. Severing the link cancels the OS
    // pending connect and the live GATT subscription, the only wake sources that
    // survive process suspension, and discards a link the health-check ladder can
    // rebuild in place.
    let isConnected = await stateMachine.isConnected
    let isAutoReconnecting = await stateMachine.isAutoReconnecting
    let holdsLink = isConnected || isAutoReconnecting
    let preserveLink = connectionIntent.wantsConnection
      && holdsLink
      && consecutiveRebuildFailures <= Self.maxRebuildFailuresPreservingLink

    // Session-live bond refresh must stop while the app stack is dead.
    // Clear on both branches — preserve keeps phase `.connected`, so phase cleanup
    // never clears the signal there.
    await stateMachine.setAppSessionLive(deviceID: nil)

    await oldSession?.stop(disconnectTransport: false)
    await oldServices?.tearDown()

    if preserveLink {
      // n of N still preserving; sever is on the (N+1)th wanting entry.
      logger.warning(
        "[BLE] Rebuild failure \(consecutiveRebuildFailures) of \(Self.maxRebuildFailuresPreservingLink) still preserving link (sever on next)"
      )
      // Preserve-only: arm if none is running. Natural exit paths inside the
      // watchdog Task nil reconnectionWatchdogTask (a finished Task is not
      // isCancelled — non-nil alone is not "live"). Do not call
      // startReconnectionWatchdog when a live task is running (that always
      // cancel+restarts at 30s).
      if reconnectionWatchdogTask == nil {
        startReconnectionWatchdog()
      }
      await onConnectionLost?()
    } else {
      if connectionIntent.wantsConnection, holdsLink {
        logger.error(
          "[BLE] Rebuild preserve budget exhausted after \(consecutiveRebuildFailures) failures; severing link"
        )
        persistDisconnectDiagnostic(
          "source=handleReconnectionFailure.preserveBudgetExhausted, " +
            "failures=\(consecutiveRebuildFailures), " +
            "intent=\(connectionIntent)"
        )
      }
      await transport.disconnect()
      // Exhaustion / no-link / user-disconnect: full path (cancel+restart 30s).
      await notifyConnectionLost()
    }
  }
}
