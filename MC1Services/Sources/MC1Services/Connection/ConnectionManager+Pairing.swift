import Foundation
import MeshCore

// MARK: - Pairing

public extension ConnectionManager {
  /// The request `pairNewDevice()` has already admitted. Nil during the picker,
  /// so cancelling discovery does not drop a radio that is already connected.
  var admittedPairingRequestID: UUID? {
    guard isPairingInProgress, let request = desiredRequest, request.origin == .pairing else { return nil }
    return request.id
  }

  /// Pairs through the platform picker, then joins the admitted connection request.
  @discardableResult
  func pairNewDevice() async throws -> ConnectionResult {
    guard !isPairingInProgress else { throw DevicePairingError.alreadyInProgress }
    let operationID = UUID()
    let observedRequestID = desiredRequest?.id
    pairingOperationID = operationID
    isPairingInProgress = true
    defer {
      if pairingOperationID == operationID { isPairingInProgress = false; pairingOperationID = nil }
    }
    await stopBLEScanning()
    try await pairing.activate()
    try Task.checkCancellation()
    guard desiredRequest?.id == observedRequestID else { return .superseded }
    let deviceID = try await pairing.discoverDevice()
    guard desiredRequest?.id == observedRequestID, pairingOperationID == operationID else { return .superseded }
    var handoffRequestID: UUID?
    do {
      _ = await waitForOtherAppReconnection(deviceID)
      try Task.checkCancellation()
      guard desiredRequest?.id == observedRequestID else { return .superseded }
      let result = try await requestConnection(
        targets: [.bluetooth(deviceID)], origin: .pairing, forceFullSync: true, forceReconnect: true
      )
      switch result {
      case .operational: return result
      case let .handoff(requestID):
        handoffRequestID = requestID
        try await waitForPairingAdoptionToSettle(expectedRequestID: requestID)
        guard let snapshot = currentConnection, snapshot.requestID == requestID else { return .superseded }
        return .operational(snapshot)
      case .cancelled:
        if let request = desiredRequest, request.target == .bluetooth(deviceID), installedLink == nil {
          await cleanupPartialPairing(deviceID: deviceID, requestID: request.id)
        }
        return .cancelled
      case .superseded: return .superseded
      }
    } catch let failure as ConnectionRequestFailure {
      guard isCurrent(failure) else { return .superseded }
      let pairingError: PairingError = if case BLEError.deviceConnectedToOtherApp = failure.underlying {
        .deviceConnectedToOtherApp(deviceID: deviceID)
      } else {
        .connectionFailed(deviceID: deviceID, underlying: failure.underlying)
      }
      throw ConnectionRequestFailure(requestID: failure.requestID, underlying: pairingError)
    } catch is CancellationError {
      let ownerID = handoffRequestID ?? observedRequestID
      await cleanupPartialPairing(deviceID: deviceID, requestID: ownerID)
      return .cancelled
    } catch {
      guard let requestID = handoffRequestID, desiredRequest?.id == requestID else { throw error }
      throw ConnectionRequestFailure(
        requestID: requestID, underlying: PairingError.connectionFailed(deviceID: deviceID, underlying: error)
      )
    }
  }

  private func cleanupPartialPairing(deviceID: UUID, requestID: UUID?) async {
    let cleanup = Task { @MainActor in
      guard self.desiredRequest?.id == requestID, self.connectedDevice?.id != deviceID else { return }
      let admitted = self.desiredRequest?.target == .bluetooth(deviceID)
      guard !admitted || self.installedLink == nil else { return }
      let device = try? await self.persistenceStore.fetchDevice(id: deviceID)
      let context = ConnectionRemovalContext(deviceID: deviceID, device: device, requestID: requestID, generation: nil)
      _ = try? await self.removeDevice(
        context, policy: .bluetoothAssociation(pairingFailed: false), reason: .pairingFailed, systemPolicy: .required
      )
    }
    await cleanup.value
  }

  /// Pairing waits for the admitted request's operational stack; physical
  /// `.connected` alone does not authorize completion.
  func waitForPairingAdoptionToSettle(expectedRequestID: UUID) async throws {
    #if DEBUG
      if let strategy = pairingAdoptionSettleStrategyOverride {
        try await strategy()
        return
      }
    #endif

    let timeout: Duration
    #if DEBUG
      timeout = testPairingAdoptionSettleTimeout ?? Self.pairingAdoptionSettleTimeout
    #else
      timeout = Self.pairingAdoptionSettleTimeout
    #endif
    let deadline = ContinuousClock.now + timeout

    while true {
      try Task.checkCancellation()
      guard desiredRequest?.id == expectedRequestID else { throw CancellationError() }
      if connectionState.isOperational {
        guard services != nil else {
          throw BLEError.connectionFailed(Self.pairingAdoptionMissingSessionDetail)
        }
        return
      }
      if connectionState == .disconnected {
        throw BLEError.connectionFailed(Self.pairingAdoptionFailedDetail)
      }
      guard ContinuousClock.now < deadline else {
        throw BLEError.connectionFailed(Self.pairingAdoptionTimedOutDetail)
      }
      try await Task.sleep(for: Self.pairingAdoptionSettlePollInterval)
    }
  }

  /// ASK accessories with no `Device` row. Activates the pairing session first.
  /// Omits saved radios, the live connection, and the in-flight attempt; a fetch throw skips that id.
  func systemAccessoriesMissingDeviceRecord() async throws -> [(id: UUID, name: String)] {
    guard pairing.hasSystemPairingRegistry else { return [] }
    try await pairing.activate()
    var protectedIDs = Set<UUID>()
    if let connectedID = connectedDevice?.id { protectedIDs.insert(connectedID) }
    if let attemptID = activeConnectionAttemptDeviceID { protectedIDs.insert(attemptID) }
    var result: [(id: UUID, name: String)] = []
    for info in pairing.registeredDeviceInfos() where !protectedIDs.contains(info.id) {
      let existing: DeviceDTO?
      do {
        existing = try await persistenceStore.fetchDevice(id: info.id)
      } catch {
        logger.warning(
          "Skipping association \(info.id.uuidString.prefix(8)); device lookup failed: \(error.localizedDescription)"
        )
        continue
      }
      if existing == nil { result.append(info) }
    }
    return result
  }

  /// Removes ASK accessories that still have no `Device` row, limited to `ids`.
  /// A declined iOS confirmation throws `DevicePairingError.cancelled` and aborts the loop.
  func removeSystemAccessoriesMissingDeviceRecord(_ ids: [UUID]) async throws {
    guard pairing.hasSystemPairingRegistry else { return }
    let allowed = try await Set(systemAccessoriesMissingDeviceRecord().map(\.id))
    for id in ids where allowed.contains(id) {
      try await pairing.removeDevice(id)
    }
  }

  /// Removes the failed Bluetooth association while retaining its radio partition.
  @discardableResult
  func removeFailedPairing(deviceID: UUID) async throws -> ConnectionRemovalResult {
    let context = try await captureRemoval(id: deviceID)
    return try await removeDevice(
      context, policy: .bluetoothAssociation(pairingFailed: false),
      reason: .forgetDevice, systemPolicy: .required
    )
  }

  // MARK: - Other-App Detection

  /// Polls for other-app reconnection after ASK pairing disrupts existing BLE connections.
  /// ASK pairing severs the other app's BLE link; it auto-reconnects seconds later via
  /// `CBConnectPeripheralOptionEnableAutoReconnect`. This method gives it time to reappear.
  /// - Parameter deviceID: The UUID of the newly paired device
  /// - Returns: `true` if the device was detected as connected to another app
  internal func waitForOtherAppReconnection(_ deviceID: UUID) async -> Bool {
    #if DEBUG
      if let strategy = otherAppWaitStrategyOverride {
        return await strategy(deviceID)
      }
    #endif
    return await defaultWaitForOtherAppReconnection(deviceID)
  }

  private func defaultWaitForOtherAppReconnection(_ deviceID: UUID) async -> Bool {
    let maxChecks = 6
    let interval: Duration = .milliseconds(400)

    for check in 1...maxChecks {
      // Short-circuit on cancellation so the caller's `connect(to:)` checkpoint
      // surfaces the CancellationError without grinding through every iteration.
      if Task.isCancelled {
        logger.info("[OtherAppCheck] Cancelled at check \(check)/\(maxChecks)")
        return false
      }

      let connected = await stateMachine.isDeviceConnectedToSystem(deviceID)
      if connected {
        logger.info("[OtherAppCheck] Detected other-app connection on check \(check)/\(maxChecks)")
        return true
      }

      if check < maxChecks {
        try? await Task.sleep(for: interval)
      }
    }

    logger.info("[OtherAppCheck] No other-app connection detected after \(maxChecks) checks")
    return false
  }

  // MARK: - Forget Device

  @discardableResult
  func forgetDevice(_ context: ConnectionRemovalContext, deleteData: Bool) async throws -> ConnectionRemovalResult {
    if !deleteData {
      return try await removeDevice(
        context, policy: .wholeDevice(deleteData: false), reason: .forgetDevice, systemPolicy: .required
      )
    }
    guard ownsRemovalSource(context) else { return .superseded }
    let device = if let connectedDevice, context.identity.matches(ConnectionDeviceIdentity(connectedDevice)) {
      connectedDevice
    } else {
      try await resolveRemovalDevice(context.identity, store: persistenceStore)
    }
    guard let device, ownsRemovalSource(context) else { return .superseded }
    do {
      return try await startRadioErase(device, context: context)
    } catch DevicePairingError.cancelled {
      return .cancelled
    }
  }

  // MARK: - Node Management

  /// Returns the number of non-favorite contacts for the current device.
  func unfavoritedNodeCount() async throws -> Int {
    guard let radioID = connectedDevice?.radioID else {
      throw ConnectionError.notConnected
    }

    let dataStore = persistenceStore
    let allContacts = try await dataStore.fetchContacts(radioID: radioID)
    return allContacts.count(where: { !$0.isFavorite })
  }

  /// Removes all non-favorite contacts from the device and app, along with their messages.
  /// - Returns: Count of removed vs total non-favorite contacts
  /// - Throws: `ConnectionError.notConnected` if no device is connected
  func removeUnfavoritedNodes() async throws -> RemoveUnfavoritedResult {
    try await removeContacts(matching: { !$0.isFavorite })
  }

  /// Removes non-favorite contacts whose recency timestamp is older than the given threshold.
  /// - Parameter days: Number of days. Contacts not heard from in this many days are removed.
  /// - Returns: Count of removed vs total stale contacts
  /// - Throws: `ConnectionError.notConnected` if no device is connected
  func removeStaleNodes(olderThanDays days: Int) async throws -> RemoveUnfavoritedResult {
    let cutoff = UInt32(Date().addingTimeInterval(-Double(days) * 86400).timeIntervalSince1970)
    return try await removeContacts(matching: { $0.matchesStaleNodePrune(cutoff: cutoff) }) { contact in
      let ageDays = (Int(Date().timeIntervalSince1970) - Int(contact.recencyTimestamp)) / 86400
      let keyPrefix = contact.publicKeyHex.prefix(8)
      self.logger.info("Auto-removed stale node '\(contact.name)' [\(keyPrefix)] (last heard \(ageDays)d ago)")
    }
  }

  /// Shared implementation for removing contacts matching a predicate.
  /// - Parameters:
  ///   - predicate: Filter applied to all contacts to determine which to remove.
  ///   - onRemove: Optional callback invoked after each successful removal (for per-contact logging).
  /// - Returns: Count of removed vs total matching contacts
  private func removeContacts(
    matching predicate: (ContactDTO) -> Bool,
    onRemove: ((_ contact: ContactDTO) -> Void)? = nil
  ) async throws -> RemoveUnfavoritedResult {
    guard let radioID = connectedDevice?.radioID, let services else {
      throw ConnectionError.notConnected
    }

    let selfPublicKey = connectedDevice?.publicKey
    let dataStore = persistenceStore
    let allContacts = try await dataStore.fetchContacts(radioID: radioID)
    try Task.checkCancellation()
    guard self.services === services, connectedDevice?.radioID == radioID else {
      throw ConnectionError.notConnected
    }
    // Never bulk-remove the ZephCore V-contact: CMD_REMOVE turns firmware v.contact off.
    let targets = allContacts.filter { contact in
      guard predicate(contact) else { return false }
      if let selfPublicKey,
         VContactIdentity.isVContact(publicKey: contact.publicKey, selfPublicKey: selfPublicKey) {
        return false
      }
      return true
    }

    if targets.isEmpty {
      return RemoveUnfavoritedResult(removed: 0, total: 0)
    }

    var removedCount = 0
    var totalCount = targets.count
    var deletedIDs: Set<UUID> = []

    do {
      for target in targets {
        try Task.checkCancellation()
        guard self.services === services, connectedDevice?.radioID == radioID else {
          break
        }
        guard let contact = try await dataStore.fetchContact(id: target.id), predicate(contact) else {
          totalCount -= 1
          continue
        }
        try Task.checkCancellation()
        guard self.services === services, connectedDevice?.radioID == radioID else {
          break
        }

        do {
          if let id = try await services.contactService.removeContact(
            radioID: radioID, publicKey: contact.publicKey, deferDeletionUpdates: true
          ) {
            deletedIDs.insert(id)
          }
          removedCount += 1
          onRemove?(contact)
        } catch ContactServiceError.contactNotFound {
          do {
            if let id = try await services.contactService.removeLocalContact(
              contactID: contact.id, publicKey: contact.publicKey, deferDeletionUpdates: true
            ) {
              deletedIDs.insert(id)
            }
            removedCount += 1
            logger.info("Contact not found on device, cleaned up locally: \(contact.name)")
          } catch is CancellationError {
            try Task.checkCancellation()
            break
          } catch {
            logger.warning("Failed to clean up local data for \(contact.name): \(error.localizedDescription)")
          }
        } catch is CancellationError {
          try Task.checkCancellation()
          break
        } catch {
          logger.warning("Failed to remove contact \(contact.name): \(error.localizedDescription)")
          break
        }
        await services.contactService.flushDeletionUpdatesIfNeeded(contactIDs: &deletedIDs, force: false)
      }
    } catch {
      await services.contactService.flushDeletionUpdatesIfNeeded(contactIDs: &deletedIDs, force: true)
      throw error
    }
    await services.contactService.flushDeletionUpdatesIfNeeded(contactIDs: &deletedIDs, force: true)
    try Task.checkCancellation()
    return RemoveUnfavoritedResult(removed: removedCount, total: totalCount)
  }

  // MARK: - Stale Pairings

  /// Clears all stale pairings from the system registry (AccessorySetupKit on iOS).
  /// Use when a device has been factory-reset but iOS still has the old pairing. No-op on macOS.
  func clearStalePairings() async {
    logger.info("Clearing stale pairings")
    await pairing.clearStaleRegistrations()
    logger.info("Stale pairings cleared")
  }

  // MARK: - Accessory Management

  /// Checks if a device is registered with the system pairing registry.
  /// - Parameter deviceID: The Bluetooth UUID of the device
  /// - Returns: `true` if the device is available for connection (always `true` on macOS).
  func hasAccessory(for deviceID: UUID) -> Bool {
    pairing.isDeviceConnectable(deviceID)
  }

  /// Fetches all previously paired devices from storage.
  /// Available even when disconnected, for device selection UI.
  func fetchSavedDevices() async throws -> [DeviceDTO] {
    logger.info("fetchSavedDevices called, connectionState: \(String(describing: connectionState))")
    let dataStore = persistenceStore
    let devices = try await dataStore.fetchDevices()
    logger.info("fetchSavedDevices returning \(devices.count) devices")
    return devices
  }

  /// Removes the confirmed subject while preserving its radio partition as a Ghost.
  func deleteDevice(id: UUID) async throws {
    let context = try await captureRemoval(id: id)
    let result = try await removeDevice(
      context, policy: .wholeDevice(deleteData: false), reason: .forgetDevice, systemPolicy: .required
    )
    if case .cancelled = result { throw DevicePairingError.cancelled }
  }

  /// Returns devices registered with the system pairing registry (AccessorySetupKit on iOS).
  /// Use as fallback when SwiftData has no device records. Empty on macOS.
  var pairedAccessoryInfos: [(id: UUID, name: String)] {
    pairing.registeredDeviceInfos()
  }

  /// Renames the currently connected device via the system rename UI (AccessorySetupKit on iOS).
  /// No-op on macOS, where there is no system rename surface.
  /// - Throws: `ConnectionError.notConnected` if no device is connected
  func renameCurrentDevice() async throws {
    guard let deviceID = connectedDevice?.id else {
      throw ConnectionError.notConnected
    }

    guard pairing.isDeviceConnectable(deviceID) else {
      throw ConnectionError.deviceNotFound
    }

    try await pairing.renameDevice(deviceID)
  }
}

// MARK: - DevicePairingDelegate

extension ConnectionManager: DevicePairingDelegate {
  public func devicePairing(_ service: any DevicePairingService, didRemoveDeviceWithID bluetoothID: UUID) {
    guard !isRemovingPairing(bluetoothID) else { return }
    handleRegistryRemoval(bluetoothID, pairingFailed: false)
  }

  public func devicePairing(_ service: any DevicePairingService, didFailPairingForDeviceWithID bluetoothID: UUID) {
    guard !isRemovingPairing(bluetoothID) else { return }
    handleRegistryRemoval(bluetoothID, pairingFailed: true)
  }

  private func isRemovingPairing(_ bluetoothID: UUID) -> Bool {
    removalDeviceID == bluetoothID || radioCleanup.values.contains {
      $0.target.pairingID == bluetoothID && radioEraseTasks[$0.target.radioID] != nil
    }
  }

  private func handleRegistryRemoval(_ id: UUID, pairingFailed: Bool) {
    let identity: ConnectionDeviceIdentity = if let device = connectedDevice, device.id == id {
      ConnectionDeviceIdentity(device)
    } else if let attempt = desiredRequest?.attempt, attempt.identity.deviceID == id {
      attempt.identity
    } else {
      ConnectionDeviceIdentity(deviceID: id)
    }
    let context = ConnectionRemovalContext(
      deviceID: id, identity: identity, requestID: desiredRequest?.id, generation: installedLink?.generation
    )
    let work = startRemoval(
      context, policy: .bluetoothAssociation(pairingFailed: pairingFailed),
      reason: pairingFailed ? .pairingFailed : .deviceRemovedFromSettings, sourceIsCurrent: true
    )
    Task { @MainActor in
      if case let .failure(error) = await work.result {
        self.logger.warning("Association removal failed: \(error.localizedDescription)")
      }
    }
  }
}
