import Foundation

extension ConnectionManager {
  enum RemovalPolicy {
    case wholeDevice(deleteData: Bool)
    case bluetoothAssociation(pairingFailed: Bool)
  }

  enum SystemRemovalPolicy {
    case required
    case bestEffort
  }

  public func captureCurrentRemoval() -> ConnectionRemovalContext? {
    guard let device = connectedDevice else { return nil }
    return ConnectionRemovalContext(
      deviceID: device.id, device: device, requestID: desiredRequest?.id, generation: installedLink?.generation
    )
  }

  func captureRemoval(id: UUID) async throws -> ConnectionRemovalContext {
    let requestID = desiredRequest?.id
    let generation = installedLink?.generation
    let device = try await persistenceStore.fetchDevice(id: id)
    return ConnectionRemovalContext(deviceID: id, device: device, requestID: requestID, generation: generation)
  }

  @discardableResult
  func removeDevice(
    _ context: ConnectionRemovalContext,
    policy: RemovalPolicy,
    reason: DisconnectReason,
    systemPolicy: SystemRemovalPolicy
  ) async throws -> ConnectionRemovalResult {
    guard ownsRemovalSource(context) else { return .superseded }
    let operationID = UUID()
    removalOperationID = operationID
    removalDeviceID = context.deviceID
    defer {
      if removalOperationID == operationID { removalOperationID = nil; removalDeviceID = nil }
    }
    do {
      switch systemPolicy {
      case .required:
        try await pairing.removeDevice(context.deviceID)
      case .bestEffort:
        try? await pairing.removeDevice(context.deviceID)
      }
    } catch DevicePairingError.cancelled {
      return .cancelled
    } catch {
      guard ownsRemoval(context, operationID: operationID) else { return .superseded }
      throw error
    }
    return try await startRemoval(
      context, policy: policy, reason: reason,
      sourceIsCurrent: ownsRemoval(context, operationID: operationID)
    ).value
  }

  private func ownsRemoval(_ context: ConnectionRemovalContext, operationID: UUID) -> Bool {
    removalOperationID == operationID && ownsRemovalSource(context)
  }

  func ownsRemovalSource(_ context: ConnectionRemovalContext) -> Bool {
    desiredRequest?.id == context.requestID
      && (context.generation == nil || desiredRequest?.committedGeneration == context.generation)
  }

  func startRemoval(
    _ context: ConnectionRemovalContext,
    policy: RemovalPolicy,
    reason: DisconnectReason,
    sourceIsCurrent: Bool
  ) -> Task<ConnectionRemovalResult, Error> {
    let sourceLink = installedLink
    let affectsConnection = removalMatchesCurrent(context, policy: policy)
    if sourceIsCurrent, case .wholeDevice = policy, let request = desiredRequest,
       !request.removedIdentities.contains(context.identity) {
      request.removedIdentities.append(context.identity)
    }
    let terminal = sourceIsCurrent && affectsConnection ? beginDisconnect(reason: reason) : nil
    // Captured here, including when confirmation arrives after the Link is already detached.
    // deleteData waits for this task; a nil task is already drained. Later retirement is not consulted.
    let admittedWriterDrain: Task<Void, Never>? = if case .wholeDevice(deleteData: true) = policy { retirementTask } else { nil }
    let notifyOwnerID = terminal?.receipt.requestID ?? context.requestID
    let metadata: Task<DeviceDTO?, Error> = enqueueMetadata { store in
      await admittedWriterDrain?.value
      // A newer pairing of the same handle owns its new association and row.
      if !sourceIsCurrent, self.pairing.isDeviceConnectable(context.deviceID) { return nil }
      if !sourceIsCurrent, self.removalMatchesCurrent(context, policy: .wholeDevice(deleteData: false)) {
        let kept = try await store.removeBluetoothMethod(peripheralID: context.deviceID)
        await self.clearBondVerification(for: context.deviceID)
        return kept
      }
      switch policy {
      case let .wholeDevice(deleteData):
        if let current = try await self.resolveRemovalDevice(context.identity, store: store) {
          if deleteData { try await store.deleteDeviceAndData(id: current.id) } else {
            try await store.demoteDeviceToGhost(id: current.id)
          }
          await self.clearPersistedConnection(for: current.id, notifyingRequestID: notifyOwnerID)
        }
        await self.clearPersistedConnection(for: context.deviceID, notifyingRequestID: notifyOwnerID)
      case let .bluetoothAssociation(pairingFailed):
        if self.pairing.hasSystemPairingRegistry, self.pairing.isDeviceConnectable(context.deviceID) {
          await self.clearPersistedConnection(for: context.deviceID, notifyingRequestID: notifyOwnerID)
          return nil
        }
        if let kept = try await store.removeBluetoothMethod(peripheralID: context.deviceID) {
          await self.clearBondVerification(for: context.deviceID)
          return kept
        }
        if let current = try await self.resolveRemovalDevice(context.identity, store: store) {
          if pairingFailed { try await store.deleteDevice(id: current.id) } else {
            try await store.demoteDeviceToGhost(id: current.id)
          }
        }
        await self.clearPersistedConnection(for: context.deviceID, notifyingRequestID: notifyOwnerID)
      }
      return nil
    }
    return Task { @MainActor in
      await terminal?.barrier.value
      let kept: DeviceDTO?
      do {
        kept = try await metadata.value
      } catch {
        if let terminal {
          guard self.isCurrent(terminal.receipt) else { return .superseded }
        } else {
          guard self.ownsRemovalSource(context) else { return .superseded }
        }
        throw error
      }
      if let kept, let sourceLink, self.installedLink === sourceLink, self.owns(sourceLink),
         sourceLink.device.id == kept.id {
        self.setPublishedDevice(kept)
      }
      guard let terminal, self.isCurrent(terminal.receipt) else { return .superseded }
      await self.onConnectionLost?(terminal.receipt)
      return .removed(terminal.receipt)
    }
  }

  func removalMatchesCurrent(_ context: ConnectionRemovalContext, policy: RemovalPolicy) -> Bool {
    switch policy {
    case .wholeDevice:
      if let device = connectedDevice, context.identity.matches(ConnectionDeviceIdentity(device)) { return true }
      if let attempt = desiredRequest?.attempt, context.identity.matches(attempt.identity) { return true }
      return desiredRequest?.target.deviceID == context.deviceID
    case .bluetoothAssociation:
      return desiredRequest?.target == .bluetooth(context.deviceID)
        || (currentTransportType == .bluetooth && connectedDevice?.id == context.deviceID)
    }
  }

  func resolveRemovalDevice(
    _ identity: ConnectionDeviceIdentity, store: PersistenceStore
  ) async throws -> DeviceDTO? {
    if let id = identity.deviceID, let device = try await store.fetchDevice(id: id) { return device }
    if let radioID = identity.radioID, let device = try await store.fetchDevice(radioID: radioID) { return device }
    if let key = identity.publicKey { return try await store.fetchDevice(publicKey: key) }
    return nil
  }
}
