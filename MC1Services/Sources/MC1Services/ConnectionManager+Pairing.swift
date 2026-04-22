import Foundation
import MeshCore

// MARK: - Pairing

extension ConnectionManager {

    /// Pairs a new device using AccessorySetupKit picker.
    /// - Returns: The device ID if pairing succeeds but connection fails (for recovery UI)
    /// - Throws: `PairingError` with device ID if connection fails after ASK pairing succeeds
    public func pairNewDevice() async throws {
        logger.info("Starting device pairing")

        // Clear intentional disconnect flag - user is explicitly pairing
        connectionIntent = .wantsConnection()
        persistIntent()

        // Show AccessorySetupKit picker
        let deviceID = try await accessorySetupKit.showPicker()

        // Poll for other-app reconnection — ASK pairing severs existing BLE connections,
        // so the other app needs time to auto-reconnect before we can detect it
        if await waitForOtherAppReconnection(deviceID) {
            throw PairingError.deviceConnectedToOtherApp(deviceID: deviceID)
        }

        // Set connecting state for immediate UI feedback
        connectionState = .connecting

        // Connect to the newly paired device
        do {
            try await connectAfterPairing(deviceID: deviceID)
        } catch {
            // Connection failed (e.g., wrong PIN causes "Authentication is insufficient")
            // Don't auto-remove - throw error with device ID so UI can offer recovery
            logger.error("Connection after pairing failed: \(error.localizedDescription)")
            connectionState = .disconnected
            throw PairingError.connectionFailed(deviceID: deviceID, underlying: error)
        }
    }

    /// Removes a device that failed to connect after pairing.
    /// Call this when user explicitly chooses to remove and retry.
    /// No data cascade — fresh pairings have no associated data.
    /// - Parameter deviceID: The device ID from `PairingError.connectionFailed`
    public func removeFailedPairing(deviceID: UUID) async {
        logger.info("Removing failed pairing for device: \(deviceID)")

        await transport.disconnect()

        if let accessory = accessorySetupKit.accessory(for: deviceID) {
            do {
                try await accessorySetupKit.removeAccessory(accessory)
                logger.info("Removed device from ASK")
            } catch {
                logger.warning("Failed to remove from ASK: \(error.localizedDescription)")
            }
        }

        let dataStore = PersistenceStore(modelContainer: modelContainer)
        try? await dataStore.deleteDevice(id: deviceID)

        if lastConnectedDeviceID == deviceID {
            clearPersistedConnection()
        }
    }

    /// Connects to a device immediately after ASK pairing with retry logic
    private func connectAfterPairing(deviceID: UUID, maxAttempts: Int = 4) async throws {
        logger.info("[BLE] connectAfterPairing: device=\(deviceID.uuidString.prefix(8)), maxAttempts=\(maxAttempts)")
        var lastError: Error = ConnectionError.connectionFailed("Unknown error")

        for attempt in 1...maxAttempts {
            // Allow ASK/CoreBluetooth bond to register on first attempt
            if attempt == 1 {
                try await Task.sleep(for: .milliseconds(100))
            }

            do {
                try await performConnection(deviceID: deviceID)

                if attempt > 1 {
                    logger.info("Connection succeeded on attempt \(attempt)")
                }
                return

            } catch {
                lastError = error
                logger.warning("Connection attempt \(attempt) failed: \(error.localizedDescription)")

                if isDeviceNotFoundError(error) {
                    await logDeviceNotFoundDiagnostics(deviceID: deviceID, context: "connectAfterPairing attempt \(attempt)")
                }

                await cleanupResources()
                await transport.disconnect()

                if isAuthenticationError(error) {
                    logger.info("Authentication/encryption error — skipping retries")
                    break
                }

                if attempt < maxAttempts {
                    let baseDelay = 0.3 * pow(2.0, Double(attempt - 1))
                    let jitter = Double.random(in: 0...0.1) * baseDelay
                    try await Task.sleep(for: .seconds(baseDelay + jitter))
                }
            }
        }

        // All retries exhausted - caller's catch block sets .disconnected
        throw lastError
    }

    // MARK: - Other-App Detection

    /// Polls for other-app reconnection after ASK pairing disrupts existing BLE connections.
    /// ASK pairing severs the other app's BLE link; it auto-reconnects seconds later via
    /// `CBConnectPeripheralOptionEnableAutoReconnect`. This method gives it time to reappear.
    /// - Parameter deviceID: The UUID of the newly paired device
    /// - Returns: `true` if the device was detected as connected to another app
    func waitForOtherAppReconnection(_ deviceID: UUID) async -> Bool {
        let maxChecks = 6
        let interval: Duration = .milliseconds(400)

        for check in 1...maxChecks {
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
    public func forgetDevice(deleteData: Bool) async throws {
        guard let deviceID = connectedDevice?.id else {
            throw ConnectionError.notConnected
        }

        guard let accessory = accessorySetupKit.accessory(for: deviceID) else {
            throw ConnectionError.deviceNotFound
        }

        logger.info("Forgetting device: \(deviceID), deleteData: \(deleteData)")

        await disconnect(reason: .forgetDevice)
        try await accessorySetupKit.removeAccessory(accessory)

        let dataStore = PersistenceStore(modelContainer: modelContainer)
        do {
            if deleteData {
                try await dataStore.deleteDeviceAndData(id: deviceID)
            } else {
                try await dataStore.deleteDevice(id: deviceID)
            }
        } catch {
            logger.warning("Failed to delete device data from SwiftData: \(error.localizedDescription)")
        }

        clearPersistedConnection()
        logger.info("Device forgotten")
    }

    /// Forgets a device by ID, removing it from paired accessories and local storage.
    /// Deletes both the device record and all associated data (factory reset path).
    /// Best-effort cleanup — does not throw.
    public func forgetDevice(id: UUID) async {
        logger.info("Forgetting device by ID: \(id)")

        await disconnect(reason: .factoryReset)

        if let accessory = accessorySetupKit.accessory(for: id) {
            do {
                try await accessorySetupKit.removeAccessory(accessory)
            } catch {
                logger.warning("Failed to remove accessory from ASK: \(error.localizedDescription)")
            }
        }

        let dataStore = PersistenceStore(modelContainer: modelContainer)
        do {
            try await dataStore.deleteDeviceAndData(id: id)
        } catch {
            logger.warning("Failed to delete device data from SwiftData: \(error.localizedDescription)")
        }

        logger.info("Device forgotten by ID: \(id)")
    }

    // MARK: - Node Management

    /// Returns the number of non-favorite contacts for the current device.
    public func unfavoritedNodeCount() async throws -> Int {
        guard let radioID = connectedDevice?.radioID else {
            throw ConnectionError.notConnected
        }

        let dataStore = PersistenceStore(modelContainer: modelContainer)
        let allContacts = try await dataStore.fetchContacts(radioID: radioID)
        return allContacts.filter { !$0.isFavorite }.count
    }

    /// Removes all non-favorite contacts from the device and app, along with their messages.
    /// - Returns: Count of removed vs total non-favorite contacts
    /// - Throws: `ConnectionError.notConnected` if no device is connected
    public func removeUnfavoritedNodes() async throws -> RemoveUnfavoritedResult {
        try await removeContacts(matching: { !$0.isFavorite })
    }

    /// Removes non-favorite contacts whose `lastModified` timestamp is older than the given threshold.
    /// - Parameter days: Number of days. Contacts not heard from in this many days are removed.
    /// - Returns: Count of removed vs total stale contacts
    /// - Throws: `ConnectionError.notConnected` if no device is connected
    public func removeStaleNodes(olderThanDays days: Int) async throws -> RemoveUnfavoritedResult {
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
        let targets = allContacts.filter(predicate)

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

    /// Clears all stale pairings from AccessorySetupKit.
    /// Use when a device has been factory-reset but iOS still has the old pairing.
    public func clearStalePairings() async {
        let accessories = self.accessorySetupKit.pairedAccessories
        logger.info("Clearing \(accessories.count) stale pairings")

        for accessory in accessories {
            do {
                try await self.accessorySetupKit.removeAccessory(accessory)
            } catch {
                // Continue trying to remove others even if one fails
                logger.warning("Failed to remove accessory: \(error.localizedDescription)")
            }
        }

        logger.info("Stale pairings cleared")
    }

    // MARK: - Device Updates

    /// Updates the connected device with new settings from SelfInfo.
    /// Called by SettingsService after device settings are successfully changed.
    /// Also persists to SwiftData so changes appear in Connect Device sheet.
    public func updateDevice(from selfInfo: MeshCore.SelfInfo) {
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
    public func updateDevice(with deviceDTO: DeviceDTO) {
        connectedDevice = deviceDTO
    }

    /// Updates the connected device's auto-add config.
    /// Called by SettingsService after auto-add config is successfully changed.
    public func updateAutoAddConfig(_ config: MeshCore.AutoAddConfig) {
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
    public func updateClientRepeat(_ enabled: Bool) {
        guard let device = connectedDevice else { return }
        let updated = device.copy { $0.clientRepeat = enabled }
        connectedDevice = updated

        Task {
            do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist client repeat state: \(error)") }
        }
    }

    /// Updates the connected device's path hash mode.
    /// Called by SettingsService after path hash mode is successfully changed.
    public func updatePathHashMode(_ mode: UInt8) {
        guard let device = connectedDevice else { return }
        let updated = device.copy { $0.pathHashMode = mode }
        connectedDevice = updated

        Task {
            do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist path hash mode: \(error)") }
        }
    }

    /// Updates the connected device's cached default flood scope name.
    /// Called by SettingsService after a `getDefaultFloodScope` read or a successful write.
    public func updateDefaultFloodScopeName(_ name: String?) {
        guard let device = connectedDevice else { return }
        let updated = device.copy { $0.defaultFloodScopeName = name }
        connectedDevice = updated

        Task {
            do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist default flood scope: \(error)") }
        }
    }

    /// Appends a region to the connected device's known-regions list and persists.
    /// No-ops if the region is already present.
    public func addKnownRegion(_ region: String) {
        guard let device = connectedDevice,
              !device.knownRegions.contains(region) else { return }
        let updated = device.copy { $0.knownRegions.append(region) }
        connectedDevice = updated
        Task {
            do { try await services?.dataStore.addDeviceKnownRegion(radioID: updated.radioID, region: region) }
            catch { logger.error("Failed to add known region: \(error)") }
        }
    }

    /// Removes a region from the connected device's known-regions list and persists.
    /// If the removed region is the device's current default flood scope, also clears
    /// the scope on the radio so firmware state doesn't dangle on a deleted name.
    public func removeKnownRegion(_ region: String) {
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
        }
    }

    /// Saves the connected device's current radio settings as pre-repeat settings.
    /// Called before enabling repeat mode so settings can be restored later.
    public func savePreRepeatSettings() {
        guard let device = connectedDevice else { return }
        let updated = device.savingPreRepeatSettings()
        connectedDevice = updated

        Task {
            do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist pre-repeat settings: \(error)") }
        }
    }

    /// Clears the connected device's pre-repeat settings after restoration.
    public func clearPreRepeatSettings() {
        guard let device = connectedDevice else { return }
        let updated = device.clearingPreRepeatSettings()
        connectedDevice = updated

        Task {
            do { try await services?.dataStore.saveDevice(updated) } catch { logger.error("Failed to persist cleared pre-repeat settings: \(error)") }
        }
    }

    // MARK: - Accessory Management

    /// Checks if an accessory is registered with AccessorySetupKit.
    /// - Parameter deviceID: The Bluetooth UUID of the device
    /// - Returns: `true` if the accessory is available for connection
    public func hasAccessory(for deviceID: UUID) -> Bool {
        accessorySetupKit.accessory(for: deviceID) != nil
    }

    /// Fetches all previously paired devices from storage.
    /// Available even when disconnected, for device selection UI.
    public func fetchSavedDevices() async throws -> [DeviceDTO] {
        logger.info("fetchSavedDevices called, connectionState: \(String(describing: self.connectionState))")
        let dataStore = PersistenceStore(modelContainer: modelContainer)
        let devices = try await dataStore.fetchDevices()
        logger.info("fetchSavedDevices returning \(devices.count) devices")
        return devices
    }

    /// Deletes a previously paired device record from storage.
    /// Does not delete associated data — the user may re-pair to recover it.
    /// - Parameter id: The device UUID to delete
    public func deleteDevice(id: UUID) async throws {
        logger.info("deleteDevice called for device: \(id)")
        let dataStore = PersistenceStore(modelContainer: modelContainer)
        try await dataStore.deleteDevice(id: id)
        logger.info("deleteDevice completed for device: \(id)")
    }

    /// Returns paired accessories from AccessorySetupKit.
    /// Use as fallback when SwiftData has no device records.
    public var pairedAccessoryInfos: [(id: UUID, name: String)] {
        accessorySetupKit.pairedAccessories.compactMap { accessory in
            guard let id = accessory.bluetoothIdentifier else { return nil }
            return (id: id, name: accessory.displayName)
        }
    }

    /// Renames the currently connected device via AccessorySetupKit.
    /// - Throws: `ConnectionError.notConnected` if no device is connected
    public func renameCurrentDevice() async throws {
        guard let deviceID = connectedDevice?.id else {
            throw ConnectionError.notConnected
        }

        guard let accessory = accessorySetupKit.accessory(for: deviceID) else {
            throw ConnectionError.deviceNotFound
        }

        try await accessorySetupKit.renameAccessory(accessory)
    }
}

// MARK: - AccessorySetupKitServiceDelegate

extension ConnectionManager: AccessorySetupKitServiceDelegate {
    public func accessorySetupKitService(
        _ service: AccessorySetupKitService,
        didRemoveAccessoryWithID bluetoothID: UUID
    ) {
        logger.info("Device removed from ASK: \(bluetoothID)")

        Task {
            if connectedDevice?.id == bluetoothID {
                await disconnect(reason: .deviceRemovedFromSettings)
            }

            // Delete device record only — preserve user data for re-pairing
            let dataStore = PersistenceStore(modelContainer: modelContainer)
            do {
                try await dataStore.deleteDevice(id: bluetoothID)
            } catch {
                logger.warning("Failed to delete device record from SwiftData: \(error.localizedDescription)")
            }
        }

        // Clear persisted connection if it was this device
        if lastConnectedDeviceID == bluetoothID {
            clearPersistedConnection()
        }
    }

    public func accessorySetupKitService(
        _ service: AccessorySetupKitService,
        didFailPairingForAccessoryWithID bluetoothID: UUID
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
