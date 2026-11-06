import Foundation
import MeshCore

public extension ConnectionManager {
  internal var activeConnectionAttemptDeviceID: UUID? {
    desiredRequest?.attempt?.identity.deviceID ?? desiredRequest?.target.deviceID ?? reconnectionCoordinator.reconnectingDeviceID
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
    let diagnostics = await stateMachine.linkDiagnostics
    let bleState = diagnostics.centralState
    let blePhase = diagnostics.phase
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
    let diagnostics = await stateMachine.linkDiagnostics
    let bleState = diagnostics.centralState
    let blePhase = diagnostics.phase
    logger.info(
      "[BLE] Lifecycle transition complete: active health check finished, " +
        "connectionState: \(String(describing: connectionState)), " +
        "bleState: \(bleState), " +
        "blePhase: \(blePhase)"
    )

    guard currentTransportType == nil || currentTransportType == .bluetooth, connectionIntent.wantsConnection, connectionState == .disconnected else { return }

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
    loadTextRules()

    // Ensure lifecycle handlers are installed before anything below can
    // construct the CBCentralManager; creating the central can fire
    // restoration callbacks synchronously, and a missed handler would
    // strand the restored link without a session rebuild.
    let activationRequestID = desiredRequest?.id
    await wireTransportHandlers()

    let lastDeviceShort = lastConnectedDeviceID?.uuidString.prefix(8) ?? "none"
    let bleState = await stateMachine.linkDiagnostics.centralState
    logger.info("""
    Activating ConnectionManager - \
    connectionIntent: \(connectionIntent), \
    lastConnectedDeviceID: \(lastDeviceShort), \
    connectionState: \(String(describing: connectionState)), \
    bleState: \(bleState)
    """)

    // Reset stale room session connections from previous app launch
    try? await persistenceStore.resetAllRemoteNodeSessionConnections()

    // Populate radioID on existing devices and backfill deduplication keys (one-time migration)
    do {
      try await persistenceStore.performRadioIDMigration()
    } catch {
      logger.error("radioID migration failed: \(error)")
    }

    // Promote legacy per-channel region overrides to `.specific` mode so the
    // corrective flood-scope semantics don't reinterpret them as `.inherit`.
    do {
      try await persistenceStore.performChannelFloodScopeMigration()
    } catch {
      logger.error("channel flood-scope migration failed: \(error)")
    }

    // Zero accumulated unread counts on repeater-type contacts and repeater-role
    // sessions so the badge stops including invisible records.
    do {
      try await persistenceStore.performRepeaterUnreadCountMigration()
    } catch {
      logger.error("repeater unread-count migration failed: \(error)")
    }

    // Copy existing contact favorites into the local chat pin once.
    do {
      try await persistenceStore.performChatPinMigration()
    } catch {
      logger.error("chat pin migration failed: \(error)")
    }

    // Backfill sortDate from createdAt on pre-existing messages so date-header
    // grouping keeps their current display order.
    do {
      try await persistenceStore.performSortDateBackfillMigration()
    } catch {
      logger.error("sortDate backfill migration failed: \(error)")
    }

    // Re-normalize sortDate to createdAt for installs whose backfill already ran
    // before the block-at-reconnect change, un-burying any rows an interim build
    // sorted by send time. Must run before stateMachine.activate() so no
    // restoration-driven sync writes a fresh anchor before this resets the baseline.
    do {
      try await persistenceStore.performSortDateResetMigration()
    } catch {
      logger.error("sortDate reset migration failed: \(error)")
    }

    guard desiredRequest?.id == activationRequestID else { return }

    if desiredRequest != nil {
      guard connectionIntent.wantsConnection else { return }
      do {
        try await pairing.activate()
        Task { await resumePendingRadioErases(pairingReady: true) }
      } catch {
        logger.error("Failed to activate pairing session: \(error.localizedDescription)")
        await resumePendingRadioErases(pairingReady: false)
      }
      guard desiredRequest?.id == activationRequestID else { return }
      await checkWiFiConnectionHealth()
      await checkBLEConnectionHealth()
      return
    }

    #if targetEnvironment(simulator)
      Task { await resumePendingRadioErases(pairingReady: true) }
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
        Task { await resumePendingRadioErases(pairingReady: true) }
      } catch {
        logger.error("Failed to activate pairing session: \(error.localizedDescription)")
        await resumePendingRadioErases(pairingReady: false)
      }

      // Skip auto-reconnect if user explicitly disconnected
      if connectionIntent.isUserDisconnected {
        logger.info("Skipping auto-reconnect: user previously disconnected")
        return
      }

      guard desiredRequest?.id == activationRequestID else { return }

      // Auto-reconnect to last device if available
      if let lastDeviceID = lastConnectedDeviceID {
        logger.info("Attempting auto-reconnect to last device: \(lastDeviceID)")
        connectionIntent = .wantsConnection()
        let device = try? await persistenceStore.fetchDevice(id: lastDeviceID)
        guard desiredRequest?.id == activationRequestID else { return }
        await reconnectLastUsedTransport(device: device, lastDeviceID: lastDeviceID)
      } else {
        logger.info("No last connected device - skipping auto-reconnect")
      }
    #endif
  }
}
