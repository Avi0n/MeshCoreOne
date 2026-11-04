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

  func factoryResetAndEraseRadio() async throws {
    guard let device = connectedDevice, let settings = services?.settingsService else {
      throw ConnectionError.notConnected
    }
    try await startRadioErase(device, resetWith: settings)
  }

  func retryRadioErase(_ target: PendingRadioEraseDTO) {
    guard radioEraseTasks[target.radioID] == nil else { return }
    beginRadioEraseTask(radioID: target.radioID) { [self] in
      guard try await persistenceStore.isRadioErasePending(target.radioID) else { return }
      try await finishRadioErase(target)
    }
  }
}

extension ConnectionManager {
  func startRadioErase(_ device: DeviceDTO, resetWith settings: SettingsService? = nil) async throws {
    if let running = radioEraseTasks[device.radioID] {
      try await running.value
      return
    }
    let task = beginRadioEraseTask(radioID: device.radioID) { [self] in
      let ownsPairingFlow = connectedDevice?.radioID == device.radioID && !isPairingFlowActive
      if ownsPairingFlow { isPairingFlowActive = true }
      defer {
        if ownsPairingFlow { isPairingFlowActive = false }
      }

      let alreadyPending = try await persistenceStore.isRadioErasePending(device.radioID)
      let poller = connectedDevice?.radioID == device.radioID ? services?.messagePollingService : nil
      try await poller?.suspendForRadioErase()

      let target: PendingRadioEraseDTO
      do {
        guard connectedDevice?.radioID != device.radioID || services?.messagePollingService === poller else {
          throw ConnectionError.notConnected
        }
        target = try await persistenceStore.beginRadioErase(device, isFactoryReset: settings != nil)
      } catch {
        if services?.messagePollingService === poller {
          await poller?.resumeAfterRadioErase()
        }
        throw error
      }
      radioCleanup[target.radioID] = .init(target: target, state: .running)
      if let settings, !alreadyPending {
        // The radio usually reboots before it can acknowledge the command.
        do {
          try await settings.factoryReset()
          try await Task.sleep(for: Self.factoryResetRebootGracePeriod)
        } catch {
          logger.info("Factory reset response interrupted: \(error.localizedDescription)")
        }
      }
      try await finishRadioErase(target)
    }
    try await task.value
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
    operation: @escaping @MainActor () async throws -> Void
  ) -> Task<Void, Error> {
    let task = Task { [self] in
      defer { radioEraseTasks[radioID] = nil }
      try await operation()
    }
    radioEraseTasks[radioID] = task
    return task
  }

  private func finishRadioErase(_ target: PendingRadioEraseDTO) async throws {
    radioCleanup[target.radioID] = .init(target: target, state: .running)
    let shouldTearDown =
      connectedDevice?.radioID == target.radioID ||
      activeConnectionAttemptDeviceID == target.deviceID ||
      activeConnectionAttemptDeviceID == target.pairingID
    let ownsPairingFlow = shouldTearDown && !isPairingFlowActive
    if ownsPairingFlow { isPairingFlowActive = true }
    defer {
      if ownsPairingFlow { isPairingFlowActive = false }
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
      if shouldTearDown,
         connectedDevice == nil || connectedDevice?.radioID == target.radioID,
         activeConnectionAttemptDeviceID == nil ||
         activeConnectionAttemptDeviceID == target.deviceID ||
         activeConnectionAttemptDeviceID == target.pairingID {
        await disconnect(reason: target.isFactoryReset == true ? .factoryReset : .forgetDevice)
      }
      try await waitForConnectionTeardown()
      await clearPersistedConnection(for: target.deviceID)
      try await persistenceStore.completeRadioErase(radioID: target.radioID)
      radioCleanup[target.radioID]?.state = .completed
    } catch {
      radioCleanup[target.radioID]?.state = .failed
      throw error
    }
  }

  private static let factoryResetRebootGracePeriod: Duration = .seconds(1)
}
