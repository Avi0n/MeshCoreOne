import Foundation
import MC1Services

extension NodeConfigImportViewModel {
  func beginImport() {
    guard !isApplying else { return }
    let source = connectionManager?.currentConnection.map { ImportSource($0) }
    if recovery != nil || inputSource != source {
      resetToFileSelection()
    }
    recovery = nil
  }

  @discardableResult
  func beginRecovery(_ summary: IdentityImportRecovery) -> Bool {
    guard !isApplying, let current = connectionManager?.prepareIdentityImportRecovery(receiptID: summary.receiptID) else {
      return false
    }
    parseTask?.cancel()
    currentParseID = nil
    previewTask?.cancel()
    currentPreviewID = nil
    cancelConfirmation()
    if inputReceiptID != current.receiptID {
      resetToFileSelection()
    }
    recovery = current
    identityRecoveryComplete = false
    errorMessage = L10n.Settings.ConfigImport.identityOutcomeUnknown(current.sourceName)
    return true
  }

  func prepareRecoveryConfirmation() {
    guard let recovery, let manager = connectionManager,
          let current = manager.prepareIdentityImportRecovery(receiptID: recovery.receiptID) else { return }
    guard inputReceiptID == current.receiptID, validatedRecoveryKey != nil else {
      errorMessage = L10n.Settings.ConfigImport.identityRecoveryKeyRequired
      showFilePicker = true
      return
    }
    self.recovery = current
    confirmation = .recovery(current, inputID: inputID)
    showConfirmation = true
  }

  var validatedRecoveryKey: Data? {
    guard let hex = importedConfig?.privateKey, hex.allSatisfy(\.isHexDigit),
          let key = Data(hexString: hex), key.count == ProtocolLimits.privateKeySize else { return nil }
    return key
  }

  func applyIdentityRecovery(_ summary: IdentityImportRecovery, selectedInputID: UUID) {
    guard inputID == selectedInputID, inputReceiptID == summary.receiptID,
          recovery?.receiptID == summary.receiptID, let manager = connectionManager,
          manager.prepareIdentityImportRecovery(receiptID: summary.receiptID) == summary,
          let key = validatedRecoveryKey else { return }
    isApplying = true
    errorMessage = nil
    identityRecoveryComplete = false
    importComplete = false
    importTask = Task {
      do {
        let result = try await manager.retryIdentityImport(confirmation: summary, privateKey: key)
        guard inputID == selectedInputID, recovery?.receiptID == summary.receiptID else { return }
        isApplying = false
        if case .operational = result, manager.isCurrent(result) {
          identityRecoveryComplete = true
        } else if manager.identityImportRecoveries.contains(where: { $0.receiptID == summary.receiptID }) {
          errorMessage = L10n.Settings.ConfigImport.identityOutcomeUnknown(summary.sourceName)
        }
      } catch {
        guard inputID == selectedInputID, recovery?.receiptID == summary.receiptID else { return }
        isApplying = false
        if let interrupted = error as? IdentityImportRecoveryRequired {
          retainInterruptedInput(interrupted.recovery, selectedInputID: selectedInputID)
        } else if error is CancellationError {
          errorMessage = L10n.Settings.ConfigImport.identityOutcomeUnknown(summary.sourceName)
        } else {
          errorMessage = error.userFacingMessage
        }
      }
    }
  }

  func retainInterruptedInput(_ summary: IdentityImportRecovery, selectedInputID: UUID) {
    guard inputID == selectedInputID else { return }
    inputReceiptID = summary.receiptID
    recovery = summary
    importComplete = false
    errorMessage = L10n.Settings.ConfigImport.identityOutcomeUnknown(summary.sourceName)
  }
}
