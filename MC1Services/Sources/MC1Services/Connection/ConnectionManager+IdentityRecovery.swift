import Foundation
import MeshCore
import os

public extension ConnectionManager {
  var identityImportRecoveries: [IdentityImportRecovery] {
    pendingIdentityReconciliations.values.filter { $0.publicKey == nil }
      .map { $0.recovery(currentRequestID: connectionRequestID) }
      .sorted { $0.receiptID.uuidString < $1.receiptID.uuidString }
  }

  func prepareIdentityImportRecovery(receiptID: UUID) -> IdentityImportRecovery? {
    identityImportRecoveries.first { $0.receiptID == receiptID }
  }

  func retryIdentityImport(
    confirmation: IdentityImportRecovery, privateKey: Data
  ) async throws -> ConnectionResult {
    try Task.checkCancellation()
    guard privateKey.count == ProtocolLimits.privateKeySize,
          prepareIdentityImportRecovery(receiptID: confirmation.receiptID) == confirmation,
          let receipt = pendingIdentityReconciliations[confirmation.originalDeviceID],
          receipt.receiptID == confirmation.receiptID else { throw CancellationError() }
    let preflight = enqueueMetadata { store in
      try Task.checkCancellation()
      guard self.prepareIdentityImportRecovery(receiptID: confirmation.receiptID) == confirmation else {
        throw CancellationError()
      }
      let source = try await self.validateIdentitySource(receipt, selfInfo: nil, store: store)
      try Task.checkCancellation()
      guard self.prepareIdentityImportRecovery(receiptID: confirmation.receiptID) == confirmation else {
        throw CancellationError()
      }
      guard source != nil else {
        self.consumeIdentityReceipt(receipt)
        throw ConnectionError.deviceNotFound
      }
    }
    try await withTaskCancellationHandler {
      try await preflight.value
    } onCancel: {
      preflight.cancel()
    }
    try Task.checkCancellation()
    guard prepareIdentityImportRecovery(receiptID: confirmation.receiptID) == confirmation else {
      throw CancellationError()
    }
    return try await requestConnection(
      targets: [receipt.target], origin: .explicit, forceFullSync: false, forceReconnect: true,
      operation: { request in
        try await self.runIdentityRecovery(request, receipt: receipt, privateKey: privateKey)
      }
    )
  }

  private func runIdentityRecovery(
    _ request: ConnectionRequest, receipt: IdentityImportReceipt, privateKey: Data
  ) async throws -> ConnectionResult {
    let attempt = ConnectionAttempt(target: receipt.target)
    attempt.identity = receipt.originalIdentity
    installAttempt(attempt, on: request, ownedByAdmissionWorker: true)
    do {
      let resource = try await openConnectionResource(request, attempt: attempt, preservePhysicalBLE: false)
      _ = try await initializeSession(resource.session, attempt: attempt)
      try requireIdentityReceipt(receipt, request: request, attempt: attempt)
      let cancelled = OSAllocatedUnfairLock(initialState: false)
      let job = enqueueMetadata { store in
        guard !cancelled.withLock({ $0 }) else { throw CancellationError() }
        try self.requireIdentityReceipt(receipt, request: request, attempt: attempt)
        guard try await self.validateIdentitySource(receipt, selfInfo: nil, store: store) != nil else {
          self.consumeIdentityReceipt(receipt)
          throw IdentityReceiptFailure.staleSource
        }
        try self.requireIdentityReceipt(receipt, request: request, attempt: attempt)
        try await resource.session.prepareForIdentityImport()
        try self.requireIdentityReceipt(receipt, request: request, attempt: attempt)
        let info = try await resource.session.importPrivateKeyForReconnection(
          privateKey, binding: resource.binding, onAcknowledged: {}
        )
        if self.pendingIdentityReconciliations[receipt.originalDeviceID]?.receiptID == receipt.receiptID {
          self.pendingIdentityReconciliations[receipt.originalDeviceID]?.publicKey = info.publicKey
        }
        attempt.identity.publicKey = info.publicKey
        #if DEBUG
          await self.testIdentityBeforeCommit?()
        #endif
        let device = try await self.reconcileImportedIdentity(
          receipt: receipt, selfInfo: info, request: request, attempt: attempt, store: store,
          cancelled: { cancelled.withLock { $0 } }
        )
        attempt.identity = ConnectionDeviceIdentity(device)
        return try await self.rebuildImportedIdentity(
          request, attempt: attempt, previousResource: resource, device: device, info: info,
          retainedSource: nil, repeatRanges: nil, cancelled: { cancelled.withLock { $0 } }
        )
      }
      let prepared = try await withTaskCancellationHandler {
        try await job.value
      } onCancel: {
        cancelled.withLock { $0 = true }
      }
      let link = try await completeImportedIdentity(
        request, attempt: prepared.attempt, resource: prepared.resource, prepared: prepared.prepared
      )
      guard owns(request), owns(link) else { return .superseded }
      return .operational(link.snapshot)
    } catch {
      let failure = settleIdentityImportFailure(error, receipt: receipt, preservingEarlierReceipt: true)
      if request.removedIdentities.contains(where: { $0.matches(receipt.originalIdentity) }) { consumeIdentityReceipt(receipt) }
      await retireCandidate(request, attempt: attempt, closePhysical: true)
      if owns(request), request.attempt == nil { _ = beginDisconnect(reason: .userInitiated) }
      if let pending = pendingIdentityReconciliations[receipt.originalDeviceID], pending.publicKey == nil,
         !(error is IdentityImportReconnectionError) {
        throw IdentityImportRecoveryRequired(recovery: pending.recovery(currentRequestID: connectionRequestID))
      }
      throw failure
    }
  }
}
