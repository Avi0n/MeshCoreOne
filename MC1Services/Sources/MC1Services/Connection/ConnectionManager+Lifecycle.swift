import Foundation
import MeshCore

public extension ConnectionManager {
  internal var activeConnectionAttemptDeviceID: UUID? {
    connectingDeviceID ?? sessionRebuildDeviceID ?? reconnectionCoordinator.reconnectingDeviceID
  }

  internal var activeReconnectDeviceID: UUID? {
    sessionRebuildDeviceID ?? reconnectionCoordinator.reconnectingDeviceID
  }

  // MARK: - App Lifecycle

  /// Called when the app enters background. Pauses foreground-only BLE operations.
  func appDidEnterBackground() async {
    let transportName = switch currentTransportType {
    case .bluetooth: "bluetooth"
    case .wifi: "wifi"
    case nil: "none"
    }
    logger.info(
      "[BLE] Lifecycle transition: entering background, " +
        "transport: \(transportName), " +
        "connectionIntent: \(connectionIntent), " +
        "connectionState: \(String(describing: connectionState))"
    )
    await stateMachine.appDidEnterBackground()
    stopReconnectionWatchdog()
    let bleState = await stateMachine.centralManagerStateName
    let blePhase = await stateMachine.currentPhaseName
    logger.info(
      "[BLE] Lifecycle transition complete: backgrounded, " +
        "bleState: \(bleState), " +
        "blePhase: \(blePhase)"
    )
  }

  /// Called when the app becomes active. Reconciles BLE state and restarts
  /// foreground operations.
  func appDidBecomeActive() async {
    let transportName = switch currentTransportType {
    case .bluetooth: "bluetooth"
    case .wifi: "wifi"
    case nil: "none"
    }
    logger.info(
      "[BLE] Lifecycle transition: becoming active, " +
        "transport: \(transportName), " +
        "connectionIntent: \(connectionIntent), " +
        "connectionState: \(String(describing: connectionState))"
    )
    await stateMachine.appDidBecomeActive()
    await checkBLEConnectionHealth()
    let bleState = await stateMachine.centralManagerStateName
    let blePhase = await stateMachine.currentPhaseName
    logger.info(
      "[BLE] Lifecycle transition complete: active health check finished, " +
        "connectionState: \(String(describing: connectionState)), " +
        "bleState: \(bleState), " +
        "blePhase: \(blePhase)"
    )

    guard currentTransportType == nil || currentTransportType == .bluetooth else { return }
    guard connectionIntent.wantsConnection, connectionState == .disconnected else { return }

    if shouldDeferOpportunisticReconnect {
      logger.info("[BLE] ConnectionManager: not re-arming watchdog on foreground (pairing in progress)")
      return
    }

    if await stateMachine.isAutoReconnecting {
      logger.info("[BLE] ConnectionManager: not re-arming watchdog on foreground (iOS auto-reconnect in progress)")
      return
    }

    startReconnectionWatchdog()
    logger.info("[BLE] ConnectionManager: re-armed watchdog on foreground while disconnected")
  }

  // MARK: - Sync Health

  /// Triggers resync if connected but sync state is failed.
  /// Called when app returns to foreground.
  func checkSyncHealth() async {
    guard connectionState.isOperational,
          connectionIntent.wantsConnection,
          let services,
          let radioID = connectedDevice?.radioID else { return }

    let syncCoordinator = services.syncCoordinator
    let syncState = syncCoordinator.state
    guard case .failed = syncState else { return }

    guard resyncTask == nil else {
      logger.info("Resync loop already running, skipping foreground trigger")
      return
    }

    logger.info("Foreground return: sync state is failed, starting resync loop")
    startResyncLoop(radioID: radioID, services: services, transportType: currentTransportType ?? .bluetooth)
  }

  // MARK: - Activation

  /// Activates the connection manager on app launch.
  /// Call this once during app initialization.
  func activate() async {
    // Ensure lifecycle handlers are installed before anything below can
    // construct the CBCentralManager; creating the central can fire
    // restoration callbacks synchronously, and a missed handler would
    // strand the restored link without a session rebuild.
    await wireTransportHandlers()

    let lastDeviceShort = lastConnectedDeviceID?.uuidString.prefix(8) ?? "none"
    let bleState = await stateMachine.centralManagerStateName
    logger.info("""
    Activating ConnectionManager - \
    connectionIntent: \(connectionIntent), \
    lastConnectedDeviceID: \(lastDeviceShort), \
    connectionState: \(String(describing: connectionState)), \
    bleState: \(bleState)
    """)

    // Reset stale room session connections from previous app launch
    let resetStore = createStandalonePersistenceStore()
    try? await resetStore.resetAllRemoteNodeSessionConnections()

    // Populate radioID on existing devices and backfill deduplication keys (one-time migration)
    do {
      try await resetStore.performRadioIDMigration()
    } catch {
      logger.error("radioID migration failed: \(error)")
    }

    // Promote legacy per-channel region overrides to `.specific` mode so the
    // corrective flood-scope semantics don't reinterpret them as `.inherit`.
    do {
      try await resetStore.performChannelFloodScopeMigration()
    } catch {
      logger.error("channel flood-scope migration failed: \(error)")
    }

    // Zero accumulated unread counts on repeater-type contacts and repeater-role
    // sessions so the badge stops including invisible records.
    do {
      try await resetStore.performRepeaterUnreadCountMigration()
    } catch {
      logger.error("repeater unread-count migration failed: \(error)")
    }

    // Backfill sortDate from createdAt on pre-existing messages so date-header
    // grouping keeps their current display order.
    do {
      try await resetStore.performSortDateBackfillMigration()
    } catch {
      logger.error("sortDate backfill migration failed: \(error)")
    }

    // Re-normalize sortDate to createdAt for installs whose backfill already ran
    // before the block-at-reconnect change, un-burying any rows an interim build
    // sorted by send time. Must run before stateMachine.activate() so no
    // restoration-driven sync writes a fresh anchor before this resets the baseline.
    do {
      try await resetStore.performSortDateResetMigration()
    } catch {
      logger.error("sortDate reset migration failed: \(error)")
    }

    #if targetEnvironment(simulator)
      // Skip auto-reconnect if user explicitly disconnected
      if connectionIntent.isUserDisconnected {
        logger.info("Simulator: skipping auto-reconnect - user previously disconnected")
        return
      }
      // On simulator, skip ASK entirely and auto-reconnect to simulator device
      if let lastDeviceID = lastConnectedDeviceID,
         lastDeviceID == MockDataProvider.simulatorDeviceID {
        logger.info("Simulator: auto-reconnecting to mock device")
        connectionIntent = .wantsConnection()
        do {
          try await simulatorConnect()
        } catch {
          logger.warning("Simulator auto-reconnect failed: \(error.localizedDescription)")
        }
        return
      }
      // Simulator doesn't support real BLE devices - show connection UI for simulator pairing
      return
    #else
      // Activate the pairing session early; on iOS this is AccessorySetupKit, required for
      // ASK events and iOS 26 state restoration. On macOS this is a no-op.
      do {
        try await pairing.activate()
      } catch {
        logger.error("Failed to activate pairing session: \(error.localizedDescription)")
        // Don't return - WiFi doesn't need the pairing session
      }

      // Skip auto-reconnect if user explicitly disconnected
      if connectionIntent.isUserDisconnected {
        logger.info("Skipping auto-reconnect: user previously disconnected")
        return
      }

      // Auto-reconnect to last device if available
      if let lastDeviceID = lastConnectedDeviceID {
        logger.info("Attempting auto-reconnect to last device: \(lastDeviceID)")

        // Set intent before checking state
        connectionIntent = .wantsConnection()

        // Check if last device was WiFi - try WiFi first
        let dataStore = PersistenceStore(modelContainer: modelContainer)
        if let device = try? await dataStore.fetchDevice(id: lastDeviceID),
           let wifiMethod = device.connectionMethods.first(where: { $0.isWiFi }) {
          if case let .wifi(host, port, _) = wifiMethod {
            logger.info("Auto-reconnecting via WiFi to \(host):\(port)")
            do {
              try await connectViaWiFi(host: host, port: port)
              return
            } catch {
              logger.warning("WiFi auto-reconnect failed: \(error.localizedDescription)")
              // Fall through to try BLE
            }
          }
        }

        // Activate BLE state machine before checking BLE state restoration status.
        // Must be after: ASK activation (line 700), explicit disconnect guard (line 709).
        // Must be before: isAutoReconnecting check, isDeviceConnectedToSystem.
        await stateMachine.activate()

        // If state machine is already auto-reconnecting (from state restoration),
        // let it complete rather than fighting with it
        if await stateMachine.isAutoReconnecting {
          let blePhase = await stateMachine.currentPhaseName
          let blePeripheralState = await stateMachine.currentPeripheralState ?? "none"
          logger.info(
            "State restoration in progress - blePhase: \(blePhase), blePeripheralState: \(blePeripheralState), waiting for auto-reconnect"
          )
          return
        }

        if await stateMachine.isConnected, await stateMachine.connectedDeviceID == lastDeviceID {
          logger.info("State restoration complete - device already connected, waiting for session setup")
          return
        }

        // If iOS kept the BLE link alive (common across app updates) but restoration didn't fire,
        // adopt the system-connected peripheral rather than treating it as "connected elsewhere".
        if await startAdoptingLastSystemConnectedPeripheralIfAvailable(
          deviceID: lastDeviceID,
          context: "activate"
        ) {
          return
        }

        // Check if device is connected to another app before auto-reconnect
        // Silently skip per HIG: minimize interruptions on app launch
        if await isDeviceConnectedToOtherApp(lastDeviceID) {
          logger.info("Auto-reconnect skipped: device connected to another app")
          let bleState = await stateMachine.centralManagerStateName
          let blePhase = await stateMachine.currentPhaseName
          let blePeripheralState = await stateMachine.currentPeripheralState ?? "none"
          persistDisconnectDiagnostic(
            "source=activate.autoReconnectSkippedOtherApp, " +
              "device=\(lastDeviceID.uuidString.prefix(8)), " +
              "bleState=\(bleState), " +
              "blePhase=\(blePhase), " +
              "blePeripheralState=\(blePeripheralState), " +
              "intent=\(connectionIntent)"
          )

          // Keep intent so we can retry on foreground/watchdog, but avoid
          // fighting another app's connection on launch.
          startReconnectionWatchdog()
          return
        }

        do {
          try await connect(to: lastDeviceID)
        } catch {
          logger.warning("Auto-reconnect failed: \(error.localizedDescription)")
          let bleState = await stateMachine.centralManagerStateName
          let blePhase = await stateMachine.currentPhaseName
          let blePeripheralState = await stateMachine.currentPeripheralState ?? "none"
          persistDisconnectDiagnostic(
            "source=activate.autoReconnectFailed, " +
              "device=\(lastDeviceID.uuidString.prefix(8)), " +
              "bleState=\(bleState), " +
              "blePhase=\(blePhase), " +
              "blePeripheralState=\(blePeripheralState), " +
              "error=\(error.localizedDescription), " +
              "intent=\(connectionIntent)"
          )
          // A setup-phase auth failure arrives here as a thrown error and never
          // fires onDisconnection, so surface the guided recovery now rather than
          // waiting for the watchdog's first tick. The latch keeps the watchdog
          // from re-presenting it.
          if case BLEError.authenticationFailed = error {
            surfaceAuthenticationFailure(deviceID: lastDeviceID)
          }
          startReconnectionWatchdog()
          // Don't propagate - auto-reconnect failure is not fatal
        }
      } else {
        logger.info("No last connected device - skipping auto-reconnect")
      }
    #endif
  }

  // MARK: - Connection

  /// Connects to a previously paired device.
  ///
  /// This method handles all connection scenarios:
  /// - If disconnected: connects to the device
  /// - If already connected to this device: no-op
  /// - If connected to a different device: switches to the new device
  ///
  /// - Parameters:
  ///   - deviceID: The UUID of the device to connect to
  ///   - forceFullSync: Whether to force a full sync instead of incremental
  ///   - forceReconnect: When `true`, marks the connect as user-initiated: it bypasses the
  ///     circuit breaker, and on a platform without a system pairing registry (macOS) it also
  ///     bounds the retry budget to `unverifiedConnectAttempts`, so a tap on an out-of-range
  ///     radio fails fast instead of hanging the full budget. Background reconnects pass
  ///     `false` to keep the full budget for unattended recovery.
  /// - Throws: Connection errors
  func connect(to deviceID: UUID, forceFullSync: Bool = false, forceReconnect: Bool = false) async throws {
    // Honor cancellation before any state mutation. Without this checkpoint
    // a cancelled `pairNewDevice` task whose preceding awaits all swallowed
    // cancellation would still drive a real BLE connect to completion.
    try Task.checkCancellation()

    // Circuit breaker: prevent rapid reconnection loops after repeated failures
    guard shouldAllowConnection(force: forceReconnect) else {
      logger.info("[BLE] Circuit breaker open, rejecting connection to \(deviceID.uuidString.prefix(8))")
      throw BLEError.connectionFailed("Connection blocked by circuit breaker (cooling down)")
    }

    if activeReconnectDeviceID == deviceID {
      if forceReconnect, connectionState == .disconnected, sessionRebuildDeviceID != deviceID {
        await abandonStuckReconnect(deviceID: deviceID)
      } else {
        connectionIntent = .wantsConnection(forceFullSync: forceFullSync)
        persistIntent()
        if sessionRebuildDeviceID != deviceID {
          reconnectionCoordinator.restartTimeout(deviceID: deviceID)
        }
        logger.info("[BLE] Reconnect already in progress for \(deviceID.uuidString.prefix(8)), deferring duplicate connect request")
        return
      }
    }

    // Prevent concurrent connection attempts
    if connectionState == .connecting {
      let currentDeviceID = activeConnectionAttemptDeviceID

      if currentDeviceID == deviceID {
        if connectingDeviceID == nil {
          // Auto-reconnect same device — refresh UI timeout
          connectionIntent = .wantsConnection(forceFullSync: forceFullSync)
          persistIntent()
          reconnectionCoordinator.restartTimeout(deviceID: deviceID)
        }
        logger.info("Connection already in progress for \(deviceID.uuidString.prefix(8)), ignoring")
        return
      }

      // Different device — cancel current and fall through
      logger.info("Cancelling connection to \(currentDeviceID?.uuidString.prefix(8) ?? "unknown") to connect to \(deviceID.uuidString.prefix(8))")
      connectingDeviceID = nil
      reconnectionCoordinator.cancelTimeout()
      reconnectionCoordinator.clearReconnectingDevice()
      cancelResyncLoop()
      stopReconnectionWatchdog()
      await cleanupResources()
      await transport.disconnect()
      connectionState = .disconnected
    }

    // Handle already-connected cases
    if connectionState != .disconnected {
      if connectedDevice?.id == deviceID {
        logger.info("Already connected to device: \(deviceID)")
        return
      }
      // Connected to different device - switch to new one
      logger.info("Switching from current device to: \(deviceID)")
      try await switchDevice(to: deviceID)
      return
    }

    // Claim the attempt before the pre-connect awaits below. Two connect
    // calls interleaving at those suspension points would otherwise both
    // pass the guards above and build duplicate sessions over one
    // transport. The newest claim wins; a superseded call observes the
    // mismatch after each await and bails.
    if connectingDeviceID == deviceID {
      logger.info("Connection already claimed for \(deviceID.uuidString.prefix(8)), ignoring")
      return
    }
    connectingDeviceID = deviceID

    // Handle state restoration auto-reconnect
    let transportAutoReconnecting = await stateMachine.isAutoReconnecting
    guard connectingDeviceID == deviceID else { throw CancellationError() }
    if transportAutoReconnecting {
      let restoringDeviceID = await stateMachine.connectedDeviceID
      let blePhase = await stateMachine.currentPhaseName
      let blePeripheralState = await stateMachine.currentPeripheralState ?? "none"
      guard connectingDeviceID == deviceID else { throw CancellationError() }

      if restoringDeviceID != deviceID {
        logger.info("Cancelling state restoration auto-reconnect to \(restoringDeviceID?.uuidString ?? "unknown") to connect to \(deviceID)")
        await transport.disconnect()
        guard connectingDeviceID == deviceID else { throw CancellationError() }
      } else if forceReconnect, connectionState == .disconnected {
        // Reached when the coordinator's reconnect cycle was already cleared while
        // the state machine itself is still mid auto-reconnect for this device.
        await abandonStuckReconnect(
          deviceID: deviceID,
          detail: " - blePhase: \(blePhase), blePeripheralState: \(blePeripheralState)"
        )
        guard connectingDeviceID == deviceID else { throw CancellationError() }
      } else {
        // Same device - let auto-reconnect complete instead of racing with it.
        // The reconnection handler will create the session when auto-reconnect succeeds.
        // Release the claim: the reconnect cycle owns identity tracking from here.
        connectingDeviceID = nil
        // Preserve user intent so the watchdog can retry if auto-reconnect fails.
        connectionIntent = .wantsConnection(forceFullSync: forceFullSync)
        persistIntent()
        // Show connecting UI so the user sees their tap did something
        if connectionState != .connecting {
          connectionState = .connecting
        }
        // Re-arm timeout in case the previous one already fired
        reconnectionCoordinator.restartTimeout(deviceID: deviceID)
        logger.warning(
          "[BLE] Deferring to iOS auto-reconnect for device \(deviceID.uuidString.prefix(8)) - connectionState: \(String(describing: connectionState)), blePhase: \(blePhase), blePeripheralState: \(blePeripheralState)"
        )
        return
      }
    }

    // If the user is reconnecting to the last radio and iOS still has a system-level BLE link
    // (common after app updates), adopt the existing link rather than blocking as "connected elsewhere".
    if deviceID == lastConnectedDeviceID {
      connectionIntent = .wantsConnection(forceFullSync: forceFullSync)
      persistIntent()

      if await startAdoptingLastSystemConnectedPeripheralIfAvailable(
        deviceID: deviceID,
        context: "connect(to:)"
      ) {
        // Adoption owns the reconnect cycle now; release the claim.
        if connectingDeviceID == deviceID { connectingDeviceID = nil }
        return
      }
      guard connectingDeviceID == deviceID else { throw CancellationError() }
    }

    // Check for other app connection before changing state
    if await isDeviceConnectedToOtherApp(deviceID) {
      if connectingDeviceID == deviceID { connectingDeviceID = nil }
      throw BLEError.deviceConnectedToOtherApp
    }
    guard connectingDeviceID == deviceID else { throw CancellationError() }

    // Clear intentional disconnect flag before changing state,
    // so the didSet invariant check sees consistent state
    connectionIntent = .wantsConnection(forceFullSync: forceFullSync)
    persistIntent()

    // Set connecting state for immediate UI feedback
    // (connectingDeviceID was already claimed above)
    connectionState = .connecting

    logger.info("Connecting to device: \(deviceID)")

    // Cancel any pending auto-reconnect timeout and clear device identity
    reconnectionCoordinator.cancelTimeout()
    reconnectionCoordinator.clearReconnectingDevice()

    do {
      // Validate device is still registered with the pairing registry. macOS reports
      // no active session, so this guard is skipped and CoreBluetooth resolves the device.
      if pairing.isSessionActive {
        let isConnectable = pairing.isDeviceConnectable(deviceID)

        if !isConnectable {
          await logDeviceNotFoundDiagnostics(deviceID: deviceID, context: "connect(to:) paired accessories mismatch")
          throw ConnectionError.deviceNotFound
        }
      }

      // Attempt connection with retry. Without a system pairing registry (macOS),
      // CoreBluetooth cannot pre-reject an absent saved peripheral, so a user-initiated
      // tap on an out-of-range radio would hang the full retry budget. Bound user taps
      // there; background reconnects (forceReconnect == false) keep the full budget so
      // unattended recovery still gets every attempt.
      let maxAttempts = (forceReconnect && !pairing.hasSystemPairingRegistry)
        ? Self.unverifiedConnectAttempts
        : Self.defaultConnectAttempts
      try await connectWithRetry(deviceID: deviceID, maxAttempts: maxAttempts)
    } catch {
      guard connectingDeviceID == deviceID else {
        logger.info("Connection to \(deviceID.uuidString.prefix(8)) superseded")
        throw error
      }
      if error is CancellationError {
        logger.info("Connection cancelled")
      } else {
        logger.warning("Connection failed: \(error.localizedDescription)")
      }
      connectingDeviceID = nil
      connectionState = .disconnected
      throw error
    }
    connectingDeviceID = nil
  }

  /// Break-glass teardown for a user-initiated (`forceReconnect`) connect that found
  /// a stuck reconnect: the UI already abandoned the cycle (pill shows "Disconnected")
  /// but the OS pending connect never resolved, e.g. the bond was invalidated.
  /// Deferring again would restart the same doomed wait forever, so cancel the cycle
  /// and tear down the pending connect; the caller falls through to a fresh attempt
  /// that surfaces the real CoreBluetooth error instead.
  private func abandonStuckReconnect(deviceID: UUID, detail: String = "") async {
    logger.warning("[BLE] Break-glass: abandoning stuck reconnect for \(deviceID.uuidString.prefix(8)) to allow a fresh connect attempt\(detail)")
    reconnectionCoordinator.cancelTimeout()
    reconnectionCoordinator.clearReconnectingDevice()
    await transport.disconnect()
  }

  /// Disconnects from the current device.
  /// - Parameter reason: The reason for disconnecting (for debugging)
  func disconnect(reason: DisconnectReason = .userInitiated) async {
    let initialState = String(describing: connectionState)
    let transportName = switch currentTransportType {
    case .bluetooth: "bluetooth"
    case .wifi: "wifi"
    case nil: "none"
    }
    let activeDevice = connectedDevice?.id.uuidString.prefix(8) ?? "none"

    logger.info(
      "Disconnecting from device (" +
        "reason: \(reason.rawValue), " +
        "transport: \(transportName), " +
        "device: \(activeDevice), " +
        "initialState: \(initialState), " +
        "intent: \(connectionIntent)" +
        ")"
    )

    // Cancel any pending auto-reconnect timeout and clear device identity
    reconnectionCoordinator.cancelTimeout()
    reconnectionCoordinator.clearReconnectingDevice()
    connectingDeviceID = nil

    // Cancel any WiFi reconnection in progress
    cancelWiFiReconnection()

    // Stop WiFi heartbeat
    stopWiFiHeartbeat()

    // Stop reconnection watchdog
    stopReconnectionWatchdog()

    cancelResyncLoop()
    cancelChannelRetry()

    // Only clear user intent and clean-channel state for explicit disconnects.
    // Transient reasons preserve both so the next reconnect can skip redundant channel sync.
    switch reason {
    case .userInitiated, .statusMenuDisconnectTap, .forgetDevice, .deviceRemovedFromSettings, .factoryReset, .switchingDevice:
      connectionIntent = .userDisconnected
      persistIntent()
      surfacedAuthFailureDeviceID = nil
      lastCleanChannelSync = nil
      lastAttemptedChannelSync = nil
    case .resyncFailed, .wifiAddressChange, .wifiReconnectPrep, .pairingFailed:
      // Preserve .wantsConnection so health check can retry
      break
    }

    // Capture and clear synchronously, mirroring teardownSessionForReconnect:
    // a racing connect can install a new container during the awaits below,
    // and re-reading self.services would tear that new container down.
    let oldServices = services
    let oldSession = session
    services = nil
    session = nil
    logger.info("[BLE] disconnect: state → .disconnected")
    connectionState = .disconnected
    connectingDeviceID = nil
    connectedDevice = nil
    allowedRepeatFreqRanges = []

    if let oldServices {
      // Mark room sessions disconnected before tearing down services
      _ = await oldServices.remoteNodeService.handleBLEDisconnection()
      sessionsAwaitingReauth = []
      await oldServices.tearDown()
      // Reset sync state and clear notification suppression (safety net)
      await oldServices.syncCoordinator.onDisconnected(notificationService: oldServices.notificationService)
    }

    // Stop session
    await oldSession?.stop()

    // Disconnect appropriate transport based on current type
    if let wifiTransport {
      await wifiTransport.disconnect()
      self.wifiTransport = nil
    } else {
      await transport.disconnect()
    }

    // Clear transport type
    currentTransportType = nil

    persistDisconnectDiagnostic(
      "source=disconnect(reason), " +
        "reason=\(reason.rawValue), " +
        "transport=\(transportName), " +
        "device=\(activeDevice), " +
        "initialState=\(initialState), " +
        "finalState=\(String(describing: connectionState)), " +
        "intent=\(connectionIntent)"
    )

    logger.info(
      "Disconnected (" +
        "reason: \(reason.rawValue), " +
        "transport: \(transportName), " +
        "device: \(activeDevice), " +
        "initialState: \(initialState), " +
        "finalState: \(String(describing: connectionState)), " +
        "intent: \(connectionIntent)" +
        ")"
    )
  }

  // MARK: - Simulator

  /// Connects to the simulator device with mock data.
  /// Used for simulator builds and demo mode on device.
  func simulatorConnect() async throws {
    logger.info("Starting simulator connection")

    connectionIntent = .wantsConnection()
    persistIntent()
    connectingDeviceID = MockDataProvider.simulatorDeviceID
    connectionState = .connecting

    do {
      // Connect simulator mode
      await simulatorMode.connect()

      // Create services with a placeholder session
      // Note: We need a MeshCoreSession but won't actually use it for communication
      // The mock data is seeded directly into the persistence store
      let mockTransport = SimulatorMockTransport()
      let session = MeshCoreSession(transport: mockTransport)
      self.session = session

      // Create services
      let newServices = ServiceContainer(
        session: session,
        modelContainer: modelContainer,
        radioID: MockDataProvider.simulatorDeviceID,
        appStateProvider: appStateProvider,
        connectionStateEvents: connectionStateEvents,
        initialConnectionState: connectionState
      )
      await wireCleanChannelSyncCallback(on: newServices)
      await newServices.chatSendQueueService.hydrate()
      services = newServices

      // Seed mock data
      try await simulatorMode.seedDataStore(newServices.dataStore)

      // Set connected device
      connectedDevice = MockDataProvider.simulatorDevice

      // Persist for auto-reconnect
      persistConnection(
        deviceID: MockDataProvider.simulatorDeviceID,
        radioID: MockDataProvider.simulatorDeviceID,
        deviceName: "MeshCore One Sim"
      )

      // Notify observers
      await onConnectionReady?()

      connectingDeviceID = nil
      connectionState = .ready
      await onDeviceSynced?()
      logger.info("Simulator connection complete")
    } catch {
      connectingDeviceID = nil
      await cleanupConnection()
      throw error
    }
  }

  // MARK: - Device Switching

  /// Switches to a different device.
  ///
  /// - Parameter deviceID: UUID of the new device to connect to
  func switchDevice(to deviceID: UUID) async throws {
    logger.info("Switching to device: \(deviceID)")
    lastCleanChannelSync = nil
    lastAttemptedChannelSync = nil

    // Update intent
    connectionIntent = .wantsConnection()
    persistIntent()

    do {
      // Validate device is registered with the pairing registry. Skipped on macOS,
      // which has no active session; CoreBluetooth resolves the device on connect.
      if pairing.isSessionActive {
        let isConnectable = pairing.isDeviceConnectable(deviceID)
        if !isConnectable {
          await logDeviceNotFoundDiagnostics(deviceID: deviceID, context: "switchDevice paired accessories mismatch")
          throw ConnectionError.deviceNotFound
        }
      }

      // Cancel any resync loop from the old device before teardown
      cancelResyncLoop()

      // Reset sync state before destroying services to prevent stuck "Syncing" pill
      if let services {
        await services.syncCoordinator.onDisconnected(notificationService: services.notificationService)
      }

      // Stop current services
      await services?.tearDown()
      // tearDown above cancelled the old container's connection-state
      // subscription; nilling drops the dead reference before the
      // connect ceremony installs its replacement.
      services = nil
      await session?.stop()

      // Switch transport
      logger.info("[BLE] switchDevice: state → .connecting for device: \(deviceID.uuidString.prefix(8))")
      connectionState = .connecting
      try await transport.switchDevice(to: deviceID)
      logger.info("[BLE] switchDevice: state → .connected for device: \(deviceID.uuidString.prefix(8))")
      connectionState = .connected

      // Re-create session with existing transport
      let newSession = MeshCoreSession(transport: transport)
      session = newSession

      let (meshCoreSelfInfo, deviceCapabilities) = try await initializeSession(newSession)

      // Session traffic flowed over the encrypted UART link, so the bond is
      // proven healthy as of now.
      recordBondVerification(deviceID: deviceID)

      // Configure BLE write pacing based on device platform
      await configureBLEPacing(for: deviceCapabilities)

      let (newServices, radioID) = try await buildServicesAndSaveDevice(
        deviceID: deviceID,
        session: newSession,
        selfInfo: meshCoreSelfInfo,
        capabilities: deviceCapabilities,
        connectionMethods: Self.bleConnectionMethods(for: deviceID)
      )

      // Persist connection for auto-reconnect
      persistConnection(deviceID: deviceID, radioID: radioID, deviceName: meshCoreSelfInfo.name)

      // Notify observers before sync starts so they can wire callbacks
      await onConnectionReady?()
      let syncSucceeded = await performInitialSync(radioID: radioID, services: newServices, context: "Device switch", forceFullSync: true)

      guard await promoteToReady(syncSucceeded: syncSucceeded, expectedServices: newServices, transportType: .bluetooth) else { return }

      stopReconnectionWatchdog()
      logger.info("Device switch complete - device ready")
    } catch {
      // Without this, a partial switch would leave services, session, and
      // connectedDevice pointing at the old device with state stuck on
      // .connecting or .connected. pairNewDevice routes through here when
      // an old radio is still connected, so the failure alert's recovery
      // actions need clean state.
      await cleanupConnection()
      await transport.disconnect()
      // cleanupConnection tore down the session without firing onConnectionLost,
      // so observers (and the radio's Live Activity) would otherwise be stranded
      // on the old device's "connected" state. Route through the same callback the
      // transport-loss and reconnect-failure paths use.
      await onConnectionLost?()
      throw error
    }
  }
}
