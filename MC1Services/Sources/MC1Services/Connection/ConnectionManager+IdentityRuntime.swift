import MeshCore

extension ConnectionManager {
  func rebuildImportedIdentity(
    _ request: ConnectionRequest, attempt: ConnectionAttempt, previousResource: ConnectionResource,
    device: DeviceDTO, info: SelfInfo,
    retainedSource: ConnectionLink?, repeatRanges: [MeshCore.FrequencyRange]?,
    cancelled: @Sendable () -> Bool
  ) async throws -> (attempt: ConnectionAttempt, resource: ConnectionResource, prepared: PreparedConnection) {
    try requireAttemptOwnership(request, attempt)
    guard !cancelled() else { throw CancellationError() }
    if let retainedSource, installedLink === retainedSource {
      // Set the flag before detachPublication. The device nils in that call, and the
      // settings observers read the flag when they see the nil device.
      identityRebuildUnpublished = true
      detachPublication(phase: .connected)
    }
    await previousResource.retire(closePhysical: false)
    await retainedSource?.services.tearDown()
    try requireAttemptOwnership(request, attempt)
    guard !cancelled() else { throw CancellationError() }
    let replacement = ConnectionAttempt(target: attempt.target)
    replacement.identity = ConnectionDeviceIdentity(device)
    installAttempt(replacement, on: request, ownedByAdmissionWorker: true)
    func requireRuntimeOwnership() throws {
      try requireAttemptOwnership(request, replacement)
      guard !cancelled() else { throw CancellationError() }
    }
    do {
      let resource = try await openConnectionResource(
        request, attempt: replacement, preservePhysicalBLE: attempt.target.transportType == .bluetooth
      )
      let (freshInfo, capabilities) = try await initializeSession(resource.session, attempt: replacement)
      guard freshInfo.publicKey == info.publicKey else { throw ConnectionError.notConnected }
      try requireRuntimeOwnership()
      if let bluetoothID = replacement.target.deviceID {
        await recordBondVerification(deviceID: bluetoothID, binding: resource.binding)
        try requireRuntimeOwnership()
        await configureBLEPacing(for: capabilities, binding: resource.binding)
      }
      try requireRuntimeOwnership()
      let container = try await buildServiceContainer(
        request, attempt: replacement, resource: resource, device: device
      )
      try requireRuntimeOwnership()
      let ranges: [MeshCore.FrequencyRange] = if let repeatRanges { repeatRanges } else {
        await capabilities.clientRepeat ? ((try? resource.session.getRepeatFreq()) ?? []) : []
      }
      try requireRuntimeOwnership()
      return (replacement, resource, PreparedConnection(services: container, device: device, repeatRanges: ranges))
    } catch {
      await retireCandidate(request, attempt: replacement, closePhysical: true)
      throw error
    }
  }

  func completeImportedIdentity(
    _ request: ConnectionRequest, attempt: ConnectionAttempt,
    resource: ConnectionResource, prepared: PreparedConnection
  ) async throws -> ConnectionLink {
    do {
      guard owns(request), request.attempt === attempt, resource.binding.isActive else { throw CancellationError() }
      let finalization = enqueueMetadata { store in
        await resource.session.waitForQuiescence()
        try self.requireAttemptOwnership(request, attempt)
        try resource.binding.checkActive()
        #if DEBUG
          try await self.testConnectionSelectionHandler?(request.id)
        #endif
        try self.requireAttemptOwnership(request, attempt)
        try resource.binding.checkActive()
        let selected = try await store.selectConnectionDevice(id: prepared.device.id, method: attempt.target.method)
        try self.requireAttemptOwnership(request, attempt)
        try resource.binding.checkActive()
        let link = ConnectionLink(
          requestID: request.id, target: attempt.target, resource: resource, services: prepared.services, device: selected
        )
        try self.publish(link, repeatRanges: prepared.repeatRanges)
        self.persistConnection(deviceID: selected.id, radioID: selected.radioID, deviceName: selected.nodeName)
        request.committedGeneration = link.generation
        attempt.resource = nil
        attempt.preparedServices = nil
        return link
      }
      let link = try await finalization.value
      guard ownsRuntime(link), resource.binding.isActive else { throw CancellationError() }
      await beginConnectionSetup(request, link: link).value
      #if DEBUG
        await testPostSetupResultHandler?(link.generation)
      #endif
      guard ownsRuntime(link), resource.binding.isActive else { throw CancellationError() }
      return link
    } catch {
      let retirement = owns(request) && request.attempt === attempt
        ? beginDisconnect(reason: .userInitiated).barrier : nil
      await retireCandidate(request, attempt: attempt, closePhysical: true)
      await retirement?.value
      throw error
    }
  }
}
