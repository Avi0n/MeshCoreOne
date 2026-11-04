import Foundation

extension ConnectionManager {
  @discardableResult
  func beginOwnedRetirement(
    previousRequest: ConnectionRequest?,
    phase: DeviceConnectionState,
    closePhysical: Bool,
    retainMembership: Bool,
    closeUnboundPhysical: Bool = false
  ) -> ConnectionRetirement {
    let predecessor = retirementTask
    let oldLink = installedLink
    let receipt = ConnectionLossReceipt(requestID: desiredRequest?.id ?? UUID(), retiredGeneration: oldLink?.generation)
    currentLossReceipt = receipt
    let candidate = previousRequest?.attempt?.resource
    let candidateServices = previousRequest?.attempt?.preparedServices
    let oldContext = oldLink.map { link in
      let request = previousRequest?.id == link.requestID ? previousRequest : nil
      return lifecycleContext(request: request, attempt: request?.attempt, link: link)
    }
    let candidateContext = candidate.map {
      lifecycleContext(request: previousRequest, attempt: previousRequest?.attempt, link: nil, resource: $0)
    }
    let retirementReason = closePhysical ? "closePhysical" : "preservePhysical"
    if let oldContext { logLifecycle("dispose", context: oldContext, reason: retirementReason) }
    if let candidateContext, candidate !== oldLink?.resource {
      logLifecycle("candidateDispose", context: candidateContext, reason: retirementReason)
    }
    let closeUnboundBLE = closePhysical && oldLink == nil && candidate == nil
      && (closeUnboundPhysical || previousRequest?.handedOff == true)
      && connectionTeardownCount == 0
    oldLink?.resource.revoke()
    candidate?.revoke()
    previousRequest?.binding?.revoke()
    oldLink?.setupWorker?.cancel()
    oldLink?.completionWorker?.cancel()
    oldLink?.listenerRepairWorker?.cancel()
    stopWiFiHeartbeat()
    cancelResyncLoop()
    cancelChannelRetry()
    let workers = retiringWorkers
    retiringWorkers.removeAll()
    if !retainMembership {
      membershipBatch = nil
    } else if let oldLink {
      let retained = membershipBatch?.radioID == oldLink.device.radioID ? membershipBatch?.sessionIDs ?? [] : []
      membershipBatch = MembershipBatch(id: UUID(), radioID: oldLink.device.radioID, sessionIDs: retained)
    }
    detachPublication(phase: phase)
    beginConnectionTeardown()
    let worker = Task { @MainActor in
      defer { self.endConnectionTeardown() }
      await predecessor?.value
      #if DEBUG
        await self.testConnectionTeardownHandler?()
      #endif
      if let oldLink {
        let ids = await oldLink.services.remoteNodeService.handleLinkDisconnection(radioID: oldLink.device.radioID)
        if retainMembership, self.membershipBatch?.radioID == oldLink.device.radioID {
          self.membershipBatch?.sessionIDs.formUnion(ids)
        }
        oldLink.services.settingsService.radioWiFiState.noteLinkEnded(contextID: oldLink.services.settingsService.radioWiFiContextID)
        async let settingsRetired: Void = oldLink.services.settingsService.retireRadioWiFiOperations()
        await oldLink.resource.retire(closePhysical: closePhysical)
        await settingsRetired
        await oldLink.setupWorker?.value
        _ = await oldLink.completionWorker?.value
        _ = await oldLink.listenerRepairWorker?.value
        await oldLink.services.tearDown()
        await oldLink.services.syncCoordinator.onDisconnected(notificationService: oldLink.services.notificationService)
      }
      if let candidateServices, candidateServices !== oldLink?.services {
        async let settingsRetired: Void = candidateServices.settingsService.retireRadioWiFiOperations()
        if let candidate, candidate !== oldLink?.resource {
          await candidate.retire(closePhysical: closePhysical)
        }
        await settingsRetired
        await candidateServices.tearDown()
      } else if let candidate, candidate !== oldLink?.resource {
        await candidate.retire(closePhysical: closePhysical)
      }
      for worker in workers {
        await worker.value
      }
      if closeUnboundBLE { await self.transport.disconnect() }
      if let binding = oldLink?.resource.binding ?? candidate?.binding {
        await self.stateMachine.setAppSessionLive(deviceID: nil, sessionBinding: binding)
      }
      if let oldContext { self.logLifecycle("retired", context: oldContext, reason: retirementReason) }
      if let candidateContext, candidate !== oldLink?.resource {
        self.logLifecycle("candidateRetired", context: candidateContext, reason: retirementReason)
      }
    }
    retirementTask = worker
    return ConnectionRetirement(worker: worker, receipt: receipt)
  }

  func retireCandidate(_ request: ConnectionRequest, attempt: ConnectionAttempt, closePhysical: Bool) async {
    let resource = attempt.resource
    let prepared = attempt.preparedServices
    let context = lifecycleContext(request: request, attempt: attempt, link: nil, resource: resource)
    let retirementReason = closePhysical ? "closePhysical" : "preservePhysical"
    logLifecycle("candidateDispose", context: context, reason: retirementReason)
    attempt.binding.revoke()
    #if DEBUG
      await testCandidateRetirementHandler?(attempt.binding)
    #endif
    async let settingsRetired: Void? = prepared?.settingsService.retireRadioWiFiOperations()
    await resource?.retire(closePhysical: closePhysical)
    await settingsRetired
    await prepared?.tearDown()
    logLifecycle("candidateRetired", context: context, reason: retirementReason)
    if request.attempt === attempt { request.attempt = nil }
  }

  func beginConnectionTeardown() {
    connectionTeardownCount += 1
  }

  func endConnectionTeardown() {
    connectionTeardownCount -= 1
  }
}
