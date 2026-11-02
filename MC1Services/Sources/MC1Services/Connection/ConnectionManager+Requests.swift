import Foundation
import MeshCore

private enum AdmissionWorkerInstallation {
  @TaskLocal static var isActive = false
}

extension ConnectionManager {
  public var connectionRequestID: UUID? {
    desiredRequest?.id
  }

  public func isCurrent(_ failure: ConnectionRequestFailure) -> Bool {
    desiredRequest?.id == failure.requestID
  }

  func owns(_ request: ConnectionRequest) -> Bool {
    desiredRequest === request
  }

  func requireOwnership(_ request: ConnectionRequest) throws {
    do {
      try Task.checkCancellation()
    } catch {
      logLifecycle("admissionRejected", context: lifecycleContext(request: request, attempt: request.attempt, link: nil), reason: "cancelledCaller")
      throw error
    }
    guard owns(request) else {
      logLifecycle("admissionRejected", context: lifecycleContext(request: request, attempt: request.attempt, link: nil), reason: "supersededRequest")
      throw CancellationError()
    }
  }

  func requireAttemptOwnership(_ request: ConnectionRequest, _ attempt: ConnectionAttempt) throws {
    // Admission follows this task, the current request, and this attempt.
    try requireOwnership(request)
    guard request.attempt === attempt else {
      logLifecycle("admissionRejected", context: lifecycleContext(request: request, attempt: attempt, link: nil), reason: "supersededAttempt")
      throw CancellationError()
    }
    if request.removedIdentities.contains(where: { $0.matches(attempt.identity) }) {
      logLifecycle("admissionRejected", context: lifecycleContext(request: request, attempt: attempt, link: nil), reason: "removedIdentity")
      _ = beginDisconnect(reason: .forgetDevice)
      throw CancellationError()
    }
  }

  func requestConnection(
    targets: [ConnectionRequest.Target],
    origin: ConnectionRequest.Origin,
    forceFullSync: Bool,
    forceReconnect: Bool,
    operation: (@MainActor @Sendable (ConnectionRequest) async throws -> ConnectionResult)? = nil
  ) async throws -> ConnectionResult {
    try Task.checkCancellation()
    if let link = installedLink, targets.first == link.target, !forceReconnect, !forceFullSync,
       connectionState.isOperational {
      return .operational(link.snapshot)
    }
    if let request = desiredRequest, request.target == targets.first,
       let cycle = reconnectionCoordinator.currentCycle, cycle.requestID == request.id,
       !forceReconnect || connectionState != .disconnected || reconnectionCoordinator.isRebuilding(cycle) {
      if connectionState == .connecting, !reconnectionCoordinator.isRebuilding(cycle) {
        reconnectionCoordinator.restartTimeout(deviceID: cycle.deviceID)
      }
      return .handoff(requestID: request.id)
    }
    if origin == .explicit || origin == .pairing,
       case let .bluetooth(deviceID) = targets.first,
       pairing.isSessionActive, !pairing.isDeviceConnectable(deviceID) {
      throw ConnectionError.deviceNotFound
    }
    let previous = desiredRequest
    let request = ConnectionRequest(
      targets: targets, origin: origin, forceFullSync: forceFullSync, forceReconnect: forceReconnect,
      continuing: origin == .recovery || origin == .launch ? previous : nil
    )
    if origin == .explicit || origin == .pairing { cancelExpectedWiFiReboots() }
    desiredRequest = request
    logLifecycle("admitted", context: lifecycleContext(request: request, attempt: nil, link: nil))
    invalidatePowerOffForConnectionChange()
    previous?.binding?.revoke()
    previous?.worker?.cancel()
    reconnectionCoordinator.cancelTimeout()
    reconnectionCoordinator.clear()
    stopReconnectionWatchdog()
    cancelWiFiReconnection()
    connectionIntent = .wantsConnection(forceFullSync: forceFullSync)
    persistIntent()
    let keepMembership = origin == .recovery || targets.first?.transportType == .wifi || installedLink.map { link in
      targets.contains(link.target) || targets.contains { target in
        target.deviceID == link.device.id || target.method == link.device.wifiConnectionMethod
      }
    } ?? false
    if !keepMembership { membershipBatch = nil }
    let barrier = beginOwnedRetirement(
      previousRequest: previous, phase: .connecting, closePhysical: true, retainMembership: keepMembership
    )
    let worker = Task { @MainActor in
      try await AdmissionWorkerInstallation.$isActive.withValue(true) {
        // Ownership first so a superseded worker cannot publish into its successor.
        // A current cancellation joins this admission's retirement, then uses the ordinary outcome.
        guard self.owns(request) else { return ConnectionResult.superseded }
        if Task.isCancelled {
          await barrier.value
          return await self.settleCurrentCancellation(request)
        }
        await barrier.value
        guard self.owns(request) else { return ConnectionResult.superseded }
        if Task.isCancelled {
          return await self.settleCurrentCancellation(request)
        }
        await self.metadataCommitTask?.value
        guard self.owns(request) else { return ConnectionResult.superseded }
        if Task.isCancelled {
          return await self.settleCurrentCancellation(request)
        }
        if let operation { return try await operation(request) }
        return try await self.runConnectionRequest(request)
      }
    }
    request.worker = worker
    return try await withTaskCancellationHandler {
      let result = try await worker.value
      if Task.isCancelled { return .cancelled }
      return result
    } onCancel: {
      Task { @MainActor [weak self] in
        guard let self, self.owns(request), request.committedGeneration == nil else { return }
        request.worker?.cancel()
        #if DEBUG
          self.testAdmissionCancellationFinished = false
          await self.testAdmissionCancellationHandler?()
        #endif
        guard self.owns(request), request.committedGeneration == nil else { return }
        request.admissionBinding?.revoke()
        #if DEBUG
          self.testAdmissionCancellationFinished = true
        #endif
      }
    }
  }

  func installAttempt(_ attempt: ConnectionAttempt, on request: ConnectionRequest, ownedByAdmissionWorker: Bool) {
    request.attempt = attempt
    if ownedByAdmissionWorker {
      request.admissionBinding = attempt.binding
    }
  }

  private func runConnectionRequest(_ request: ConnectionRequest) async throws -> ConnectionResult {
    var lastError: any Error = ConnectionError.deviceNotFound
    var lastBluetoothError: (any Error)?
    for index in request.targets.indices {
      request.targetIndex = index
      do {
        try requireOwnership(request)
        if request.target == .simulator { return try await establishSimulator(request) }
        if case let .bluetooth(deviceID) = request.target,
           try await handOffBluetoothIfNeeded(request, deviceID: deviceID) {
          return .handoff(requestID: request.id)
        }
        let count = request.target.transportType == .bluetooth
          ? ((request.forceReconnect && !pairing.hasSystemPairingRegistry) ? Self.unverifiedConnectAttempts : Self.defaultConnectAttempts)
          : 1
        for attempt in 1...count {
          do {
            return try await establishAttempt(request, preservePhysicalBLE: false)
          } catch {
            lastError = error
            if let failure = error as? ConnectionRequestFailure { throw failure }
            guard owns(request) else { return .superseded }
            if request.handedOff { return .handoff(requestID: request.id) }
            try requireOwnership(request)
            if isTerminalConnectError(error) || attempt == count { throw error }
            let delay = Self.connectRetryBaseDelay * pow(2, Double(attempt - 1))
            try await Task.sleep(for: .seconds(delay))
          }
        }
      } catch {
        lastError = error
        if let failure = error as? ConnectionRequestFailure { throw failure }
        guard owns(request) else { return .superseded }
        if request.handedOff { return .handoff(requestID: request.id) }
        if error is CancellationError || Task.isCancelled {
          return await settleCurrentCancellation(request)
        }
        if case .bluetooth = request.target { lastBluetoothError = error }
      }
    }
    guard owns(request) else { return .superseded }
    if let lastBluetoothError {
      switch lastBluetoothError {
      case BLEError.bluetoothPoweredOff, BLEError.bluetoothUnavailable, BLEError.bluetoothUnauthorized: break
      default: recordConnectionFailure()
      }
    }
    if request.origin == .launch,
       let bluetoothIndex = request.targets.firstIndex(where: { $0.transportType == .bluetooth }) {
      request.targetIndex = bluetoothIndex
    }
    publishPending(request, phase: .disconnected)
    if let receipt = currentLossReceipt, isCurrent(receipt) { await onConnectionLost?(receipt) }
    throw ConnectionRequestFailure(requestID: request.id, underlying: lastError)
  }

  static let connectRetryBaseDelay: TimeInterval = 0.3

  private func settleCurrentCancellation(_ request: ConnectionRequest) async -> ConnectionResult {
    guard owns(request) else { return .superseded }
    publishPending(request, phase: .disconnected)
    if let receipt = currentLossReceipt, isCurrent(receipt) { await onConnectionLost?(receipt) }
    return .cancelled
  }

  private func isTerminalConnectError(_ error: any Error) -> Bool {
    guard let error = error as? BLEError else { return error is CancellationError }
    return switch error {
    case .authenticationFailed, .bluetoothPoweredOff, .bluetoothUnavailable, .bluetoothUnauthorized,
         .deviceConnectedToOtherApp, .deviceNotFound: true
    default: false
    }
  }

  func establishAttempt(_ request: ConnectionRequest, preservePhysicalBLE: Bool) async throws -> ConnectionResult {
    try requireOwnership(request)
    let predecessor = retirementTask
    await predecessor?.value
    try requireOwnership(request)
    await metadataCommitTask?.value
    try requireOwnership(request)
    let attempt = ConnectionAttempt(target: request.target)
    installAttempt(attempt, on: request, ownedByAdmissionWorker: AdmissionWorkerInstallation.isActive)
    logLifecycle("attemptStarted", context: lifecycleContext(request: request, attempt: attempt, link: nil))
    let binding = attempt.binding
    do {
      let resource = try await openConnectionResource(request, attempt: attempt, preservePhysicalBLE: preservePhysicalBLE)
      let newSession = resource.session
      let (selfInfo, capabilities) = try await initializeSession(newSession, attempt: attempt)
      attempt.identity.publicKey = selfInfo.publicKey
      try requireAttemptOwnership(request, attempt)
      if attempt.target.transportType == .bluetooth {
        guard let deviceID = attempt.target.deviceID else { throw CancellationError() }
        await recordBondVerification(deviceID: deviceID, binding: binding)
        try requireAttemptOwnership(request, attempt)
        await configureBLEPacing(for: capabilities, binding: binding)
      } else {
        detectAndStorePlatform(model: capabilities.model, transportType: .wifi)
      }
      try requireAttemptOwnership(request, attempt)
      let prepared = try await prepareConnection(request, attempt: attempt, resource: resource, selfInfo: selfInfo, capabilities: capabilities)
      try requireAttemptOwnership(request, attempt)
      let link = ConnectionLink(
        requestID: request.id, target: attempt.target, resource: resource, services: prepared.services, device: prepared.device
      )
      try await commitConnection(request, attempt: attempt, link: link, repeatRanges: prepared.repeatRanges)
      try requireAttemptOwnership(request, attempt)
      guard owns(request), installedLink === link else { return resultForRetiredLink(request) }
      let setup = beginConnectionSetup(request, link: link)
      await setup.value
      #if DEBUG
        await testPostSetupResultHandler?(link.generation)
      #endif
      guard owns(request), installedLink === link else { return resultForRetiredLink(request) }
      recordConnectionSuccess()
      stopReconnectionWatchdog()
      return .operational(link.snapshot)
    } catch {
      // Retirement revokes every binding, so classify an already-lost owned attempt before cleanup.
      let establishmentError: any Error = if error is CancellationError, !binding.isActive, owns(request), request.attempt === attempt,
                                             !Task.isCancelled, request.worker?.isCancelled != true, !request.handedOff {
        ConnectionError.notConnected
      } else {
        error
      }
      let handedOff = owns(request) && request.handedOff && attempt.target.transportType == .bluetooth
      await retireCandidate(request, attempt: attempt, closePhysical: !preservePhysicalBLE && !handedOff)
      if isTerminalIdentityError(error) {
        let failureRequestID = owns(request) ? beginDisconnect(reason: .userInitiated).receipt.requestID : request.id
        throw ConnectionRequestFailure(requestID: failureRequestID, underlying: identityFailureForPresentation(error))
      }
      throw establishmentError
    }
  }

  func beginConnectionSetup(_ request: ConnectionRequest, link: ConnectionLink) -> Task<Void, Never> {
    let setup = Task { @MainActor in
      guard self.ownsRuntime(link), !Task.isCancelled else { return }
      await link.services.chatSendQueueService.hydrate()
      guard self.ownsRuntime(link), !Task.isCancelled else { return }
      await self.onConnectionReady?(link.snapshot)
      guard self.ownsRuntime(link), self.owns(request), !Task.isCancelled else { return }
      let forceFullSync = request.forceFullSync
      request.forceFullSync = false
      self.connectionIntent = .wantsConnection()
      let syncResult = await self.performInitialSync(
        radioID: link.device.radioID, services: link.services, transportType: link.target.transportType,
        forceFullSync: forceFullSync
      )
      _ = await self.completeSync(link, syncResult: syncResult)
    }
    link.setupWorker = setup
    return setup
  }

  private func resultForRetiredLink(_ request: ConnectionRequest) -> ConnectionResult {
    guard owns(request) else { return .superseded }
    if let link = installedLink, link.requestID == request.id { return .operational(link.snapshot) }
    return .handoff(requestID: request.id)
  }

  func wireSocketLoss(_ resource: ConnectionResource) async {
    guard let socket = resource.wifi else { return }
    let binding = resource.binding
    await socket.setDisconnectionHandler { [weak self, weak resource, weak socket] error, eventTime in
      binding.revoke()
      Task { @MainActor in
        guard let self, let resource, let socket,
              self.installedLink?.resource === resource else { return }
        await self.handleWiFiDisconnection(error: error, remoteLossAt: eventTime, source: socket)
      }
    }
  }
}
