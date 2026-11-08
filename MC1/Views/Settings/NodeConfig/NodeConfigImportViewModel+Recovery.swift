import Foundation
import MC1Services

extension NodeConfigImportViewModel {
  func beginImport() {
    guard !isApplying else { return }
    let source = connectionManager?.currentConnection.map { ImportSource($0) }
    if recovery != nil || inputSource != source {
      resetToFileSelection()
    }
  }

  @discardableResult
  func beginRecovery(_ summary: IdentityImportRecovery) -> Bool {
    guard !isApplying, let current = connectionManager?.prepareIdentityImportRecovery(receiptID: summary.receiptID) else {
      return false
    }
    retireInputWork()
    if inputReceiptID != current.receiptID {
      resetToFileSelection()
    }
    state = .recovery(current, input: state.input)
    errorMessage = L10n.Settings.ConfigImport.identityOutcomeUnknown(current.sourceName)
    return true
  }

  func prepareRecoveryConfirmation() {
    guard let recovery, let manager = connectionManager,
          let current = manager.prepareIdentityImportRecovery(receiptID: recovery.receiptID) else { return }
    guard inputReceiptID == current.receiptID, let input = state.input, let key = validatedRecoveryKey else {
      errorMessage = L10n.Settings.ConfigImport.identityRecoveryKeyRequired
      showFilePicker = true
      return
    }
    state = .confirming(.recovery(current, input: input, privateKey: key))
    showConfirmation = true
  }

  var validatedRecoveryKey: Data? {
    guard let hex = importedConfig?.privateKey, hex.allSatisfy(\.isHexDigit),
          let key = Data(hexString: hex), key.count == ProtocolLimits.privateKeySize else { return nil }
    return key
  }

  func applyIdentityRecovery(_ summary: IdentityImportRecovery, input: NodeConfigImportState.Input, privateKey: Data) {
    guard inputID == input.id, inputReceiptID == summary.receiptID, let manager = connectionManager,
          manager.prepareIdentityImportRecovery(receiptID: summary.receiptID) == summary else { return }
    let application = NodeConfigImportState.Application(confirmation: .recovery(summary, input: input, privateKey: privateKey))
    state = .applying(application)
    errorMessage = nil
    importTask = Task {
      do {
        let result = try await manager.retryIdentityImport(confirmation: summary, privateKey: privateKey)
        guard case let .applying(current) = state, current.id == application.id else { return }
        if case .operational = result, manager.isCurrent(result) {
          state = .completed(current)
        } else {
          state = .failed(current)
          if manager.identityImportRecoveries.contains(where: { $0.receiptID == summary.receiptID }) {
            errorMessage = L10n.Settings.ConfigImport.identityOutcomeUnknown(summary.sourceName)
          }
        }
      } catch {
        guard case let .applying(current) = state, current.id == application.id else { return }
        state = .failed(current)
        if let interrupted = error as? IdentityImportRecoveryRequired {
          retainInterruptedInput(interrupted.recovery, application: current)
        } else if error is CancellationError {
          errorMessage = L10n.Settings.ConfigImport.identityOutcomeUnknown(summary.sourceName)
        } else {
          errorMessage = error.userFacingMessage
        }
      }
    }
  }

  func retainInterruptedInput(_ summary: IdentityImportRecovery, application: NodeConfigImportState.Application) {
    guard inputID == application.confirmation.input.id else { return }
    state = .interrupted(application, summary)
    errorMessage = L10n.Settings.ConfigImport.identityOutcomeUnknown(summary.sourceName)
  }
}
