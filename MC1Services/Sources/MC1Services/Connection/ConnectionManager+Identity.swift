import Foundation
import MeshCore
import os

extension ConnectionManager {
  func importIdentity(_ key: Data, expectedServices: ServiceContainer, deviceID: UUID) async throws -> UUID? {
    try Task.checkCancellation()
    guard key.count == ProtocolLimits.privateKeySize else { throw ConnectionError.notConnected }
    guard let source = installedLink, source.services === expectedServices, source.device.id == deviceID,
          let request = desiredRequest, owns(source) else { throw CancellationError() }
    #if DEBUG
      await testIdentityBeforeAdmission?()
    #endif
    let cancelled = OSAllocatedUnfairLock(initialState: false)
    let acknowledged = OSAllocatedUnfairLock(initialState: false)
    let job = enqueueMetadata { store in
      try await self.performIdentityImport(
        key, source: source, request: request, store: store,
        cancelled: { cancelled.withLock { $0 } }, acknowledged: acknowledged
      )
    }
    do {
      let result = try await withTaskCancellationHandler {
        try await job.value
      } onCancel: {
        cancelled.withLock { $0 = true }
      }
      let completion: @Sendable () async throws -> Void = { [weak self] in
        guard let self else { throw CancellationError() }
        _ = try await self.completeImportedIdentity(
          request, attempt: result.attempt, resource: result.resource, prepared: result.prepared
        )
      }
      let deferred = await source.services.nodeConfigService.adoptImportRuntime(
        session: result.resource.session, settingsService: result.prepared.services.settingsService,
        channelService: result.prepared.services.channelService,
        syncCoordinator: result.prepared.services.syncCoordinator,
        contactRadio: result.prepared.services.contactService, onImportFinished: completion
      )
      if deferred {
        try requireAttemptOwnership(request, result.attempt)
        try result.resource.binding.checkActive()
      } else {
        try await completion()
        try Task.checkCancellation()
      }
      return result.prepared.device.radioID
    } catch {
      if acknowledged.withLock({ $0 }) { throw ConfigIdentityImportFailure(underlying: error) }
      throw error
    }
  }

  private func performIdentityImport(
    _ key: Data, source: ConnectionLink, request: ConnectionRequest, store: PersistenceStore,
    cancelled: @escaping @Sendable () -> Bool, acknowledged: OSAllocatedUnfairLock<Bool>
  ) async throws -> (attempt: ConnectionAttempt, resource: ConnectionResource, prepared: PreparedConnection) {
    guard owns(source), !cancelled(), let method = source.target.method else { throw CancellationError() }
    let repeatRanges = allowedRepeatFreqRanges
    let attempt = ConnectionAttempt(target: source.target)
    attempt.identity = ConnectionDeviceIdentity(source.device)
    installAttempt(attempt, on: request, ownedByAdmissionWorker: true)
    let settings = source.services.settingsService
    let receipt = IdentityImportReceipt(device: source.device, method: method)
    transition(source, to: .connected)
    stopWiFiHeartbeat()
    cancelResyncLoop()
    cancelChannelRetry()
    settings.radioWiFiBinding.state.closeAdmission()
    do {
      await source.services.nodeConfigService.retirePublicAdmission()
      try await source.resource.session.prepareForIdentityImport()
      await source.resource.cancelUnsubmittedWrites()
      await settings.retireRadioWiFiOperations()
      source.listenerRepairWorker?.cancel()
      await source.services.prepareForIdentityImport()
      _ = await source.listenerRepairWorker?.value
      try requireAttemptOwnership(request, attempt)
      guard !cancelled() else { throw CancellationError() }
      guard try await validateIdentitySource(receipt, selfInfo: nil, store: store) != nil else {
        throw IdentityReceiptFailure.staleSource
      }
      try requireAttemptOwnership(request, attempt)
      guard !cancelled() else { throw CancellationError() }
      pendingIdentityReconciliations[source.device.id] = receipt
      let info = try await source.resource.session.importPrivateKeyForReconnection(
        key, binding: source.resource.binding, onAcknowledged: { acknowledged.withLock { $0 = true } }
      )
      pendingIdentityReconciliations[source.device.id]?.publicKey = info.publicKey
      attempt.identity.publicKey = info.publicKey
      #if DEBUG
        await testIdentityBeforeCommit?()
      #endif
      let device = try await reconcileImportedIdentity(
        receipt: receipt, selfInfo: info, request: request, attempt: attempt, store: store, cancelled: cancelled
      )
      attempt.identity = ConnectionDeviceIdentity(device)
      return try await rebuildImportedIdentity(
        request, attempt: attempt, previousResource: source.resource, device: device,
        info: info, retainedSource: source, repeatRanges: repeatRanges, cancelled: cancelled
      )
    } catch {
      let failure = settleIdentityImportFailure(error, receipt: receipt, preservingEarlierReceipt: false)
      if request.removedIdentities.contains(where: { $0.matches(receipt.originalIdentity) }) { consumeIdentityReceipt(receipt) }
      if installedLink === source { detachPublication(phase: .disconnected) }
      await source.resource.retire(closePhysical: true)
      await source.services.tearDown()
      await retireCandidate(request, attempt: attempt, closePhysical: true)
      if owns(request), request.attempt == nil { _ = beginDisconnect(reason: .userInitiated) }
      if let unresolved = pendingIdentityReconciliations[source.device.id], unresolved.publicKey == nil {
        throw IdentityImportRecoveryRequired(recovery: unresolved.recovery(currentRequestID: connectionRequestID))
      }
      throw failure
    }
  }

  func settleIdentityImportFailure(
    _ error: any Error, receipt: IdentityImportReceipt, preservingEarlierReceipt: Bool
  ) -> any Error {
    guard let failure = error as? IdentityImportReconnectionError else { return identityFailureForPresentation(error) }
    if !preservingEarlierReceipt, pendingIdentityReconciliations[receipt.originalDeviceID]?.receiptID == receipt.receiptID {
      pendingIdentityReconciliations[receipt.originalDeviceID] = nil
    }
    switch failure {
    case .uncertainResponseHistory: return ConnectionError.notConnected
    case let .rejected(underlying): return underlying
    }
  }
}
