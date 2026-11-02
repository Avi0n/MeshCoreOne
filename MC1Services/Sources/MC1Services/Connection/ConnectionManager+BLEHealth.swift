import Foundation

extension ConnectionManager {
  func handOffBluetoothIfNeeded(_ request: ConnectionRequest, deviceID: UUID) async throws -> Bool {
    await stateMachine.activate()
    try requireOwnership(request)
    let autoReconnecting = await stateMachine.isAutoReconnecting
    let connectedID = await stateMachine.connectedDeviceID
    let connected = await stateMachine.isConnected
    try requireOwnership(request)
    if autoReconnecting || connected, connectedID == deviceID, !request.forceReconnect {
      if let origin = await transport.lifecycleOrigin, origin.deviceID == deviceID {
        try requireOwnership(request)
        if let accepted = request.physicalOrigin, accepted.generation > origin.generation { return true }
        await enterBLERecovery(request, origin: origin)
        if let cycle = reconnectionCoordinator.currentCycle, cycle.requestID == request.id {
          try await reconcileBLERecoveryCompletion(request, cycle: cycle, origin: origin)
        }
        return true
      }
    }
    if try await startAdoptingSystemLink(request, deviceID: deviceID) { return true }
    let heldElsewhere = await isDeviceConnectedToOtherApp(deviceID)
    try requireOwnership(request)
    if heldElsewhere, !isOwnedSystemConnectedLink(deviceID) || request.origin == .recovery {
      persistDisconnectDiagnostic("source=ble.otherAppConnected, device=\(deviceID.uuidString.prefix(8))")
      throw BLEError.deviceConnectedToOtherApp
    }
    return false
  }

  private func startAdoptingSystemLink(_ request: ConnectionRequest, deviceID: UUID) async throws -> Bool {
    guard isOwnedSystemConnectedLink(deviceID) else { return false }
    let diagnostics = await stateMachine.linkDiagnostics
    try requireOwnership(request)
    guard diagnostics.phase == .idle else { return false }
    let systemConnected = await stateMachine.isDeviceConnectedToSystem(deviceID)
    try requireOwnership(request)
    guard systemConnected else { return false }
    persistDisconnectDiagnostic("source=ble.adoptSystemConnectedPeripheral, device=\(deviceID.uuidString.prefix(8))")
    request.handedOff = true
    let cycle = reconnectionCoordinator.begin(deviceID: deviceID, requestID: request.id)
    publishPending(request, phase: .connecting)
    let started = await stateMachine.startAdoptingSystemConnectedPeripheral(deviceID)
    try requireOwnership(request)
    if !started {
      request.handedOff = false
      reconnectionCoordinator.clear()
      return false
    }
    let origin = await transport.lifecycleOrigin
    try requireOwnership(request)
    guard reconnectionCoordinator.isCurrent(cycle) else { return true }
    if let origin, origin.deviceID == deviceID {
      request.physicalOrigin = origin
      try await reconcileBLERecoveryCompletion(request, cycle: cycle, origin: origin)
    } else {
      reconnectionCoordinator.armTimeout(cycle)
    }
    return true
  }

  private func reconcileBLERecoveryCompletion(
    _ request: ConnectionRequest,
    cycle: BLEReconnectionCoordinator.Cycle,
    origin: BLELifecycleOrigin
  ) async throws {
    let connected = await stateMachine.isConnected
    let currentOrigin = await transport.lifecycleOrigin
    try requireOwnership(request)
    guard reconnectionCoordinator.isCurrent(cycle), cycle.requestID == request.id,
          cycle.deviceID == origin.deviceID,
          request.physicalOrigin?.deviceID == origin.deviceID,
          request.physicalOrigin?.generation == origin.generation,
          currentOrigin?.deviceID == origin.deviceID,
          currentOrigin?.generation == origin.generation else { return }
    if connected {
      Task { await self.reconnectionCoordinator.complete(cycle) }
    } else {
      reconnectionCoordinator.armTimeout(cycle)
    }
  }

  public func checkBLEConnectionHealth() async {
    guard !shouldDeferBLEHealthReconciliation, connectionIntent.wantsConnection,
          currentTransportType != .wifi, let deviceID = opportunisticBluetoothDeviceID else { return }
    let observedRequest = desiredRequest?.id
    if await shouldStandDownOpportunisticBluetoothReconnect(for: deviceID) { return }
    guard canReconcileBLEHealth(deviceID: deviceID, requestID: observedRequest) else { return }
    let connected = await stateMachine.isConnected
    let connectedID = await stateMachine.connectedDeviceID
    guard canReconcileBLEHealth(deviceID: deviceID, requestID: observedRequest) else { return }
    if connected, connectedID == deviceID {
      if let link = installedLink {
        _ = await reconcileConnectedListeners(for: link)
        return
      }
      let origin = await transport.lifecycleOrigin
      guard canReconcileBLEHealth(deviceID: deviceID, requestID: observedRequest), let origin else { return }
      let request: ConnectionRequest
      if let current = desiredRequest, current.target == .bluetooth(deviceID) {
        request = current
      } else {
        request = ConnectionRequest(
          targets: [.bluetooth(deviceID)], origin: .recovery, forceFullSync: false, forceReconnect: false,
          continuing: desiredRequest
        )
        desiredRequest = request
      }
      await enterBLERecovery(request, origin: origin)
      guard let cycle = reconnectionCoordinator.currentCycle, owns(request) else { return }
      await reconnectionCoordinator.complete(cycle)
      return
    }
    let autoReconnecting = await stateMachine.isAutoReconnecting
    let poweredOff = await stateMachine.isBluetoothPoweredOff
    guard canReconcileBLEHealth(deviceID: deviceID, requestID: observedRequest), !autoReconnecting, !poweredOff else { return }
    if connectionState != .disconnected, let request = desiredRequest {
      let barrier = beginOwnedRetirement(previousRequest: request, phase: .disconnected, closePhysical: true, retainMembership: true)
      await barrier.value
      guard owns(request), isCurrent(barrier.receipt),
            canReconcileBLEHealth(deviceID: deviceID, requestID: observedRequest) else { return }
      await onConnectionLost?(barrier.receipt)
      guard owns(request), isCurrent(barrier.receipt),
            canReconcileBLEHealth(deviceID: deviceID, requestID: observedRequest) else { return }
    }
    do {
      _ = try await requestConnection(targets: [.bluetooth(deviceID)], origin: .recovery, forceFullSync: false, forceReconnect: false)
    } catch let failure as ConnectionRequestFailure {
      guard isCurrent(failure) else { return }
      if case BLEError.authenticationFailed = failure.underlying {
        surfaceAuthenticationFailure(deviceID: deviceID)
      } else if connectionIntent.wantsConnection {
        startReconnectionWatchdog()
      }
    } catch { logger.warning("BLE health recovery failed: \(error.localizedDescription)") }
  }

  private var shouldDeferBLEHealthReconciliation: Bool {
    if shouldDeferOpportunisticReconnect || connectionTeardownCount > 0 { return true }
    guard installedLink == nil, let request = desiredRequest else { return false }
    if request.attempt?.resource != nil { return true }
    return request.worker != nil && request.committedGeneration == nil && connectionState != .disconnected
  }

  private func canReconcileBLEHealth(deviceID: UUID, requestID: UUID?) -> Bool {
    desiredRequest?.id == requestID && !shouldDeferBLEHealthReconciliation
      && connectionIntent.wantsConnection && currentTransportType != .wifi
      && reconnectionCoordinator.currentCycle == nil
      && (desiredRequest == nil || desiredRequest?.target == .bluetooth(deviceID))
  }

  private var opportunisticBluetoothDeviceID: UUID? {
    if let request = desiredRequest {
      guard case let .bluetooth(id) = request.target else { return nil }
      return id
    }
    return lastConnectedDeviceID
  }

  func shouldStandDownOpportunisticBluetoothReconnect(for deviceID: UUID) async -> Bool {
    if let request = desiredRequest { return request.target != .bluetooth(deviceID) }
    guard let device = try? await persistenceStore.fetchDevice(id: deviceID) else { return false }
    if case .wifi = device.lastUsedConnectionMethod { return true }
    return false
  }

  /// Bluetooth radio came back while the user still wants a connection.
  /// Follows the admitted Bluetooth choice, or saved selection without a request.
  func handleBluetoothPoweredOn() async {
    #if DEBUG
      defer { testBluetoothPoweredOnHandlerRuns += 1 }
    #endif
    guard connectionIntent.wantsConnection,
          connectionState == .disconnected,
          let deviceID = opportunisticBluetoothDeviceID else { return }

    if shouldDeferOpportunisticReconnect {
      logger.info("[BLE] Bluetooth powered on: standing down (pairing in progress)")
      return
    }

    let observedRequestID = desiredRequest?.id
    let blePhase = await stateMachine.linkDiagnostics.phase
    let bleConnectedDeviceID = await stateMachine.connectedDeviceID
    guard canReconcileBLEHealth(deviceID: deviceID, requestID: observedRequestID) else { return }
    if blePhase != .idle || bleConnectedDeviceID == deviceID {
      logger.info(
        "[BLE] Bluetooth powered on: BLE already owns reconnect flow for \(deviceID.uuidString.prefix(8)) " +
          "(phase: \(blePhase), bleConnectedDevice: \(bleConnectedDeviceID?.uuidString.prefix(8) ?? "none"))"
      )
      return
    }

    if await shouldStandDownOpportunisticBluetoothReconnect(for: deviceID) {
      return
    }

    guard canReconcileBLEHealth(deviceID: deviceID, requestID: observedRequestID) else { return }
    logger.info("[BLE] Bluetooth powered on: attempting reconnection to \(deviceID.uuidString.prefix(8))")
    await attemptOpportunisticReconnect(deviceID: deviceID, reason: "Bluetooth powered on")
  }

  /// Admits Bluetooth recovery only for the current choice while lifecycle work permits it.
  func attemptOpportunisticReconnect(deviceID: UUID, reason: String) async {
    guard !shouldDeferBLEHealthReconciliation, connectionIntent.wantsConnection,
          opportunisticBluetoothDeviceID == deviceID else {
      logger.info("[BLE] Opportunistic reconnect skipped (\(reason)): target or lifecycle changed")
      return
    }
    do {
      _ = try await requestConnection(targets: [.bluetooth(deviceID)], origin: .recovery, forceFullSync: false, forceReconnect: false)
    } catch let failure as ConnectionRequestFailure {
      guard isCurrent(failure) else { return }
      if case BLEError.authenticationFailed = failure.underlying {
        surfaceAuthenticationFailure(deviceID: deviceID)
      } else if connectionIntent.wantsConnection {
        startReconnectionWatchdog()
      }
      logger.warning("Opportunistic reconnect (\(reason)) failed: \(failure.localizedDescription)")
    } catch {
      logger.warning("Opportunistic reconnect (\(reason)) failed: \(error.localizedDescription)")
    }
  }

  /// Routes authentication failures through one latch so each failure episode
  /// presents recovery at most once for the affected Device ID.
  func surfaceAuthenticationFailure(deviceID: UUID) {
    guard surfacedAuthFailureDeviceID != deviceID else {
      logger.info("[BLE] Pairing-failure recovery already surfaced for \(deviceID.uuidString.prefix(8)), suppressing repeat")
      return
    }
    surfacedAuthFailureDeviceID = deviceID
    onAuthenticationFailure?(deviceID)
  }

  /// Clears the auth-failure surfacing latch so the next failure episode for the
  /// same device re-presents the guided recovery.
  public func clearSurfacedAuthenticationFailure() {
    surfacedAuthFailureDeviceID = nil
  }
}
