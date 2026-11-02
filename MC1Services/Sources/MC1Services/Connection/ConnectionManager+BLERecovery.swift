import Foundation

extension ConnectionManager {
  func wireReconnectPolicy() {
    reconnectionCoordinator.onRebuild = { [weak self] cycle in
      guard let self else { throw CancellationError() }
      try await self.rebuildBLEStack(cycle)
    }
    reconnectionCoordinator.onExhausted = { [weak self] cycle in
      await self?.finishBLERecoveryFailure(cycle)
    }
    reconnectionCoordinator.onTimeout = { [weak self] cycle in
      guard let self, self.reconnectionCoordinator.isCurrent(cycle),
            let request = self.desiredRequest, request.id == cycle.requestID,
            self.installedLink == nil else { return }
      self.publishPending(request, phase: .disconnected)
      guard let receipt = self.currentLossReceipt, self.isCurrent(receipt) else { return }
      await self.onConnectionLost?(receipt)
    }
    reconnectionCoordinator.isAutoReconnecting = { [stateMachine] in await stateMachine.isAutoReconnecting }
  }

  func handleBLELifecycleEvent(_ event: BLELifecycleEvent) async {
    #if DEBUG
      defer { testBLELifecycleEventHandled?(event) }
    #endif
    let origin = event.origin
    guard let request = desiredRequest, request.target == .bluetooth(origin.deviceID),
          connectionIntent.wantsConnection,
          origin.eventTime >= request.admittedAt || request.physicalOrigin?.generation == origin.generation else { return }
    if let accepted = request.physicalOrigin, origin.generation < accepted.generation { return }
    switch event {
    case .remoteLinkLost:
      notePowerOffRemoteLoss(deviceID: origin.deviceID, eventTime: origin.eventTime)
    case let .autoReconnecting(_, errorInfo):
      persistDisconnectDiagnostic("source=ble.autoReconnecting, device=\(origin.deviceID.uuidString.prefix(8)), error=\(errorInfo)")
      if shouldDeferOpportunisticReconnect, request.origin != .pairing {
        await retireLostBLE(request, origin: origin, error: nil)
        return
      }
      guard !powerOffSuppressesReconnect(for: origin.deviceID) else { return }
      await enterBLERecovery(request, origin: origin)
    case .reconnected:
      guard let cycle = reconnectionCoordinator.currentCycle, cycle.requestID == request.id,
            request.physicalOrigin?.generation == origin.generation else { return }
      await reconnectionCoordinator.complete(cycle)
    case let .disconnected(_, error):
      await retireLostBLE(request, origin: origin, error: error)
    }
  }

  func enterBLERecovery(_ request: ConnectionRequest, origin: BLELifecycleOrigin) async {
    guard owns(request) else { return }
    if let cycle = reconnectionCoordinator.currentCycle,
       cycle.requestID == request.id, request.physicalOrigin?.generation == origin.generation { return }
    request.physicalOrigin = origin
    request.handedOff = true
    request.binding?.revoke()
    let cycle = reconnectionCoordinator.begin(deviceID: origin.deviceID, requestID: request.id)
    let barrier = beginOwnedRetirement(previousRequest: request, phase: .connecting, closePhysical: false, retainMembership: true)
    await onAutoReconnectStarted?(barrier.receipt)
    await barrier.value
    guard owns(request), reconnectionCoordinator.isCurrent(cycle), installedLink == nil else { return }
    reconnectionCoordinator.armTimeout(cycle)
  }

  func rebuildBLEStack(_ cycle: BLEReconnectionCoordinator.Cycle) async throws {
    guard let request = desiredRequest, request.id == cycle.requestID,
          reconnectionCoordinator.isCurrent(cycle), connectionIntent.wantsConnection,
          !powerOffBlocksReconnect else { throw CancellationError() }
    await retirementTask?.value
    try requireOwnership(request)
    guard reconnectionCoordinator.isCurrent(cycle) else { throw CancellationError() }
    _ = try await establishAttempt(request, preservePhysicalBLE: true)
    try requireOwnership(request)
  }

  func finishBLERecoveryFailure(_ cycle: BLEReconnectionCoordinator.Cycle) async {
    guard let request = desiredRequest, request.id == cycle.requestID,
          reconnectionCoordinator.isCurrent(cycle) else { return }
    if connectionIntent.wantsConnection { consecutiveRebuildFailures += 1 }
    let connected = await stateMachine.isConnected
    let reconnecting = await stateMachine.isAutoReconnecting
    guard owns(request), reconnectionCoordinator.isCurrent(cycle) else { return }
    let preserve = connectionIntent.wantsConnection && (connected || reconnecting)
      && consecutiveRebuildFailures <= Self.maxRebuildFailuresPreservingLink
    if !preserve, connected || reconnecting,
       consecutiveRebuildFailures > Self.maxRebuildFailuresPreservingLink {
      persistDisconnectDiagnostic("source=ble.preserveBudgetExhausted, device=\(cycle.deviceID.uuidString.prefix(8))")
    }
    let barrier = beginOwnedRetirement(previousRequest: request, phase: .disconnected, closePhysical: !preserve, retainMembership: true)
    await barrier.value
    guard isCurrent(barrier.receipt) else { return }
    await onConnectionLost?(barrier.receipt)
    guard isCurrent(barrier.receipt), connectionIntent.wantsConnection else { return }
    if !preserve || reconnectionWatchdogTask == nil { startReconnectionWatchdog() }
  }

  private func retireLostBLE(_ request: ConnectionRequest, origin: BLELifecycleOrigin, error: BLEError?) async {
    guard owns(request) else { return }
    if let accepted = request.physicalOrigin, accepted.generation > origin.generation { return }
    reconnectionCoordinator.clear()
    let barrier = beginOwnedRetirement(previousRequest: request, phase: .disconnected, closePhysical: false, retainMembership: true)
    await barrier.value
    guard isCurrent(barrier.receipt) else { return }
    await onConnectionLost?(barrier.receipt)
    guard owns(request) else { return }
    if case .authenticationFailed = error { surfaceAuthenticationFailure(deviceID: origin.deviceID) }
    if connectionIntent.wantsConnection { startReconnectionWatchdog() }
  }
}
