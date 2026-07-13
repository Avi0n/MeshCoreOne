import Foundation
import MeshCore

// MARK: - Pairing

public extension ConnectionManager {
  /// Discovers a new device through the platform pairing service (AccessorySetupKit on
  /// iOS, an in-app CoreBluetooth scan picker on macOS), then connects through the shared
  /// `connect(to:)` ceremony. The connect path coordinates with in-flight auto-reconnects,
  /// switch-device handling, and the circuit breaker, which `connectAfterPairing`'s direct
  /// `performConnection` call used to bypass.
  ///
  /// **Cancellation behavior** (per `await`):
  /// - `pairing.discoverDevice()` — `withTaskCancellationHandler` resumes the
  ///   continuation with `CancellationError`. On iOS the system picker may stay visible
  ///   (no public ASK API to dismiss programmatically without invalidating the
  ///   session); if the user completes pairing in the orphaned picker,
  ///   `accessoryAdded` removes the bond immediately. The device is not registered
  ///   when this point is reached, so no cleanup needed here.
  /// - `waitForOtherAppReconnection` — checks `Task.isCancelled` at the top of
  ///   each iteration and short-circuits to `false`. The subsequent
  ///   `try await connect(to:)` then surfaces the cancellation via its
  ///   entry-point `Task.checkCancellation()`.
  /// - `connect(to:)` — runs `Task.checkCancellation()` before any state mutation
  ///   so a cancelled task cannot drive a real BLE connect through to success.
  ///   Propagates `CancellationError` normally; we catch it explicitly and
  ///   re-throw without re-wrapping so the UI alert path stays quiet.
  ///
  /// Hard quit: process death; defer doesn't fire; the in-memory flag resets to
  /// `false` on next launch. No persistent state corruption.
  ///
  /// - Throws:
  ///   - `DevicePairingError.alreadyInProgress` on re-entry.
  ///   - `PairingError.deviceConnectedToOtherApp` when another app holds the radio.
  ///   - `PairingError.connectionFailed` for any other connection failure (auth,
  ///     timeout, transport error). The wrapped `underlying` is checked by
  ///     `PairingError.isAuthenticationFailure` so the auth alert path keeps working.
  ///   - `CancellationError` if the surrounding task is cancelled mid-flight.
  func pairNewDevice() async throws {
    logger.info("Starting device pairing")
    guard !isPairingInProgress else {
      throw DevicePairingError.alreadyInProgress
    }
    isPairingInProgress = true
    defer { isPairingInProgress = false }

    connectionIntent = .wantsConnection()
    persistIntent()

    await stopBLEScanning()

    // Enumeration must follow activation: the system registry reads empty until the
    // session is active. Sweeping strays before the picker keeps its confirmation
    // dialogs in the context of the pairing the user just started.
    try await pairing.activate()
    await removeStrandedAssociations()

    let deviceID = try await pairing.discoverDevice()

    if await waitForOtherAppReconnection(deviceID) {
      throw PairingError.deviceConnectedToOtherApp(deviceID: deviceID)
    }

    do {
      try await connect(to: deviceID, forceFullSync: true, forceReconnect: true)
    } catch BLEError.deviceConnectedToOtherApp {
      // No `cleanupPartialPairing` here — the bond is good; the user retries
      // after dismissing the other app via the otherAppWarningDeviceID alert.
      // Removing the bond would force a fresh pair instead.
      throw PairingError.deviceConnectedToOtherApp(deviceID: deviceID)
    } catch is CancellationError {
      await cleanupPartialPairing(deviceID: deviceID)
      throw CancellationError()
    } catch {
      // Edge case: a domain error bubbled up while the surrounding task was
      // also cancelled. Without this guard the user sees "Couldn't connect"
      // instead of silent cancellation. Re-throw as CancellationError so the
      // alert path doesn't fire.
      if Task.isCancelled {
        await cleanupPartialPairing(deviceID: deviceID)
        throw CancellationError()
      }
      logger.error("Connection after pairing failed: \(error.localizedDescription)")
      throw PairingError.connectionFailed(deviceID: deviceID, underlying: error)
    }
  }

  /// Removes a partially-paired device from the system registry when pairing is cancelled
  /// mid-flight before a usable connection exists. On iOS the system has the device; we don't.
  /// Without this cleanup, iOS retains a paired bond with no app-level state, surfacing as a
  /// phantom device in Settings → Bluetooth that won't show up in the picker again. No-op on macOS.
  private func cleanupPartialPairing(deviceID: UUID) async {
    logger.info("Removing partially-paired device \(deviceID.uuidString.prefix(8)) from pairing registry")
    try? await pairing.removeDevice(deviceID)
    // Defensive backstop — connectWithRetry and switchDevice both reset state
    // on throw, so this is normally a no-op. Kept so a future cancellation
    // path that lands here without doing so still leaves a clean UI.
    connectionState = .disconnected
  }

  /// Sweeps system pairing associations that no longer map to a saved device, run once at
  /// the start of each pairing attempt before the picker appears. A fresh pairing that failed
  /// authentication leaves its association behind when the user declines the system removal
  /// dialog, and iOS then hides it from the picker, so it can only be cleared here, while the
  /// user is actively pairing. Saved radios (their `id` matches a `Device` row), demoted ghosts
  /// (fresh random ids whose associations were already removed), and the live connection are
  /// never touched. A removal may present a system confirmation the user can decline; a decline
  /// or any other failure leaves that association in place and the flow proceeds to the picker
  /// regardless. No-op on platforms without a system pairing registry.
  private func removeStrandedAssociations() async {
    guard pairing.hasSystemPairingRegistry else { return }

    var protectedIDs = Set<UUID>()
    if let connectedID = connectedDevice?.id { protectedIDs.insert(connectedID) }
    if let attemptID = activeConnectionAttemptDeviceID { protectedIDs.insert(attemptID) }

    let dataStore = PersistenceStore(modelContainer: modelContainer)

    for info in pairing.registeredDeviceInfos() where !protectedIDs.contains(info.id) {
      let existingDevice: DeviceDTO?
      do {
        existingDevice = try await dataStore.fetchDevice(id: info.id)
      } catch {
        logger.warning("Skipping association \(info.id.uuidString.prefix(8)); device lookup failed: \(error.localizedDescription)")
        continue
      }
      guard existingDevice == nil else { continue }

      do {
        try await pairing.removeDevice(info.id)
        logger.info("Removed stranded pairing association \(info.id.uuidString.prefix(8)) with no device record")
      } catch {
        logger.warning("Failed to remove stranded association \(info.id.uuidString.prefix(8)): \(error.localizedDescription)")
      }
    }
  }

  /// Removes a device that failed to connect after pairing, for the guided
  /// "remove and retry" recovery. Demotes the row to a ghost rather than deleting
  /// it, preserving the publicKey ↔ radioID bridge: an established radio that lost
  /// its bond re-pairs onto the same radioID and its contacts, messages, and
  /// channels reattach. A fresh pairing has no row to demote, so this is a no-op there.
  /// - Parameter deviceID: The device ID from `PairingError.connectionFailed`
  func removeFailedPairing(deviceID: UUID) async {
    logger.info("Removing failed pairing for device: \(deviceID)")

    await transport.disconnect()

    do {
      try await pairing.removeDevice(deviceID)
    } catch {
      logger.warning("Failed to remove from pairing registry: \(error.localizedDescription)")
    }

    let dataStore = PersistenceStore(modelContainer: modelContainer)
    try? await dataStore.demoteDeviceToGhost(id: deviceID)

    if lastConnectedDeviceID == deviceID {
      clearPersistedConnection()
    }
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

  /// Forgets the device, removing it from paired accessories and local storage.
  /// - Parameter deleteData: If `true`, also deletes all associated data (contacts, messages, channels, trace paths).
  /// - Throws: `ConnectionError.notConnected` if no device is connected
  func forgetDevice(deleteData: Bool) async throws {
    guard let deviceID = connectedDevice?.id else {
      throw ConnectionError.notConnected
    }

    guard pairing.isDeviceConnectable(deviceID) else {
      throw ConnectionError.deviceNotFound
    }

    logger.info("Forgetting device: \(deviceID), deleteData: \(deleteData)")

    await disconnect(reason: .forgetDevice)
    try await pairing.removeDevice(deviceID)

    let dataStore = PersistenceStore(modelContainer: modelContainer)
    do {
      if deleteData {
        try await dataStore.deleteDeviceAndData(id: deviceID)
      } else {
        try await dataStore.demoteDeviceToGhost(id: deviceID)
      }
    } catch {
      logger.warning("Failed to demote device in SwiftData: \(error.localizedDescription)")
    }

    clearPersistedConnection()
    logger.info("Device forgotten")
  }

  /// Forgets a device by ID, removing it from paired accessories and local storage.
  /// Deletes both the device record and all associated data (factory reset path).
  /// Best-effort cleanup — does not throw.
  func forgetDevice(id: UUID) async {
    logger.info("Forgetting device by ID: \(id)")

    await disconnect(reason: .factoryReset)

    do {
      try await pairing.removeDevice(id)
    } catch {
      logger.warning("Failed to remove device from pairing registry: \(error.localizedDescription)")
    }

    let dataStore = PersistenceStore(modelContainer: modelContainer)
    do {
      try await dataStore.deleteDeviceAndData(id: id)
    } catch {
      logger.warning("Failed to delete device data from SwiftData: \(error.localizedDescription)")
    }

    // Drop the auto-reconnect / resume signal if we just forgot the device this install
    // last connected to, so it can't trigger a phantom reconnect or onboarding resume.
    if lastConnectedDeviceID == id {
      clearPersistedConnection()
    }

    logger.info("Device forgotten by ID: \(id)")
  }

  // MARK: - Node Management

  /// Returns the number of non-favorite contacts for the current device.
  func unfavoritedNodeCount() async throws -> Int {
    guard let radioID = connectedDevice?.radioID else {
      throw ConnectionError.notConnected
    }

    let dataStore = PersistenceStore(modelContainer: modelContainer)
    let allContacts = try await dataStore.fetchContacts(radioID: radioID)
    return allContacts.count(where: { !$0.isFavorite })
  }

  /// Removes all non-favorite contacts from the device and app, along with their messages.
  /// - Returns: Count of removed vs total non-favorite contacts
  /// - Throws: `ConnectionError.notConnected` if no device is connected
  func removeUnfavoritedNodes() async throws -> RemoveUnfavoritedResult {
    try await removeContacts(matching: { !$0.isFavorite })
  }

  /// Removes non-favorite contacts whose `lastModified` timestamp is older than the given threshold.
  /// - Parameter days: Number of days. Contacts not heard from in this many days are removed.
  /// - Returns: Count of removed vs total stale contacts
  /// - Throws: `ConnectionError.notConnected` if no device is connected
  func removeStaleNodes(olderThanDays days: Int) async throws -> RemoveUnfavoritedResult {
    let cutoff = UInt32(Date().addingTimeInterval(-Double(days) * 86400).timeIntervalSince1970)
    return try await removeContacts(matching: { !$0.isFavorite && $0.lastModified < cutoff }) { contact in
      let ageDays = (Int(Date().timeIntervalSince1970) - Int(contact.lastModified)) / 86400
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
    guard let radioID = connectedDevice?.radioID else {
      throw ConnectionError.notConnected
    }

    guard let services else {
      throw ConnectionError.notConnected
    }

    let dataStore = PersistenceStore(modelContainer: modelContainer)
    let allContacts = try await dataStore.fetchContacts(radioID: radioID)
    // Never bulk-remove the ZephCore V-contact: CMD_REMOVE turns firmware v.contact off.
    let selfPublicKey = connectedDevice?.publicKey
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

    for contact in targets {
      try Task.checkCancellation()

      do {
        try await services.contactService.removeContact(
          radioID: radioID,
          publicKey: contact.publicKey
        )
        removedCount += 1
        onRemove?(contact)
      } catch ContactServiceError.contactNotFound {
        do {
          try await services.contactService.removeLocalContact(
            contactID: contact.id,
            publicKey: contact.publicKey
          )
          removedCount += 1
          logger.info("Contact not found on device, cleaned up locally: \(contact.name)")
        } catch is CancellationError {
          throw CancellationError()
        } catch {
          logger.warning("Failed to clean up local data for \(contact.name): \(error.localizedDescription)")
        }
      } catch is CancellationError {
        throw CancellationError()
      } catch {
        logger.warning("Failed to remove contact \(contact.name): \(error.localizedDescription)")
        return RemoveUnfavoritedResult(removed: removedCount, total: targets.count)
      }
    }

    return RemoveUnfavoritedResult(removed: removedCount, total: targets.count)
  }

  // MARK: - Stale Pairings

  /// Clears all stale pairings from the system registry (AccessorySetupKit on iOS).
  /// Use when a device has been factory-reset but iOS still has the old pairing. No-op on macOS.
  func clearStalePairings() async {
    logger.info("Clearing stale pairings")
    await pairing.clearStaleRegistrations()
    logger.info("Stale pairings cleared")
  }

  // MARK: - Device Updates

  /// Updates the connected device with new settings from SelfInfo.
  /// Called by SettingsService after device settings are successfully changed.
  /// Also persists to SwiftData so changes appear in Connect Device sheet.
  func updateDevice(from selfInfo: MeshCore.SelfInfo) {
    guard let device = connectedDevice else { return }
    let updated = device.updating(from: selfInfo)
    connectedDevice = updated

    // Persist to SwiftData
    Task {
      try? await services?.dataStore.saveDevice(updated)
    }
  }

  /// Updates the connected device with a new DeviceDTO.
  /// Called by DeviceService after local device settings are successfully changed.
  func updateDevice(with deviceDTO: DeviceDTO) {
    connectedDevice = deviceDTO
  }

  /// Updates the connected device's auto-add config.
  /// Called by SettingsService after auto-add config is successfully changed.
  func updateAutoAddConfig(_ config: MeshCore.AutoAddConfig) {
    guard let device = connectedDevice else { return }
    let updated = device.copy {
      $0.autoAddConfig = config.bitmask
      $0.autoAddMaxHops = config.maxHops
    }
    connectedDevice = updated

    Task {
      do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist auto-add config: \(error)") }
    }
  }

  /// Updates the connected device's client repeat state.
  /// Called by SettingsService after client repeat is successfully changed.
  func updateClientRepeat(_ enabled: Bool) {
    guard let device = connectedDevice else { return }
    let updated = device.copy { $0.clientRepeat = enabled }
    connectedDevice = updated

    Task {
      do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist client repeat state: \(error)") }
    }
  }

  /// Updates the connected device's path hash mode.
  /// Called by SettingsService after path hash mode is successfully changed.
  func updatePathHashMode(_ mode: UInt8) {
    guard let device = connectedDevice else { return }
    let updated = device.copy { $0.pathHashMode = mode }
    connectedDevice = updated

    Task {
      do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist path hash mode: \(error)") }
    }
  }

  /// Updates the connected device's cached default flood scope name.
  /// Called by SettingsService after a `getDefaultFloodScope` read or a successful write.
  func updateDefaultFloodScopeName(_ name: String?) {
    guard let device = connectedDevice else { return }
    let updated = device.copy { $0.defaultFloodScopeName = name }
    connectedDevice = updated

    Task {
      do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist default flood scope: \(error)") }
    }
  }

  /// Appends a region to the connected device's known-regions list and persists.
  /// No-ops if the region is already present.
  func addKnownRegion(_ region: String) {
    guard let device = connectedDevice,
          !device.knownRegions.contains(region) else { return }
    let updated = device.copy { $0.knownRegions.append(region) }
    connectedDevice = updated
    Task {
      do { try await services?.dataStore.addDeviceKnownRegion(radioID: updated.radioID, region: region) } catch { logger.error("Failed to add known region: \(error)") }
      await services?.rxLogService.updateKnownRegions(updated.knownRegions)
    }
  }

  /// Removes a region from the connected device's known-regions list and persists.
  /// If the removed region is the device's current default flood scope, also clears
  /// the scope on the radio so firmware state doesn't dangle on a deleted name.
  func removeKnownRegion(_ region: String) {
    guard let device = connectedDevice else { return }
    let wasDefaultFloodScope = device.defaultFloodScopeName == region
    let updated = device.copy {
      $0.knownRegions.removeAll { $0 == region }
      if wasDefaultFloodScope {
        $0.defaultFloodScopeName = nil
      }
    }
    connectedDevice = updated
    Task {
      do {
        try await services?.dataStore.removeDeviceKnownRegion(radioID: updated.radioID, region: region)
      } catch {
        logger.error("Failed to remove known region: \(error)")
      }

      if wasDefaultFloodScope, let settingsService = services?.settingsService {
        do {
          _ = try await settingsService.setDefaultFloodScopeVerified(name: nil)
        } catch {
          logger.error("Failed to clear default flood scope after region removal: \(error)")
        }
      }

      await services?.rxLogService.updateKnownRegions(updated.knownRegions)
    }
  }

  /// Saves the connected device's current radio settings as pre-repeat settings.
  /// Called before enabling repeat mode so settings can be restored later.
  func savePreRepeatSettings() {
    guard let device = connectedDevice else { return }
    let updated = device.savingPreRepeatSettings()
    connectedDevice = updated

    Task {
      do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist pre-repeat settings: \(error)") }
    }
  }

  /// Clears the connected device's pre-repeat settings after restoration.
  func clearPreRepeatSettings() {
    guard let device = connectedDevice else { return }
    let updated = device.clearingPreRepeatSettings()
    connectedDevice = updated

    Task {
      do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist cleared pre-repeat settings: \(error)") }
    }
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
    let dataStore = PersistenceStore(modelContainer: modelContainer)
    let devices = try await dataStore.fetchDevices()
    logger.info("fetchSavedDevices returning \(devices.count) devices")
    return devices
  }

  /// Deletes a previously paired device record from storage.
  /// Demotes to ghost record — preserves publicKey ↔ radioID bridge for data recovery on re-pair.
  /// - Parameter id: The device UUID to demote
  func deleteDevice(id: UUID) async throws {
    logger.info("deleteDevice called for device: \(id)")
    let dataStore = PersistenceStore(modelContainer: modelContainer)
    try await dataStore.demoteDeviceToGhost(id: id)

    // Drop the auto-reconnect / onboarding-resume signal when the deleted row is the one
    // this install last connected to, mirroring the sibling forget paths. Without this the
    // macOS no-validation connect path grinds the full retry budget toward a device the
    // user removed, and `OnboardingState.suggestedStartingPath` still resumes onto it.
    if lastConnectedDeviceID == id {
      clearPersistedConnection()
    }

    logger.info("deleteDevice completed for device: \(id)")
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
  public func devicePairing(
    _ service: any DevicePairingService,
    didRemoveDeviceWithID bluetoothID: UUID
  ) {
    logger.info("Device removed from pairing registry: \(bluetoothID)")

    Task {
      if connectedDevice?.id == bluetoothID {
        await disconnect(reason: .deviceRemovedFromSettings)
      }

      // Demote to ghost — preserve publicKey ↔ radioID bridge
      let dataStore = PersistenceStore(modelContainer: modelContainer)
      do {
        try await dataStore.demoteDeviceToGhost(id: bluetoothID)
      } catch {
        logger.warning("Failed to demote device in SwiftData: \(error.localizedDescription)")
      }
    }

    // Clear persisted connection if it was this device
    if lastConnectedDeviceID == bluetoothID {
      clearPersistedConnection()
    }
  }

  public func devicePairing(
    _ service: any DevicePairingService,
    didFailPairingForDeviceWithID bluetoothID: UUID
  ) {
    // Clean up device record so the device can appear in picker again.
    // No data cascade — failed pairings have no associated data.
    logger.info("Pairing failed for device: \(bluetoothID)")

    Task {
      if connectedDevice?.id == bluetoothID {
        await disconnect(reason: .pairingFailed)
      }

      // Delete device record only — no data exists for a failed pairing
      let dataStore = PersistenceStore(modelContainer: modelContainer)
      do {
        try await dataStore.deleteDevice(id: bluetoothID)
        logger.info("Deleted device record after failed pairing")
      } catch {
        logger.info("No device record to delete: \(error.localizedDescription)")
      }
    }

    // Clear persisted connection if it was this device
    if lastConnectedDeviceID == bluetoothID {
      clearPersistedConnection()
    }
  }
}
