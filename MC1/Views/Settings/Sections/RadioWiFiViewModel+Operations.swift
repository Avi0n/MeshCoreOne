import Foundation
import MC1Services

extension RadioWiFiViewModel {
  @discardableResult
  func confirmFromAlert(_ action: AlertAction) -> Task<Void, Never> {
    let captured: Confirmation?
    switch (action, confirmation) {
    case (.wifiWarning, .save), (.wifiWarning, .disable), (.clear, .clear), (.reboot, .reboot):
      captured = confirmation
      confirmation = nil
    default:
      captured = nil
    }
    return Task {
      switch captured {
      case let .save(submission): await performSave(submission)
      case let .disable(owner, revision): await writeEnabled(false, owner: owner, revision: revision)
      case let .clear(owner, revision): await performClear(owner: owner, revision: revision)
      case let .reboot(owner, revision): await performReboot(owner: owner, revision: revision)
      case nil: break
      }
    }
  }

  func setWiFiEnabled(_ enabled: Bool) async {
    guard canRunCommands, let owner = context,
          let revision = binding(for: owner)?.state.stableRevision() else { return }
    if !enabled, currentTransportType == .wifi {
      confirmation = .disable(owner, revision)
    } else {
      await writeEnabled(enabled, owner: owner, revision: revision)
    }
  }

  func cancelWiFiWarning() {
    switch confirmation { case .save, .disable: confirmation = nil; default: break }
  }

  func requestClear() {
    guard canClear, let owner = context,
          let revision = binding(for: owner)?.state.stableRevision() else { return }
    confirmation = .clear(owner, revision)
  }

  private func performClear(owner: Context, revision: UInt64) async {
    guard canClear, matches(owner), let settings = settingsService else { return }
    let id = UUID()
    operation = .write(id, owner)
    do {
      let result = try await settings.clearRadioWiFi(
        expectedContextID: owner.wifiContextID, expectedRevision: revision
      )
      if matches(owner) {
        await closeEditor()
        clearAssociation()
        observation.networkNameIsCurrent = false
        if let result, settings.radioWiFiState.stableRevision() == result.revision {
          apply(result)
          errorMessage = result.error?.userFacingMessage
        } else {
          oweRead(owner)
        }
      }
    } catch is CancellationError {
      if matches(owner) { oweRead(owner) }
    } catch {
      if matches(owner) {
        observation.networkNameIsCurrent = false
        clearAssociation()
        oweRead(owner)
        errorMessage = error.userFacingMessage
      }
    }
    finish(id)
    await drainOwedRead()
  }

  func writeEnabled(_ enabled: Bool, owner: Context, revision: UInt64) async {
    guard canRunCommands, matches(owner), let settings = settingsService else { return }
    let id = UUID()
    operation = .write(id, owner)
    do {
      let receipt = try await settings.changeRadioWiFiEnabled(
        enabled, expectedContextID: owner.wifiContextID, expectedRevision: revision
      )
      if matches(owner) {
        if receipt.contextID == owner.wifiContextID, let settled = receipt.revision,
           binding(for: owner)?.state.stableRevision() == settled {
          switch receipt.outcome {
          case .accepted:
            adoptMutationRevision(from: revision, to: settled)
            observation.enabled = enabled
            observation.enabledRevision = settled
          case .rejected:
            adoptMutationRevision(from: revision, to: settled)
          case .uncertain:
            observation.enabledRevision = nil
            oweRead(owner)
          }
        } else {
          oweRead(owner)
        }
        if let error = receipt.error, !(error is CancellationError) { errorMessage = error.userFacingMessage }
      }
    } catch is CancellationError {
      if matches(owner) { oweRead(owner) }
    } catch {
      if matches(owner) {
        errorMessage = error.userFacingMessage
        observation.enabledRevision = nil
        observation.networkNameIsCurrent = false
        clearAssociation()
        oweRead(owner)
      }
    }
    finish(id)
    await drainOwedRead()
  }

  func requestReboot() {
    guard canRunCommands, rebootPending, let context,
          let revision = binding(for: context)?.state.stableRevision() else { return }
    confirmation = .reboot(context, revision)
  }

  func cancelReboot() {
    if case .reboot = confirmation { confirmation = nil }
  }

  private func performReboot(owner: Context, revision: UInt64) async {
    guard canRunCommands, matches(owner), let settings = settingsService else { return }
    let id = UUID()
    operation = .write(id, owner)
    do {
      try await settings.reboot(expectedContextID: owner.wifiContextID, expectedRevision: revision)
    } catch is CancellationError {
      if matches(owner) { oweRead(owner) }
    } catch {
      if matches(owner) {
        switch error {
        case BLEError.notConnected, BLEError.operationTimeout,
             MeshTransportError.notConnected,
             WiFiTransportError.notConnected, WiFiTransportError.sendTimeout:
          break
        default: errorMessage = error.userFacingMessage
        }
      }
    }
    finish(id)
    await drainOwedRead()
  }
}
