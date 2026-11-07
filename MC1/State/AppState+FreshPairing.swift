import Foundation
import MC1Services

extension AppState {
  /// ASK Remove Accessory can temporarily prevent the scene from presenting `showPicker`.
  /// Retry once after a short delay in case `.active` already fired.
  private static let freshPairingForegroundRetryDelay: Duration = .milliseconds(400)

  func startDeviceScan() {
    guard !isRecoveringPairing else { return }
    let (operation, previous) = beginFreshPairingOperation(source: .explicitSwitch)
    connectionUI.hideDisconnectedPill()
    connectionUI.failedPairingDeviceID = nil
    freshPairingTask = Task {
      defer { finishFreshPairingWork(operation.actionID, attempt: .initial) }
      await withPairingFlowErrorHandling(actionID: operation.actionID) {
        let pending = try await connectionManager.systemAccessoriesMissingDeviceRecord()
        guard ownsFreshPairingOperation(operation.actionID),
              operation.requestID == connectionManager.connectionRequestID else { return }
        if !pending.isEmpty {
          freshPairingOperation?.phase = .awaitingConfirmation
          if connectionUI.pendingSystemPairingSetup == nil {
            connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
              accessories: pending.map { SystemPairedAccessory(id: $0.id, name: $0.name) }
            )
          }
          return
        }
        await previous?.value
        try await completeFreshPairing(actionID: operation.actionID)
      }
    }
  }

  func cancelSystemPairingSetup() {
    connectionUI.pendingSystemPairingSetup = nil
    connectionUI.queuedSystemPairingSetup = nil
    cancelFreshPairingOperation()
  }

  func handleSystemPairingSetupSheetDismissed() {
    guard freshPairingOperation?.isConfirmingSystemPairingSetup != true,
          connectionUI.pendingSystemPairingSetup == nil else { return }
    cancelSystemPairingSetup()
  }

  /// Forget the pending ASK accessories, then open the picker. Clears pending first so
  /// iOS Remove Accessory is the only dialog; siblings do not re-prompt.
  func confirmSystemPairingSetup() {
    guard let prompt = connectionUI.pendingSystemPairingSetup else { return }
    let (operation, previous) = beginFreshPairingOperation(source: .systemPairingSetup)
    connectionUI.pendingSystemPairingSetup = nil
    freshPairingTask = Task {
      defer { finishFreshPairingWork(operation.actionID, attempt: .initial) }
      await withPairingFlowErrorHandling(actionID: operation.actionID) {
        try await connectionManager.removeSystemAccessoriesMissingDeviceRecord(prompt.accessories.map(\.id))
        await previous?.value
        try await completeFreshPairing(actionID: operation.actionID)
      }
    }
  }

  private func beginFreshPairingOperation(
    source: FreshPairingOperation.Source
  ) -> (FreshPairingOperation, Task<Void, Never>?) {
    let previous = freshPairingTask
    previous?.cancel()
    freshPairingForegroundRetryTask?.cancel()
    freshPairingForegroundRetryTask = nil
    let operation = FreshPairingOperation(
      actionID: connectionUI.beginConnectionAction(), source: source, requestID: connectionManager.connectionRequestID
    )
    freshPairingOperation = operation
    connectionManager.isPairingFlowActive = true
    return (operation, previous)
  }

  private func ownsFreshPairingOperation(_ actionID: UUID) -> Bool {
    freshPairingOperation?.actionID == actionID && connectionUI.isCurrentConnectionAction(actionID)
  }

  private func finishFreshPairingWork(_ actionID: UUID, attempt: FreshPairingOperation.Attempt) {
    guard freshPairingOperation?.actionID == actionID else { return }
    // The retry claims the operation before joining the initial worker.
    if attempt == .initial, freshPairingOperation?.isForegroundRetry == true { return }
    if ownsFreshPairingOperation(actionID),
       shouldCompleteFreshPairingOnForeground || connectionUI.pendingSystemPairingSetup != nil {
      if !shouldCompleteFreshPairingOnForeground {
        freshPairingOperation?.phase = .awaitingConfirmation
      }
      connectionUI.isBusy = false
      return
    }
    finishFreshPairingOperation(actionID)
  }

  private func finishFreshPairingOperation(_ actionID: UUID) {
    guard freshPairingOperation?.actionID == actionID else { return }
    freshPairingOperation = nil
    freshPairingForegroundRetryTask?.cancel()
    freshPairingForegroundRetryTask = nil
    connectionManager.isPairingFlowActive = false
    connectionUI.finishConnectionAction(actionID)
  }

  func cancelFreshPairingOperation() {
    freshPairingTask?.cancel()
    freshPairingForegroundRetryTask?.cancel()
    if let operation = freshPairingOperation {
      finishFreshPairingOperation(operation.actionID)
    } else {
      connectionManager.isPairingFlowActive = false
    }
  }

  private func completeFreshPairing(actionID: UUID) async throws {
    guard ownsFreshPairingOperation(actionID), !Task.isCancelled,
          freshPairingOperation?.requestID == connectionManager.connectionRequestID else { return }
    let result = try await connectionManager.pairNewDevice()
    guard ownsFreshPairingOperation(actionID), !Task.isCancelled,
          case let .operational(snapshot) = result, connectionManager.isCurrent(snapshot) else { return }
    freshPairingOperation?.requestID = snapshot.requestID
    await wireServices(for: snapshot)
    guard ownsFreshPairingOperation(actionID), connectionManager.isCurrent(snapshot) else { return }
    if !onboarding.hasCompletedOnboarding {
      onboarding.onboardingPath.append(.region)
    }
  }

  private func withPairingFlowErrorHandling(actionID: UUID, _ work: () async throws -> Void) async {
    do {
      try await work()
    } catch {
      guard ownsFreshPairingOperation(actionID), let failure = currentConnectionFailure(error) else { return }
      if !(error is ConnectionRequestFailure), freshPairingOperation?.requestID != connectionManager.connectionRequestID {
        return
      }
      switch failure {
      case DevicePairingError.cancelled:
        break
      case DevicePairingError.alreadyInProgress:
        connectionUI.presentConnectionFailure(message: failure.userFacingMessage)
      case DevicePairingError.pickerUnavailable:
        if freshPairingOperation?.isForegroundRetry == true {
          connectionUI.presentConnectionFailure(message: L10n.Localizable.Error.AccessorySetup.pickerRestricted)
        } else {
          scheduleFreshPairingOnForeground(actionID: actionID)
        }
      case let pairingError as PairingError:
        connectionUI.presentFreshPairingFailure(pairingError)
      default:
        connectionUI.presentConnectionFailure(message: failure.userFacingMessage)
      }
    }
  }

  private func scheduleFreshPairingOnForeground(actionID: UUID) {
    guard ownsFreshPairingOperation(actionID),
          freshPairingOperation?.requestID == connectionManager.connectionRequestID else { return }
    freshPairingOperation?.phase = .awaitingForegroundRetry
    freshPairingForegroundRetryTask?.cancel()
    freshPairingForegroundRetryTask = Task {
      try? await Task.sleep(for: Self.freshPairingForegroundRetryDelay)
      guard !Task.isCancelled, freshPairingOperation?.actionID == actionID else { return }
      resumeFreshPairingIfNeeded()
    }
  }

  func resumeFreshPairingIfNeeded() {
    guard shouldCompleteFreshPairingOnForeground, let operation = freshPairingOperation else { return }
    guard ownsFreshPairingOperation(operation.actionID),
          operation.requestID == connectionManager.connectionRequestID else {
      finishFreshPairingOperation(operation.actionID)
      return
    }
    freshPairingForegroundRetryTask?.cancel()
    freshPairingForegroundRetryTask = nil
    let previous = freshPairingTask
    connectionUI.isBusy = true
    freshPairingOperation?.phase = .running(.foregroundRetry)
    freshPairingTask = Task {
      await previous?.value
      defer { finishFreshPairingWork(operation.actionID, attempt: .foregroundRetry) }
      await withPairingFlowErrorHandling(actionID: operation.actionID) {
        try await completeFreshPairing(actionID: operation.actionID)
      }
    }
  }

  /// Remove the failed Bluetooth association, then pair while the removal receipt is current.
  @discardableResult
  func removeFailedPairingAndRetry() -> Task<Void, Never>? {
    guard !isRecoveringPairing, let deviceID = connectionUI.failedPairingDeviceID else { return nil }
    let episodeID = connectionUI.pairingFailureEpisodeID
    let (operation, previous) = beginFreshPairingOperation(source: .bondLossRecovery)
    connectionUI.hideDisconnectedPill()
    let task = Task {
      defer { finishFreshPairingWork(operation.actionID, attempt: .initial) }
      await withPairingFlowErrorHandling(actionID: operation.actionID) {
        let result: ConnectionRemovalResult
        do {
          result = try await connectionManager.removeFailedPairing(deviceID: deviceID)
        } catch {
          guard ownsFreshPairingOperation(operation.actionID),
                connectionUI.pairingFailureEpisodeID == episodeID,
                let failure = currentConnectionFailure(error) else { return }
          connectionUI.connectionFailedMessage = failure.userFacingMessage
          connectionUI.showingConnectionFailedAlert = true
          return
        }
        guard ownsFreshPairingOperation(operation.actionID),
              connectionUI.pairingFailureEpisodeID == episodeID,
              connectionUI.failedPairingDeviceID == deviceID,
              case let .removed(receipt) = result, connectionManager.isCurrent(receipt) else { return }
        freshPairingOperation?.requestID = receipt.requestID
        connectionUI.failedPairingDeviceID = nil
        await previous?.value
        try await completeFreshPairing(actionID: operation.actionID)
      }
    }
    freshPairingTask = task
    return task
  }
}
