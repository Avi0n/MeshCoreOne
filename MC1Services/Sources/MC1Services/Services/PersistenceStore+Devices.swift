import Foundation
import SwiftData

extension PersistenceStore {

    // MARK: - Device Operations

    /// Fetch all devices
    public func fetchDevices() throws -> [DeviceDTO] {
        let descriptor = FetchDescriptor<Device>(
            sortBy: [SortDescriptor(\Device.lastConnected, order: .reverse)]
        )
        let devices = try modelContext.fetch(descriptor)
        return devices.map { DeviceDTO(from: $0) }
    }

    /// Fetch a device by ID
    public func fetchDevice(id: UUID) throws -> DeviceDTO? {
        let targetID = id
        let predicate = #Predicate<Device> { device in
            device.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { DeviceDTO(from: $0) }
    }

    /// Fetch a device by radio ID
    public func fetchDevice(radioID: UUID) throws -> DeviceDTO? {
        let targetRadioID = radioID
        let predicate = #Predicate<Device> { device in
            device.radioID == targetRadioID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { DeviceDTO(from: $0) }
    }

    /// Fetch a device by public key
    public func fetchDevice(publicKey: Data) throws -> DeviceDTO? {
        let targetKey = publicKey
        let predicate = #Predicate<Device> { device in
            device.publicKey == targetKey
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { DeviceDTO(from: $0) }
    }

    /// Fetch the active device
    public func fetchActiveDevice() throws -> DeviceDTO? {
        let predicate = #Predicate<Device> { device in
            device.isActive == true
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { DeviceDTO(from: $0) }
    }

    /// Save or update a device
    public func saveDevice(_ dto: DeviceDTO) throws {
        let targetID = dto.id
        let predicate = #Predicate<Device> { device in
            device.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let existing = try modelContext.fetch(descriptor).first {
            existing.apply(dto)
        } else {
            // Create new
            let device = Device(
                id: dto.id,
                radioID: dto.radioID,
                publicKey: dto.publicKey,
                nodeName: dto.nodeName,
                firmwareVersion: dto.firmwareVersion,
                firmwareVersionString: dto.firmwareVersionString,
                manufacturerName: dto.manufacturerName,
                buildDate: dto.buildDate,
                maxContacts: dto.maxContacts,
                maxChannels: dto.maxChannels,
                frequency: dto.frequency,
                bandwidth: dto.bandwidth,
                spreadingFactor: dto.spreadingFactor,
                codingRate: dto.codingRate,
                txPower: dto.txPower,
                maxTxPower: dto.maxTxPower,
                latitude: dto.latitude,
                longitude: dto.longitude,
                blePin: dto.blePin,
                clientRepeat: dto.clientRepeat,
                pathHashMode: dto.pathHashMode,
                defaultFloodScopeName: dto.defaultFloodScopeName,
                preRepeatFrequency: dto.preRepeatFrequency,
                preRepeatBandwidth: dto.preRepeatBandwidth,
                preRepeatSpreadingFactor: dto.preRepeatSpreadingFactor,
                preRepeatCodingRate: dto.preRepeatCodingRate,
                manualAddContacts: dto.manualAddContacts,
                autoAddConfig: dto.autoAddConfig,
                autoAddMaxHops: dto.autoAddMaxHops,
                multiAcks: dto.multiAcks,
                telemetryModeBase: dto.telemetryModeBase,
                telemetryModeLoc: dto.telemetryModeLoc,
                telemetryModeEnv: dto.telemetryModeEnv,
                advertLocationPolicy: dto.advertLocationPolicy,
                lastConnected: dto.lastConnected,
                lastContactSync: dto.lastContactSync,
                isActive: dto.isActive,
                ocvPreset: dto.ocvPreset,
                customOCVArrayString: dto.customOCVArrayString,
                connectionMethods: dto.connectionMethods,
                knownRegions: dto.knownRegions
            )
            modelContext.insert(device)
        }

        try modelContext.save()
    }

    /// Set a device as active (deactivates others)
    public func setActiveDevice(id: UUID) throws {
        // Deactivate all devices first
        let allDevices = try modelContext.fetch(FetchDescriptor<Device>())
        for device in allDevices {
            device.isActive = false
        }

        // Activate the specified device
        let targetID = id
        let predicate = #Predicate<Device> { device in
            device.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let device = try modelContext.fetch(descriptor).first {
            device.isActive = true
            device.lastConnected = Date()
        }

        try modelContext.save()
    }

    /// Update the lastContactSync timestamp for a device.
    /// Used to track incremental sync progress.
    public func updateDeviceLastContactSync(radioID: UUID, timestamp: UInt32) throws {
        let targetRadioID = radioID
        let predicate = #Predicate<Device> { device in
            device.radioID == targetRadioID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let device = try modelContext.fetch(descriptor).first else {
            throw PersistenceStoreError.deviceNotFound
        }
        device.lastContactSync = timestamp
        try modelContext.save()
    }

    /// Adds a known region to a device if not already present
    public func addDeviceKnownRegion(radioID: UUID, region: String) throws {
        let targetRadioID = radioID
        let devicePredicate = #Predicate<Device> { $0.radioID == targetRadioID }
        var deviceDescriptor = FetchDescriptor<Device>(predicate: devicePredicate)
        deviceDescriptor.fetchLimit = 1

        guard let device = try modelContext.fetch(deviceDescriptor).first else {
            throw PersistenceStoreError.deviceNotFound
        }

        guard !device.knownRegions.contains(region) else { return }
        device.knownRegions.append(region)
        try modelContext.save()
    }

    /// Removes a known region from a device and resets channels that had been scoped
    /// to the removed region back to ``ChannelFloodScope/inherit``.
    public func removeDeviceKnownRegion(radioID: UUID, region: String) throws {
        let targetRadioID = radioID
        let devicePredicate = #Predicate<Device> { $0.radioID == targetRadioID }
        var deviceDescriptor = FetchDescriptor<Device>(predicate: devicePredicate)
        deviceDescriptor.fetchLimit = 1

        guard let device = try modelContext.fetch(deviceDescriptor).first else {
            throw PersistenceStoreError.deviceNotFound
        }

        device.knownRegions.removeAll { $0 == region }

        let channelPredicate = #Predicate<Channel> { $0.radioID == targetRadioID }
        let channels = try modelContext.fetch(FetchDescriptor<Channel>(predicate: channelPredicate))
        for channel in channels where channel.floodScope == .region(region) {
            channel.floodScope = .inherit
        }

        try modelContext.save()
    }

    /// Delete all data associated with a device.
    /// Deletes: reactions, room messages, remote node sessions, blocked channel senders,
    /// RX log entries, discovered nodes, contacts, messages, channels, and saved trace paths.
    /// Does NOT delete the Device record itself.
    public func deleteDeviceData(id: UUID) throws {
        try _deleteAllDeviceData(id: id)
        try modelContext.save()
    }

    /// Delete a device record only. Does NOT delete associated data.
    public func deleteDevice(id: UUID) throws {
        let targetID = id
        let devicePredicate = #Predicate<Device> { device in
            device.id == targetID
        }
        if let device = try modelContext.fetch(FetchDescriptor(predicate: devicePredicate)).first {
            modelContext.delete(device)
        }
        try modelContext.save()
    }

    /// Demotes a device row to a "ghost": preserves `publicKey` and `radioID` so the
    /// publicKey ↔ radioID bridge survives, but assigns a fresh `id`, clears `isActive`,
    /// and strips ALL connection methods so the row is hidden from
    /// `DeviceSelectionSheet`. Used in place of `deleteDevice(id:)` whenever the user
    /// removes a device but keeps their data — the next time the same physical radio
    /// is re-paired (or its keypair is restored via config import), reconciliation can
    /// rejoin the orphaned children to the new pairing.
    ///
    /// Distinct from `DeviceDTO.cleanedForImport()`, which strips only Bluetooth and
    /// keeps WiFi (so a `.mc1backup`-restored radio stays reachable over WiFi without
    /// re-pairing). Demote-on-remove is the user saying "make this go away"; preserving
    /// any connection method would leave the row showing in `DeviceSelectionSheet`
    /// because `DeviceSelectionFilter.isConnectable` matches any WiFi method.
    public func demoteDeviceToGhost(id: UUID) throws {
        let targetID = id
        let predicate = #Predicate<Device> { device in
            device.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        guard let existing = try modelContext.fetch(descriptor).first else { return }

        let ghostDTO = DeviceDTO(from: existing).copy {
            $0.id = UUID()
            $0.isActive = false
            $0.connectionMethods = []
        }

        modelContext.delete(existing)
        let ghost = Device(dto: ghostDTO)
        modelContext.insert(ghost)
        try modelContext.save()
    }

    /// Delete a device and all its associated data atomically (single save).
    /// Use for factory reset and explicit "delete all data" user action.
    public func deleteDeviceAndData(id: UUID) throws {
        try _deleteAllDeviceData(id: id)

        let targetID = id
        let devicePredicate = #Predicate<Device> { device in
            device.id == targetID
        }
        if let device = try modelContext.fetch(FetchDescriptor(predicate: devicePredicate)).first {
            modelContext.delete(device)
        }
        try modelContext.save()
    }

    /// Looks for a ghost `Device` row whose `publicKey` matches `newPublicKey`
    /// (left by a prior remove-from-MC1, or a `.mc1backup` shadow row). If found,
    /// rewrites the current device's `radioID` and `publicKey` to the ghost's values
    /// and deletes the ghost. All child rows (Contact, Message, Channel, etc.) keyed
    /// by the ghost's `radioID` are now linked to the current device for free.
    ///
    /// The predicate filters by `isActive == false`, but that alone isn't a ghost
    /// marker — `setActiveDevice(id:)` deactivates every other Device row, so a
    /// saved-but-not-currently-active real device also has `isActive == false`. To
    /// avoid ever deleting a real device row (defense against an upstream publicKey-dedup
    /// violation), we additionally require the matched row to have **no Bluetooth
    /// connection methods**: demoted ghosts strip all methods, `.mc1backup` shadow rows
    /// strip BLE via `cleanedForImport()`, and a real saved device retains its BLE
    /// `ConnectionMethod`. SwiftData `#Predicate` cannot reliably introspect array
    /// elements, so the BLE check happens post-fetch in code.
    ///
    /// - Parameters:
    ///   - currentDeviceID: The BLE peripheral UUID of the live, currently-paired device.
    ///   - newPublicKey: The radio's `selfInfo.publicKey` after `importPrivateKey`.
    /// - Returns: The new `radioID` if reconciliation occurred, otherwise `nil`.
    public func reconcileGhostIdentity(currentDeviceID: UUID, newPublicKey: Data) throws -> UUID? {
        let lookupKey = newPublicKey
        let lookupID = currentDeviceID
        let ghostPredicate = #Predicate<Device> { device in
            device.publicKey == lookupKey && device.id != lookupID && device.isActive == false
        }
        var ghostDescriptor = FetchDescriptor(predicate: ghostPredicate)
        ghostDescriptor.fetchLimit = 1
        guard let ghost = try modelContext.fetch(ghostDescriptor).first else { return nil }

        // Defensive: refuse to delete a row that still carries a Bluetooth method.
        // It's a real saved-but-inactive device (per `setActiveDevice` semantics),
        // not a ghost. Returning nil here is safe — no reconciliation, no data loss.
        if ghost.connectionMethods.contains(where: { $0.isBluetooth }) {
            return nil
        }

        let currentPredicate = #Predicate<Device> { device in
            device.id == lookupID
        }
        var currentDescriptor = FetchDescriptor(predicate: currentPredicate)
        currentDescriptor.fetchLimit = 1
        guard let current = try modelContext.fetch(currentDescriptor).first else { return nil }

        let newRadioID = ghost.radioID
        current.radioID = newRadioID
        current.publicKey = newPublicKey
        for method in ghost.connectionMethods where !method.isBluetooth {
            if !current.connectionMethods.contains(where: { $0.id == method.id }) {
                current.connectionMethods.append(method)
            }
        }

        modelContext.delete(ghost)
        try modelContext.save()
        return newRadioID
    }

    /// Stages deletion of all device-scoped data without calling save().
    /// Used by both `deleteDeviceData` and `deleteDeviceAndData` to compose
    /// operations while maintaining single-save atomicity.
    private func _deleteAllDeviceData(id: UUID) throws {
        // Look up the Device's radioID since child records are keyed by radioID, not BLE UUID
        let targetBLEID = id
        let devicePredicate = #Predicate<Device> { device in
            device.id == targetBLEID
        }
        guard let device = try modelContext.fetch(FetchDescriptor(predicate: devicePredicate)).first else {
            return
        }
        let targetRadioID = device.radioID

        // Delete reactions (references messages via messageID)
        let reactionPredicate = #Predicate<Reaction> { reaction in
            reaction.radioID == targetRadioID
        }
        let reactions = try modelContext.fetch(FetchDescriptor(predicate: reactionPredicate))
        for reaction in reactions { modelContext.delete(reaction) }

        // Delete room messages via their sessions, then clean up NodeStatusSnapshots
        let sessionPredicate = #Predicate<RemoteNodeSession> { session in
            session.radioID == targetRadioID
        }
        let sessions = try modelContext.fetch(FetchDescriptor(predicate: sessionPredicate))

        // Collect publicKeys before deleting sessions for NodeStatusSnapshot cleanup
        let sessionPublicKeys = sessions.map { $0.publicKey }

        for session in sessions {
            let sessionID = session.id
            let roomMessagePredicate = #Predicate<RoomMessage> { message in
                message.sessionID == sessionID
            }
            let roomMessages = try modelContext.fetch(FetchDescriptor(predicate: roomMessagePredicate))
            for roomMessage in roomMessages { modelContext.delete(roomMessage) }
            modelContext.delete(session)
        }

        // Delete NodeStatusSnapshots only when no other session references that node
        for pubKey in sessionPublicKeys {
            let remainingPredicate = #Predicate<RemoteNodeSession> { session in
                session.publicKey == pubKey
            }
            let remainingCount = try modelContext.fetchCount(FetchDescriptor(predicate: remainingPredicate))
            guard remainingCount == 0 else { continue }

            let snapshotPredicate = #Predicate<NodeStatusSnapshot> { snapshot in
                snapshot.nodePublicKey == pubKey
            }
            let snapshots = try modelContext.fetch(FetchDescriptor(predicate: snapshotPredicate))
            for snapshot in snapshots { modelContext.delete(snapshot) }
        }

        // Delete blocked channel senders
        let blockedPredicate = #Predicate<BlockedChannelSender> { blocked in
            blocked.radioID == targetRadioID
        }
        let blockedSenders = try modelContext.fetch(FetchDescriptor(predicate: blockedPredicate))
        for blocked in blockedSenders { modelContext.delete(blocked) }

        // Delete RX log entries
        let rxLogPredicate = #Predicate<RxLogEntry> { entry in
            entry.radioID == targetRadioID
        }
        let rxLogEntries = try modelContext.fetch(FetchDescriptor(predicate: rxLogPredicate))
        for entry in rxLogEntries { modelContext.delete(entry) }

        // Delete discovered nodes
        let discoveredPredicate = #Predicate<DiscoveredNode> { node in
            node.radioID == targetRadioID
        }
        let discoveredNodes = try modelContext.fetch(FetchDescriptor(predicate: discoveredPredicate))
        for node in discoveredNodes { modelContext.delete(node) }

        // Delete contacts
        let contactPredicate = #Predicate<Contact> { contact in
            contact.radioID == targetRadioID
        }
        let contacts = try modelContext.fetch(FetchDescriptor(predicate: contactPredicate))
        for contact in contacts { modelContext.delete(contact) }

        // Delete messages — cascade PendingSend and MessageRepeat first.
        // Bulk `delete(model:where:)` bypasses `@Relationship(deleteRule: .cascade)`,
        // and `purgeOrphanPendingSends` cannot reap radio-scoped PendingSends
        // while the Device row survives (deleteDeviceData preserves it).
        let messagePredicate = #Predicate<Message> { message in
            message.radioID == targetRadioID
        }
        let messages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))
        let messageIDs = messages.map(\.id)
        try _deletePendingSendsForMessageIDsWithoutSaving(messageIDs: messageIDs)
        if !messageIDs.isEmpty {
            let chunkSize = 500
            for start in stride(from: 0, to: messageIDs.count, by: chunkSize) {
                let chunk = Array(messageIDs[start..<min(start + chunkSize, messageIDs.count)])
                try modelContext.delete(model: MessageRepeat.self, where: #Predicate {
                    chunk.contains($0.messageID)
                })
            }
        }
        // Defensive: reap PendingSend rows for this radio that have no matching
        // Message (e.g. from a same-millisecond race between deleteMessage and
        // upsertPendingSend, or historical bugs). The messageIDs cascade above
        // does not see them; purgeOrphanPendingSends does not see them while
        // the Device row survives.
        try modelContext.delete(model: PendingSend.self, where: #Predicate<PendingSend> { row in
            row.radioID == targetRadioID
        })
        for message in messages { modelContext.delete(message) }

        // Delete channels
        let channelPredicate = #Predicate<Channel> { channel in
            channel.radioID == targetRadioID
        }
        let channels = try modelContext.fetch(FetchDescriptor(predicate: channelPredicate))
        for channel in channels { modelContext.delete(channel) }

        // Delete saved trace paths
        let pathPredicate = #Predicate<SavedTracePath> { path in
            path.radioID == targetRadioID
        }
        let paths = try modelContext.fetch(FetchDescriptor(predicate: pathPredicate))
        for path in paths { modelContext.delete(path) }
    }
}
