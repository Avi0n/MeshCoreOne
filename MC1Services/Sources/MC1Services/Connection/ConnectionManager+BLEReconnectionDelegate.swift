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
    sessionRebuildDeviceID = deviceID
    defer {
      if sessionRebuildDeviceID == deviceID {
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

    // Check after await — user may have disconnected or a new reconnect cycle may have started
    guard connectionIntent.wantsConnection else {
      logger.info("User disconnected during session setup")
      await newSession.stop(disconnectTransport: false)
      connectionState = .disconnected
      return
    }
    guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
      logger.info("[BLE] rebuildSession superseded by new reconnect cycle during session setup")
      await newSession.stop(disconnectTransport: false)
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
      await newSession.stop(disconnectTransport: false)
      connectionState = .disconnected
      return
    }
    guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
      logger.info("[BLE] rebuildSession superseded by new reconnect cycle during device query")
      await newSession.stop(disconnectTransport: false)
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
      await newSession.stop(disconnectTransport: false)
      await newServices.tearDown()
      services = nil
      connectedDevice = nil
      allowedRepeatFreqRanges = []
      connectionState = .disconnected
      return
    }
    guard reconnectionCoordinator.reconnectGeneration == expectedGeneration else {
      logger.info("[BLE] rebuildSession superseded by new reconnect cycle during service wiring")
      await newSession.stop(disconnectTransport: false)
      await newServices.tearDown()
      services = nil
      connectedDevice = nil
      allowedRepeatFreqRanges = []
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
      await newSession.stop(disconnectTransport: false)
      await newServices.tearDown()
      services = nil
      connectedDevice = nil
      allowedRepeatFreqRanges = []
      return
    }
    let syncSucceeded = await performInitialSync(radioID: radioID, services: newServices, context: "[BLE] iOS auto-reconnect")

    // Caller-specific guard: generation check for superseded reconnects
    guard connectionIntent.wantsConnection,
          reconnectionCoordinator.reconnectGeneration == expectedGeneration,
          services === newServices
    else {
      await newSession.stop(disconnectTransport: false)
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
        await newSession.stop(disconnectTransport: false)
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
      await newSession.stop(disconnectTransport: false)
      return
    }

    recordConnectionSuccess()
    stopReconnectionWatchdog()
    logger.info("[BLE] iOS auto-reconnect: session ready, device: \(deviceID.uuidString.prefix(8))")
  }

  func disconnectTransport() async {
    await transport.disconnect()
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

    // Capture and clear synchronously, mirroring teardownSessionForReconnect:
    // a concurrent rebuild can install a new session and container during the
    // awaits below, and re-reading self.session / self.services would tear
    // the replacements down.
    let oldSession = session
    let oldServices = services
    session = nil
    services = nil
    connectionState = .disconnected
    connectedDevice = nil
    allowedRepeatFreqRanges = []

    await oldSession?.stop()
    await oldServices?.tearDown()
    await transport.disconnect()

    // Same callback contract as handleConnectionLoss and the UI-timeout
    // path in BLEReconnectionCoordinator: route through notifyConnectionLost()
    // so AppState tears down its observers and the Live Activity transitions
    // to disconnected, and the watchdog restarts while intent wants a
    // connection. Without this the LA stays in stale "connected" state.
    await notifyConnectionLost()
  }
}
