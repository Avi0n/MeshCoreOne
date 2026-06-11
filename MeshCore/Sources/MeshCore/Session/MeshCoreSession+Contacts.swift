import Foundation

extension MeshCoreSession {

    // MARK: - Contact Management

    /// Returns the currently cached contacts.
    ///
    /// This property returns contacts from the local cache without making a device request.
    /// Use ``getContacts(since:)`` or ``ensureContacts(force:)`` to refresh from the device.
    public var cachedContacts: [MeshContact] {
        contactManager.cachedContacts
    }

    /// Returns pending contacts awaiting confirmation.
    ///
    /// These are contacts that have been discovered but not yet added to the device's
    /// contact list. Use ``addContact(_:)`` to add them permanently.
    public var cachedPendingContacts: [MeshContact] {
        contactManager.cachedPendingContacts
    }

    /// Finds a contact by advertised name.
    ///
    /// - Parameters:
    ///   - name: The name to search for.
    ///   - exactMatch: If `true`, requires exact match. If `false`, uses case-insensitive
    ///                 localized search (default).
    /// - Returns: The matching contact, or `nil` if not found.
    public func getContactByName(_ name: String, exactMatch: Bool = false) -> MeshContact? {
        contactManager.getByName(name, exactMatch: exactMatch)
    }

    /// Removes and returns a pending contact.
    ///
    /// - Parameter publicKey: The hex string of the contact's public key.
    /// - Returns: The removed contact, or `nil` if not found in pending contacts.
    public func popPendingContact(publicKey: String) -> MeshContact? {
        contactManager.popPending(publicKey: publicKey)
    }

    /// Removes all pending contacts from the cache.
    public func flushPendingContacts() {
        contactManager.flushPending()
    }

    /// Finds a contact by public key prefix (hex string).
    ///
    /// - Parameter prefix: The hex string prefix to match (e.g., "a1b2c3").
    /// - Returns: The matching contact, or `nil` if not found.
    public func getContactByKeyPrefix(_ prefix: String) -> MeshContact? {
        contactManager.getByKeyPrefix(prefix)
    }

    /// Finds a contact by public key prefix (raw data).
    ///
    /// - Parameter prefix: The raw bytes of the public key prefix to match.
    /// - Returns: The matching contact, or `nil` if not found.
    public func getContactByKeyPrefix(_ prefix: Data) -> MeshContact? {
        contactManager.getByKeyPrefix(prefix)
    }

    /// Indicates whether the contact cache needs refreshing.
    ///
    /// Returns `true` if contacts have been modified since the last fetch,
    /// or if the cache has never been populated.
    public var isContactsDirty: Bool { contactManager.needsRefresh }

    /// Enables or disables automatic contact updates.
    ///
    /// When enabled, the session automatically refreshes contacts when it
    /// receives advertisements or path updates from the device.
    ///
    /// - Parameter enabled: Whether to enable auto-updates.
    public func setAutoUpdateContacts(_ enabled: Bool) {
        contactManager.setAutoUpdate(enabled)
    }

    /// Ensures contacts are loaded, fetching from device if needed.
    ///
    /// - Parameter force: If `true`, always fetches from device. If `false`,
    ///                    uses cached contacts if available and not dirty.
    /// - Returns: The current contacts.
    /// - Throws: ``MeshCoreError`` if the fetch fails.
    public func ensureContacts(force: Bool = false) async throws -> [MeshContact] {
        if force || contactManager.needsRefresh || contactManager.isEmpty {
            return try await getContacts(since: contactManager.contactsLastModified)
        }
        return cachedContacts
    }

    /// Fetches contacts from the device.
    ///
    /// This method queries the device for its contact list, optionally filtering
    /// to contacts modified since a given date.
    ///
    /// - Parameter lastModified: If provided, only returns contacts modified after this date.
    ///                          Use `nil` to fetch all contacts.
    /// - Returns: Array of contacts from the device.
    /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
    ///           ``MeshCoreError/deviceError(code:)`` if the device returns an error.
    public func getContacts(since lastModified: Date? = nil) async throws -> [MeshContact] {
        let (contacts, modifiedDate): ([MeshContact], Date?) = try await requestResponseSerializer.withSerialization { [self] in
            let data = PacketBuilder.getContacts(since: lastModified)
            let (subscriptionID, events) = await dispatcher.subscribeTracked()

            do {
                try await transport.send(data)

                // Manual timeout pattern (not withTimeout) because:
                // 1. Uses injected clock for testability
                // 2. Throws MeshCoreError.timeout for consistency with other session methods
                // 3. Defers contactManager mutations until after the serialization closure
                //    to avoid actor-isolation issues in the @Sendable closure.
                return try await withThrowingTaskGroup(
                    of: ([MeshContact], Date?).self
                ) { group in
                    let progressTracker = StreamProgressTracker()
                    group.addTask {
                        var receivedContacts: [MeshContact] = []
                        var finalModifiedDate: Date?

                        for await event in events {
                            if Task.isCancelled {
                                throw CancellationError()
                            }

                            switch event {
                            case .contactsStart(let count):
                                await progressTracker.markProgress()
                                receivedContacts.reserveCapacity(count)
                            case .contact(let contact):
                                await progressTracker.markProgress()
                                receivedContacts.append(contact)
                            case .contactsEnd(let modifiedDate):
                                await progressTracker.markProgress()
                                finalModifiedDate = modifiedDate
                                return (receivedContacts, finalModifiedDate)
                            case .error(let code):
                                throw MeshCoreError.deviceError(code: code ?? 0)
                            default:
                                continue
                            }
                        }

                        throw MeshCoreError.timeout
                    }

                    let inactivityTimeout = configuration.contactStreamInactivityTimeout
                    let hardTimeout = configuration.contactStreamHardTimeout
                    let sessionClock = clock
                    group.addTask { [sessionClock, inactivityTimeout, hardTimeout] in
                        while true {
                            let beforeSleep = await progressTracker.snapshot()
                            if beforeSleep.elapsed >= hardTimeout {
                                throw MeshCoreError.timeout
                            }

                            let remainingHardTimeout = max(0.001, hardTimeout - beforeSleep.elapsed)
                            let sleepDuration = min(inactivityTimeout, remainingHardTimeout)
                            try await sessionClock.sleep(for: .seconds(sleepDuration))

                            let afterSleep = await progressTracker.snapshot()
                            if afterSleep.elapsed >= hardTimeout || afterSleep.generation == beforeSleep.generation {
                                throw MeshCoreError.timeout
                            }
                        }
                    }

                    do {
                        guard let result = try await group.next() else {
                            group.cancelAll()
                            await dispatcher.finishSubscription(id: subscriptionID)
                            throw MeshCoreError.timeout
                        }
                        group.cancelAll()
                        await dispatcher.finishSubscription(id: subscriptionID)
                        return result
                    } catch {
                        group.cancelAll()
                        await dispatcher.finishSubscription(id: subscriptionID)
                        throw error
                    }
                }
            } catch {
                await dispatcher.finishSubscription(id: subscriptionID)
                throw error
            }
        }

        // Update contact manager on the actor after the serialized exchange completes
        for contact in contacts {
            contactManager.store(contact)
        }
        if let modifiedDate {
            contactManager.markClean(lastModified: modifiedDate)
        }

        return contacts
    }

    /// Fetches a single contact from the device by public key.
    ///
    /// - Parameter publicKey: The full 32-byte public key of the contact.
    /// - Returns: The contact if found, or `nil` if no contact exists with that key.
    /// - Throws: ``MeshCoreError/timeout`` if the device doesn't emit a matching contact response.
    public func getContact(publicKey: Data) async throws -> MeshContact? {
        try requireFullPublicKey(publicKey, operation: "getContact")
        let data = PacketBuilder.getContactByKey(publicKey: publicKey)
        return try await sendAndMatch(data) { event in
            switch event {
            case .contact(let contact):
                if contact.publicKey == publicKey {
                    return .success(contact)
                }
                return .ignore
            case .error:
                // Contact not found returns error, treat as nil
                return .success(nil)
            default:
                return .ignore
            }
        }
    }

    // MARK: - Contact Commands

    /// Resets the routing path for a contact.
    ///
    /// Clears the stored path, forcing the device to rediscover the route.
    /// This can help resolve routing issues or adapt to network changes.
    ///
    /// - Parameter publicKey: The full 32-byte public key of the contact.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func resetPath(publicKey: Data) async throws {
        try requireFullPublicKey(publicKey, operation: "resetPath")
        try await sendSimpleCommand(PacketBuilder.resetPath(publicKey: publicKey))
    }

    /// Removes a contact from the device's contact list.
    ///
    /// - Parameter publicKey: The full 32-byte public key of the contact to remove.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func removeContact(publicKey: Data) async throws {
        try requireFullPublicKey(publicKey, operation: "removeContact")
        try await sendSimpleCommand(PacketBuilder.removeContact(publicKey: publicKey))
    }

    /// Shares a contact with nearby devices via broadcast.
    ///
    /// Broadcasts the contact's information to other mesh nodes, allowing them
    /// to add it to their contact lists if desired.
    ///
    /// - Parameter publicKey: The full 32-byte public key of the contact to share.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func shareContact(publicKey: Data) async throws {
        try requireFullPublicKey(publicKey, operation: "shareContact")
        try await sendSimpleCommand(PacketBuilder.shareContact(publicKey: publicKey))
    }

    /// Exports a contact as a shareable URI string.
    ///
    /// - Parameter publicKey: The contact's public key, or `nil` to export self.
    /// - Returns: A URI string encoding the contact information.
    /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
    public func exportContact(publicKey: Data? = nil) async throws -> String {
        if let publicKey {
            try requireFullPublicKey(publicKey, operation: "exportContact")
        }
        let requestedKeyHex = publicKey?.hexString
        return try await sendAndWait(PacketBuilder.exportContact(publicKey: publicKey)) { event in
            guard case .contactURI(let uri) = event else { return nil }
            // The exported card begins with the contact's public key, so a card for a
            // different contact (e.g. an orphan from a cancelled export whose response
            // lands after this command's timeout) is rejected rather than returned.
            if let requestedKeyHex, !uri.contains(requestedKeyHex) { return nil }
            return uri
        }
    }

    /// Imports a contact from encoded contact card data.
    ///
    /// - Parameter cardData: The contact card data (typically from a QR code or URI).
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func importContact(cardData: Data) async throws {
        var data = Data([CommandCode.importContact.rawValue])
        data.append(cardData)
        try await sendSimpleCommand(data)
    }

    /// Updates or creates a contact with full details.
    ///
    /// This is a low-level method that sets all contact fields. Consider using
    /// higher-level methods like ``addContact(_:)``, ``changeContactPath(_:path:)``,
    /// or ``changeContactFlags(_:flags:)`` instead.
    ///
    /// - Parameters:
    ///   - publicKey: The full 32-byte public key.
    ///   - type: Contact type identifier.
    ///   - flags: Contact flags for capabilities and permissions.
    ///   - outPathLength: Encoded outbound path length byte, or `0xFF` for flood.
    ///   - outPath: The routing path (up to 64 bytes).
    ///   - advertisedName: The contact's advertised name.
    ///   - lastAdvertisement: Timestamp of last received advertisement.
    ///   - latitude: GPS latitude in degrees.
    ///   - longitude: GPS longitude in degrees.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func updateContact(
        publicKey: Data,
        type: ContactType,
        flags: ContactFlags,
        outPathLength: UInt8,
        outPath: Data,
        advertisedName: String,
        lastAdvertisement: Date,
        latitude: Double,
        longitude: Double
    ) async throws {
        var data = Data([CommandCode.updateContact.rawValue])
        data.append(publicKey.prefix(PacketBuilder.publicKeySize))
        data.append(type.rawValue)
        data.append(flags.rawValue)
        data.append(outPathLength)

        var pathData = outPath.prefix(64)
        while pathData.count < 64 {
            pathData.append(0)
        }
        data.append(pathData)

        var nameData = (advertisedName.data(using: .utf8) ?? Data()).prefix(32)
        while nameData.count < 32 {
            nameData.append(0)
        }
        data.append(nameData)

        let lastAdvert = PacketBuilder.epochSeconds32(lastAdvertisement)
        data.append(contentsOf: withUnsafeBytes(of: lastAdvert.littleEndian) { Array($0) })

        let lat = PacketBuilder.scaledCoordinate(latitude, in: PacketBuilder.latitudeRange)
        let lon = PacketBuilder.scaledCoordinate(longitude, in: PacketBuilder.longitudeRange)
        data.append(contentsOf: withUnsafeBytes(of: lat.littleEndian) { Array($0) })
        data.append(contentsOf: withUnsafeBytes(of: lon.littleEndian) { Array($0) })

        try await sendSimpleCommand(data)
    }

    /// Adds a contact to the device's contact list.
    ///
    /// Encodes via ``PacketBuilder/updateContact(_:)``, which forwards the raw
    /// ``MeshContact/typeRawValue`` byte and uses the saturating coordinate/timestamp
    /// encoders, so a contact carrying a type byte not modeled by ``ContactType``
    /// (e.g. from an imported config) reaches the device verbatim instead of being
    /// coerced. The 147-byte frame is wire-equivalent to the prior 144-byte frame:
    /// firmware applies the gps_lat/gps_lon coordinates in `[136, 144)` when the
    /// payload length is at least 144, and intentionally omits last_mod in `[144, 148)`
    /// (147 is below the 148-byte threshold), so the device restamps it from its RTC.
    ///
    /// - Parameter contact: The contact to add.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func addContact(_ contact: MeshContact) async throws {
        try await sendSimpleCommand(PacketBuilder.updateContact(contact))
    }

    /// Changes the routing path for a contact.
    ///
    /// Updates only the path while preserving all other contact information.
    ///
    /// - Parameters:
    ///   - contact: The contact to modify.
    ///   - path: The new routing path, or empty data to reset to flood.
    ///   - hashSize: Bytes per path hop (1, 2, or 3). Defaults to 1 for backward compatibility.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func changeContactPath(_ contact: MeshContact, path: Data, hashSize: UInt8 = 1) async throws {
        let pathLength: UInt8
        if path.isEmpty {
            pathLength = 0xFF
        } else {
            pathLength = encodePathLen(hashSize: Int(hashSize), hopCount: path.count / Int(hashSize))
        }
        try await updateContact(
            publicKey: contact.publicKey,
            type: contact.type,
            flags: contact.flags,
            outPathLength: pathLength,
            outPath: path,
            advertisedName: contact.advertisedName,
            lastAdvertisement: contact.lastAdvertisement,
            latitude: contact.latitude,
            longitude: contact.longitude
        )
    }

    /// Changes the flags for a contact.
    ///
    /// Updates only the flags while preserving all other contact information.
    ///
    /// - Parameters:
    ///   - contact: The contact to modify.
    ///   - flags: The new flags value.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func changeContactFlags(_ contact: MeshContact, flags: ContactFlags) async throws {
        try await updateContact(
            publicKey: contact.publicKey,
            type: contact.type,
            flags: flags,
            outPathLength: contact.outPathLength,
            outPath: contact.outPath,
            advertisedName: contact.advertisedName,
            lastAdvertisement: contact.lastAdvertisement,
            latitude: contact.latitude,
            longitude: contact.longitude
        )
    }
}
