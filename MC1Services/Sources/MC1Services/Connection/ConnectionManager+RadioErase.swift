import Foundation

public extension ConnectionManager {
  struct RadioCleanup: Identifiable, Sendable {
    public enum State: Sendable {
      case running
      case failed
      case completed
    }

    public let target: PendingRadioEraseDTO
    public var state: State
    public var id: UUID {
      target.radioID
    }
  }

  func factoryResetAndEraseRadio(_ context: ConnectionRemovalContext) async throws -> ConnectionRemovalResult {
    guard ownsRemovalSource(context), let device = connectedDevice, let settings = services?.settingsService,
          context.identity.matches(ConnectionDeviceIdentity(device)) else { return .superseded }
    return try await startRadioErase(device, context: context, resetWith: settings)
  }

  func retryRadioErase(_ target: PendingRadioEraseDTO) {
    guard radioEraseTasks[target.radioID] == nil else { return }
    beginRadioEraseTask(radioID: target.radioID) { [self] in
      guard try await persistenceStore.isRadioErasePending(target.radioID) else { return .superseded }
      let context = ConnectionRemovalContext(
        deviceID: target.deviceID, identity: .init(deviceID: target.deviceID, radioID: target.radioID),
        requestID: desiredRequest?.id, generation: installedLink?.generation
      )
      return try await finishRadioErase(target, context: context)
    }
  }
}

extension ConnectionManager {
  @discardableResult
  func startRadioErase(
    _ device: DeviceDTO, context: ConnectionRemovalContext? = nil, resetWith settings: SettingsService? = nil
  ) async throws -> ConnectionRemovalResult {
    let context = context ?? ConnectionRemovalContext(
      deviceID: device.id, device: device, requestID: desiredRequest?.id, generation: installedLink?.generation
    )
    guard ownsRemovalSource(context) else { return .superseded }
    if let running = radioEraseTasks[device.radioID] {
      return try await running.value
    }
    let task = beginRadioEraseTask(radioID: device.radioID) { [self] in
      guard ownsRemovalSource(context) else { return .superseded }
      let alreadyPending = try await persistenceStore.isRadioErasePending(device.radioID)
      guard ownsRemovalSource(context) else { return .superseded }
      let poller = connectedDevice?.radioID == device.radioID ? services?.messagePollingService : nil
      do {
        try await poller?.suspendForRadioErase()
      } catch {
        guard ownsRemovalSource(context) else { return .superseded }
        throw error
      }

      let record: Task<PendingRadioEraseDTO?, Error> = enqueueMetadata { store in
        guard self.ownsRemovalSource(context),
              self.connectedDevice?.radioID != device.radioID || self.services?.messagePollingService === poller else { return nil }
        return try await store.beginRadioErase(device, isFactoryReset: settings != nil)
      }
      let target: PendingRadioEraseDTO?
      do {
        target = try await record.value
      } catch {
        if services?.messagePollingService === poller { await poller?.resumeAfterRadioErase() }
        guard ownsRemovalSource(context) else { return .superseded }
        throw error
      }
      guard let target else {
        if services?.messagePollingService === poller { await poller?.resumeAfterRadioErase() }
        return .superseded
      }
      radioCleanup[target.radioID] = .init(target: target, state: .running)
      if let settings, !alreadyPending {
        let canSubmitReset = ownsRemovalSource(context) && services?.settingsService === settings
        if canSubmitReset {
          // The radio usually reboots before it can acknowledge the command.
          do {
            try await settings.factoryReset()
            try await Task.sleep(for: Self.factoryResetRebootGracePeriod)
          } catch {
            logger.info("Factory reset response interrupted: \(error.localizedDescription)")
          }
        }
        // An admitted recovery owns the next Link record while the old reset settles.
        let recoveryOwnsRequest = installedLink == nil && reconnectionCoordinator.currentCycle.map {
          $0.requestID == context.requestID
        } == true
        if !canSubmitReset || !ownsRemovalSource(context) || recoveryOwnsRequest {
          do {
            try await persistenceStore.cancelRadioErase(radioID: target.radioID)
            radioCleanup[target.radioID] = nil
            if services?.messagePollingService === poller { await poller?.resumeAfterRadioErase() }
          } catch {
            radioCleanup[target.radioID]?.state = .failed
            logger.warning("Failed to roll back superseded reset cleanup: \(error.localizedDescription)")
          }
          return .superseded
        }
      }
      return try await finishRadioErase(target, context: context)
    }
    return try await task.value
  }

  func resumePendingRadioErases(pairingReady: Bool) async {
    do {
      for target in try await persistenceStore.fetchPendingRadioErases() {
        if pairingReady {
          retryRadioErase(target)
          _ = await radioEraseTasks[target.radioID]?.result
        } else {
          radioCleanup[target.radioID] = .init(target: target, state: .failed)
        }
      }
    } catch {
      logger.error("Failed to read pending radio cleanup: \(error.localizedDescription)")
    }
  }

  @discardableResult
  private func beginRadioEraseTask(
    radioID: UUID,
    operation: @escaping @MainActor () async throws -> ConnectionRemovalResult
  ) -> Task<ConnectionRemovalResult, Error> {
    let task = Task { [self] in
      defer { radioEraseTasks[radioID] = nil }
      return try await operation()
    }
    radioEraseTasks[radioID] = task
    return task
  }

  private func finishRadioErase(
    _ target: PendingRadioEraseDTO, context: ConnectionRemovalContext
  ) async throws -> ConnectionRemovalResult {
    radioCleanup[target.radioID] = .init(target: target, state: .running)
    let operationID = UUID()
    if ownsRemovalSource(context), removalMatchesCurrent(context, policy: .wholeDevice(deleteData: true)) {
      removalOperationID = operationID
      removalDeviceID = target.pairingID
    }
    defer {
      if removalOperationID == operationID { removalOperationID = nil; removalDeviceID = nil }
    }

    do {
      #if canImport(UIKit) && !targetEnvironment(simulator)
        if pairing.hasSystemPairingRegistry, !pairing.isSessionActive {
          try await pairing.activate()
          guard pairing.isSessionActive else { throw AccessorySetupKitError.sessionNotActive }
        }
      #endif
      do {
        try await pairing.removeDevice(target.pairingID)
      } catch DevicePairingError.cancelled where target.isFactoryReset == false {
        try await persistenceStore.cancelRadioErase(radioID: target.radioID)
        radioCleanup[target.radioID] = nil
        if connectedDevice?.radioID == target.radioID {
          await services?.messagePollingService.resumeAfterRadioErase()
        }
        throw DevicePairingError.cancelled
      }
    } catch {
      if radioCleanup[target.radioID] != nil { radioCleanup[target.radioID]?.state = .failed }
      guard ownsRemovalSource(context) else { return .superseded }
      throw error
    }

    let sourceIsCurrent = ownsRemovalSource(context)
    if sourceIsCurrent, let request = desiredRequest, !request.removedIdentities.contains(context.identity) {
      request.removedIdentities.append(context.identity)
    }
    let terminal = if sourceIsCurrent,
                      removalMatchesCurrent(context, policy: .wholeDevice(deleteData: true)) ||
                      desiredRequest?.target.deviceID == target.pairingID {
      beginDisconnect(reason: target.isFactoryReset == true ? .factoryReset : .forgetDevice)
    } else {
      nil as (barrier: ConnectionRetirement, receipt: ConnectionLossReceipt)?
    }
    let writerDrain = retirementTask
    let notifyingRequestID = terminal?.receipt.requestID ?? context.requestID
    let cleanup = enqueueMetadata { store -> Bool in
      await writerDrain?.value
      let sourceStillCurrent = terminal.map { self.isCurrent($0.receipt) } ?? self.ownsRemovalSource(context)
      let sourceWasSuperseded = !sourceIsCurrent || !sourceStillCurrent
      if sourceWasSuperseded, self.pairing.hasSystemPairingRegistry, self.pairing.isDeviceConnectable(target.pairingID) {
        try await store.cancelRadioErase(radioID: target.radioID)
        return false
      }
      await self.clearPersistedConnection(for: target.deviceID, notifyingRequestID: notifyingRequestID)
      try await store.completeRadioErase(radioID: target.radioID)
      return true
    }
    do {
      guard try await cleanup.value else {
        radioCleanup[target.radioID] = nil
        return .superseded
      }
      radioCleanup[target.radioID]?.state = .completed
      guard let terminal, isCurrent(terminal.receipt) else { return .superseded }
      await onConnectionLost?(terminal.receipt)
      return .removed(terminal.receipt)
    } catch {
      radioCleanup[target.radioID]?.state = .failed
      if let terminal {
        guard isCurrent(terminal.receipt) else { return .superseded }
      } else {
        guard ownsRemovalSource(context) else { return .superseded }
      }
      throw error
    }
  }

  private static let factoryResetRebootGracePeriod: Duration = .seconds(1)
}
