import Foundation
import MeshCore

extension ConnectionManager {
  enum IdentityReceiptFailure: Error {
    case staleSource
    case ambiguousIdentity
  }

  func reconcileImportedIdentity(
    receipt: IdentityImportReceipt, selfInfo: SelfInfo, request: ConnectionRequest,
    attempt: ConnectionAttempt, store: PersistenceStore, cancelled: @Sendable () -> Bool
  ) async throws -> DeviceDTO {
    try requireIdentityReceipt(receipt, request: request, attempt: attempt)
    guard !cancelled() else { throw CancellationError() }
    guard let imported = try await validateIdentitySource(receipt, selfInfo: selfInfo, store: store) else {
      consumeIdentityReceipt(receipt)
      throw IdentityReceiptFailure.staleSource
    }
    #if DEBUG
      await testIdentityAfterCommit?()
    #endif
    await transferRadioWiFiEvidence(from: receipt.originalRadioID, to: imported.radioID)
    consumeIdentityReceipt(receipt)
    if receipt.originalPublicKey != selfInfo.publicKey {
      await dropStaleVContact(dataStore: store, radioID: receipt.originalRadioID, oldSelfPublicKey: receipt.originalPublicKey)
    }
    return imported
  }

  func reconcilePendingIdentity(
    request: ConnectionRequest, attempt: ConnectionAttempt, deviceID: UUID, selfInfo: SelfInfo
  ) async throws -> Bool {
    let receipt: IdentityImportReceipt
    if let exact = pendingIdentityReconciliations[deviceID], exact.publicKey != nil || exact.target == attempt.target {
      receipt = exact
    } else if let source = pendingIdentityReconciliations.values.first(where: { $0.target == attempt.target }) {
      receipt = source
    } else {
      let matches = pendingIdentityReconciliations.values.filter { $0.publicKey == selfInfo.publicKey }
      guard !matches.isEmpty else { return false }
      guard matches.count == 1, let match = matches.first else { throw IdentityReceiptFailure.ambiguousIdentity }
      receipt = match
    }
    let work = enqueueMetadata { store in
      try self.requireIdentityReceipt(receipt, request: request, attempt: attempt)
      if receipt.publicKey == selfInfo.publicKey {
        _ = try await self.reconcileImportedIdentity(
          receipt: receipt, selfInfo: selfInfo, request: request, attempt: attempt, store: store, cancelled: { false }
        )
        return true
      }
      guard try await self.validateIdentitySource(receipt, selfInfo: nil, store: store) != nil else {
        self.consumeIdentityReceipt(receipt)
        throw IdentityReceiptFailure.staleSource
      }
      try self.requireIdentityReceipt(receipt, request: request, attempt: attempt)
      if receipt.publicKey == nil, receipt.originalPublicKey == selfInfo.publicKey {
        self.consumeIdentityReceipt(receipt)
        return false
      }
      if receipt.publicKey == nil {
        throw IdentityImportRecoveryRequired(recovery: receipt.recovery(currentRequestID: self.connectionRequestID))
      }
      throw IdentityReceiptFailure.ambiguousIdentity
    }
    return try await work.value
  }

  func validateIdentitySource(
    _ receipt: IdentityImportReceipt, selfInfo: SelfInfo?, store: PersistenceStore
  ) async throws -> DeviceDTO? {
    #if DEBUG
      await testIdentityBeforeSourceValidation?()
    #endif
    return try await store.importConnectionIdentity(
      deviceID: receipt.originalDeviceID, expectedRadioID: receipt.originalRadioID,
      expectedPublicKey: receipt.originalPublicKey, selfInfo: selfInfo
    )
  }

  func requireIdentityReceipt(
    _ receipt: IdentityImportReceipt, request: ConnectionRequest, attempt: ConnectionAttempt
  ) throws {
    try requireAttemptOwnership(request, attempt)
    guard pendingIdentityReconciliations[receipt.originalDeviceID]?.receiptID == receipt.receiptID else {
      throw CancellationError()
    }
    if request.removedIdentities.contains(where: { $0.matches(receipt.originalIdentity) }) { throw CancellationError() }
  }

  func consumeIdentityReceipt(_ receipt: IdentityImportReceipt) {
    if pendingIdentityReconciliations[receipt.originalDeviceID]?.receiptID == receipt.receiptID {
      pendingIdentityReconciliations[receipt.originalDeviceID] = nil
    }
  }

  func isTerminalIdentityError(_ error: any Error) -> Bool {
    error is IdentityImportRecoveryRequired || error is IdentityReceiptFailure
  }

  func identityFailureForPresentation(_ error: any Error) -> any Error {
    guard let failure = error as? IdentityReceiptFailure else { return error }
    switch failure {
    case .staleSource: return ConnectionError.deviceNotFound
    case .ambiguousIdentity: return ConnectionError.notConnected
    }
  }

  private func dropStaleVContact(dataStore: PersistenceStore, radioID: UUID, oldSelfPublicKey: Data) async {
    guard let oldVKey = VContactIdentity.publicKey(forSelfPublicKey: oldSelfPublicKey) else { return }
    do {
      guard let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: oldVKey) else { return }
      try await dataStore.deleteContact(id: contact.id)
    } catch {
      logger.warning("Failed to drop stale V-contact after identity rotation: \(error.localizedDescription)")
    }
  }
}
