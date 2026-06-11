import Foundation
import MeshCore
import os

// MARK: - Contact Service Errors

public enum ContactServiceError: Error, Sendable, LocalizedError {
    case notConnected
    case sendFailed
    case invalidResponse
    case syncInterrupted
    case contactNotFound
    case contactTableFull
    case shareContactUnavailable
    case sessionError(MeshCoreError)

    public var errorDescription: String? {
        switch self {
        case .notConnected:
            return "Not connected to radio"
        case .sendFailed:
            return "Failed to send message"
        case .invalidResponse:
            return "Invalid response from device"
        case .syncInterrupted:
            return "Sync was interrupted"
        case .contactNotFound:
            return "Contact not found on device"
        case .contactTableFull:
            return "Device node list is full"
        case .shareContactUnavailable:
            return "Unable to share node. The node's advertisement may be missing or too old."
        case .sessionError(let error):
            return error.localizedDescription
        }
    }
}

/// Reason for contact cleanup (deletion or blocking)
public enum ContactCleanupReason: Sendable {
    case deleted
    case blocked
    case unblocked
}

// MARK: - Sync Result

/// Result of a contact sync operation
public struct ContactSyncResult: Sendable {
    public let contactsReceived: Int
    public let lastSyncTimestamp: UInt32
    public let isIncremental: Bool

    public init(contactsReceived: Int, lastSyncTimestamp: UInt32, isIncremental: Bool) {
        self.contactsReceived = contactsReceived
        self.lastSyncTimestamp = lastSyncTimestamp
        self.isIncremental = isIncremental
    }
}

// MARK: - Contact Service

/// Service for managing mesh network contacts.
/// Handles contact discovery, sync, add/update/remove operations.
public actor ContactService {

    // MARK: - Properties

    private let session: any MeshCoreSessionProtocol
    private let dataStore: any PersistenceStoreProtocol
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "ContactService")

    /// Sync coordinator for UI refresh notifications.
    /// Installed by `ServiceContainer.wireServices`.
    private weak var syncCoordinator: SyncCoordinator?

    /// Progress handler for sync operations.
    /// Installed by `ContactsViewModel.syncContacts` for the duration of a sync.
    private var syncProgressHandler: (@Sendable (Int, Int) -> Void)?

    /// Cleanup handler called when a contact is deleted or blocked.
    /// Installed by `ServiceContainer.wireServices`.
    private var cleanupHandler: (@Sendable (UUID, ContactCleanupReason, Data) async -> Void)?

    /// Handler called when a node is deleted (for clearing storage full flag).
    /// Installed by `ConnectionUIState.wireCallbacks`.
    private var nodeDeletedHandler: (@Sendable () async -> Void)?

    // MARK: - Initialization

    public init(session: any MeshCoreSessionProtocol, dataStore: any PersistenceStoreProtocol) {
        self.session = session
        self.dataStore = dataStore
    }

    // MARK: - Configuration

    /// Set the sync coordinator for UI refresh notifications
    public func setSyncCoordinator(_ coordinator: SyncCoordinator) {
        self.syncCoordinator = coordinator
    }

    /// Set progress handler for sync operations
    public func setSyncProgressHandler(_ handler: @escaping @Sendable (Int, Int) -> Void) {
        syncProgressHandler = handler
    }

    /// Set handler for contact cleanup operations (deletion/blocking)
    public func setCleanupHandler(_ handler: @escaping @Sendable (UUID, ContactCleanupReason, Data) async -> Void) {
        cleanupHandler = handler
    }

    /// Whether a sync coordinator has been wired via `setSyncCoordinator`.
    var hasSyncCoordinatorWired: Bool { syncCoordinator != nil }

    /// Whether a cleanup handler has been wired via `setCleanupHandler`.
    var hasCleanupHandlerWired: Bool { cleanupHandler != nil }

    /// Set handler for node deletion events (for clearing storage full flag)
    public func setNodeDeletedHandler(_ handler: @escaping @Sendable () async -> Void) {
        nodeDeletedHandler = handler
    }

    // MARK: - Contact Sync

    /// Sync all contacts from device
    /// - Parameters:
    ///   - radioID: The device to sync from
    ///   - since: Optional date for incremental sync (only contacts modified after this time)
    /// - Returns: Sync result with count and timestamp
    public func syncContacts(radioID: UUID, since: Date? = nil) async throws -> ContactSyncResult {
        do {
            let meshContacts = try await session.getContacts(since: since)

            syncProgressHandler?(0, meshContacts.count)

            // Build set of public keys from device for cleanup
            let devicePublicKeys = Set(meshContacts.map(\.publicKey))

            // Persist every received contact in a single transaction rather than one
            // commit per contact; the per-item save was redundant overhead on every sync.
            let frames = meshContacts.map { $0.toContactFrame() }
            let receivedCount = try await dataStore.batchSaveContacts(radioID: radioID, from: frames)

            let lastTimestamp = meshContacts
                .map { UInt32($0.lastModified.timeIntervalSince1970) }
                .max() ?? 0

            syncProgressHandler?(receivedCount, meshContacts.count)

            // On full sync, remove local contacts that no longer exist on device
            if since == nil {
                let localContacts = try await dataStore.fetchContacts(radioID: radioID)
                let orphans = localContacts.filter { !devicePublicKeys.contains($0.publicKey) }
                if !orphans.isEmpty {
                    logger.notice("Full sync prune: \(orphans.count) local contact(s) not found on device (device has \(devicePublicKeys.count), local has \(localContacts.count))")
                }
                for localContact in orphans {
                    let keyPrefix = localContact.publicKey.prefix(4).map { String(format: "%02x", $0) }.joined()
                    logger.notice("Full sync prune: deleting '\(localContact.name)' [\(keyPrefix)…] (favorite=\(localContact.isFavorite), type=\(localContact.typeRawValue), lastModified=\(localContact.lastModified))")
                    try await dataStore.deleteMessagesForContact(contactID: localContact.id)
                    try await dataStore.deleteContact(id: localContact.id)
                    await cleanupHandler?(localContact.id, .deleted, localContact.publicKey)
                }
            }

            return ContactSyncResult(
                contactsReceived: receivedCount,
                lastSyncTimestamp: lastTimestamp,
                isIncremental: since != nil
            )
        } catch let error as MeshCoreError {
            throw ContactServiceError.sessionError(error)
        }
    }

    // MARK: - Get Contact

    /// Get a specific contact by public key from local database
    /// - Parameters:
    ///   - radioID: The device ID
    ///   - publicKey: The contact's 32-byte public key
    /// - Returns: The contact if found
    public func getContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO? {
        try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey)
    }

    // MARK: - Add/Update Contact

    /// Add or update a contact on the device
    /// - Parameters:
    ///   - radioID: The device ID
    ///   - contact: The contact to add/update
    public func addOrUpdateContact(radioID: UUID, contact: ContactFrame) async throws {
        do {
            let meshContact = contact.toMeshContact()
            try await session.addContact(meshContact)

            // Save to local database
            _ = try await dataStore.saveContact(radioID: radioID, from: contact)

            // Notify UI to refresh contacts list
            await syncCoordinator?.notifyContactsChanged()
        } catch let error as MeshCoreError {
            if case .deviceError(let code) = error, code == ProtocolError.tableFull.rawValue {
                throw ContactServiceError.contactTableFull
            }
            throw ContactServiceError.sessionError(error)
        }
    }

    // MARK: - Remove Contact

    /// Remove a contact from the device
    /// - Parameters:
    ///   - radioID: The device ID
    ///   - publicKey: The contact's 32-byte public key
    public func removeContact(radioID: UUID, publicKey: Data) async throws {
        do {
            try await session.removeContact(publicKey: publicKey)

            // Remove from local database
            if let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) {
                let contactID = contact.id

                // Delete associated messages first
                try await dataStore.deleteMessagesForContact(contactID: contactID)

                // Delete the contact
                try await dataStore.deleteContact(id: contactID)

                // Trigger cleanup (notifications, badge, session)
                await cleanupHandler?(contactID, .deleted, publicKey)
            }

            // Notify that a node was deleted (for clearing storage full flag)
            await nodeDeletedHandler?()

            // Notify UI to refresh contacts list
            await syncCoordinator?.notifyContactsChanged()
        } catch let error as MeshCoreError {
            if case .deviceError(let code) = error, code == ProtocolError.notFound.rawValue {
                throw ContactServiceError.contactNotFound
            }
            throw ContactServiceError.sessionError(error)
        }
    }

    /// Remove a contact's local data and run the full cleanup chain without contacting the device.
    /// Use when the device reports the contact doesn't exist but local data remains.
    public func removeLocalContact(contactID: UUID, publicKey: Data) async throws {
        try await dataStore.deleteMessagesForContact(contactID: contactID)
        try await dataStore.deleteContact(id: contactID)
        await cleanupHandler?(contactID, .deleted, publicKey)
        await nodeDeletedHandler?()
        await syncCoordinator?.notifyContactsChanged()
    }

    // MARK: - Reset Path

    /// Reset the path for a contact (force rediscovery)
    /// - Parameters:
    ///   - radioID: The device ID
    ///   - publicKey: The contact's 32-byte public key
    public func resetPath(radioID: UUID, publicKey: Data) async throws {
        do {
            try await session.resetPath(publicKey: publicKey)

            // Update local contact to show flood routing
            if let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) {
                let frame = ContactFrame(
                    publicKey: contact.publicKey,
                    type: contact.type,
                    flags: contact.flags,
                    outPathLength: PacketBuilder.floodPathSentinel,
                    outPath: Data(),
                    name: contact.name,
                    lastAdvertTimestamp: contact.lastAdvertTimestamp,
                    latitude: contact.latitude,
                    longitude: contact.longitude,
                    lastModified: UInt32(Date().timeIntervalSince1970)
                )
                _ = try await dataStore.saveContact(radioID: radioID, from: frame)
            }
        } catch let error as MeshCoreError {
            if case .deviceError(let code) = error, code == ProtocolError.notFound.rawValue {
                throw ContactServiceError.contactNotFound
            }
            throw ContactServiceError.sessionError(error)
        }
    }

    // MARK: - Path Discovery

    /// Send a path discovery request to find optimal route to contact
    /// - Parameters:
    ///   - radioID: The device ID
    ///   - publicKey: The contact's 32-byte public key
    /// - Returns: MessageSentInfo containing the estimated timeout from firmware
    public func sendPathDiscovery(radioID: UUID, publicKey: Data) async throws -> MessageSentInfo {
        do {
            return try await session.sendPathDiscovery(to: publicKey)
        } catch let error as MeshCoreError {
            if case .deviceError(let code) = error, code == ProtocolError.notFound.rawValue {
                throw ContactServiceError.contactNotFound
            }
            throw ContactServiceError.sessionError(error)
        }
    }

    // MARK: - Set Path

    /// Set a specific path for a contact
    /// - Parameters:
    ///   - radioID: The device ID
    ///   - publicKey: The contact's 32-byte public key
    ///   - path: The path data (repeater hashes)
    ///   - pathLength: Encoded path length byte (0xFF for flood, 0 for direct, >0 for routed)
    public func setPath(radioID: UUID, publicKey: Data, path: Data, pathLength: UInt8) async throws {
        // Get current contact to preserve other fields
        guard let existingContact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) else {
            throw ContactServiceError.contactNotFound
        }

        // Create updated contact frame with new path
        let updatedFrame = ContactFrame(
            publicKey: existingContact.publicKey,
            type: existingContact.type,
            flags: existingContact.flags,
            outPathLength: pathLength,
            outPath: path,
            name: existingContact.name,
            lastAdvertTimestamp: existingContact.lastAdvertTimestamp,
            latitude: existingContact.latitude,
            longitude: existingContact.longitude,
            lastModified: UInt32(Date().timeIntervalSince1970)
        )

        // Send update to device
        try await addOrUpdateContact(radioID: radioID, contact: updatedFrame)
    }

    // MARK: - Share Contact

    /// Share a contact via zero-hop broadcast
    /// - Parameter publicKey: The contact's 32-byte public key to share
    public func shareContact(publicKey: Data) async throws {
        do {
            try await session.shareContact(publicKey: publicKey)
        } catch let error as MeshCoreError {
            if case .deviceError(let code) = error, code == ProtocolError.tableFull.rawValue {
                throw ContactServiceError.shareContactUnavailable
            }
            if case .deviceError(let code) = error, code == ProtocolError.notFound.rawValue {
                throw ContactServiceError.contactNotFound
            }
            throw ContactServiceError.sessionError(error)
        }
    }

    // MARK: - Export/Import Contact

    /// Export a contact to a shareable URI (legacy firmware call)
    /// - Parameter publicKey: The contact's 32-byte public key (nil for self)
    /// - Returns: Contact URI string
    @available(*, deprecated, message: "Use exportContactURI(name:publicKey:type:) instead")
    public func exportContact(publicKey: Data? = nil) async throws -> String {
        do {
            return try await session.exportContact(publicKey: publicKey)
        } catch let error as MeshCoreError {
            throw ContactServiceError.sessionError(error)
        }
    }

    private static let contactURIScheme = "meshcore"
    private static let contactURIHost = "contact"
    private static let contactURIPath = "/add"
    private static let contactURINameKey = "name"
    private static let contactURIPublicKeyKey = "public_key"
    private static let contactURITypeKey = "type"

    /// Build a shareable contact URI from contact information
    /// - Parameters:
    ///   - name: The contact's advertised name
    ///   - publicKey: The contact's 32-byte public key
    ///   - type: The contact type (chat, repeater, room)
    /// - Returns: Contact URI string in format: meshcore://contact/add?name=...&public_key=...&type=...
    public static func exportContactURI(name: String, publicKey: Data, type: ContactType) -> String {
        // Build via URLComponents so reserved characters in the name (`&`, `=`, `+`, `?`) are
        // percent-encoded per query item. String interpolation would let a crafted name inject
        // its own public_key/type and spoof the parsed contact identity.
        var components = URLComponents()
        components.scheme = contactURIScheme
        components.host = contactURIHost
        components.path = contactURIPath
        components.queryItems = [
            URLQueryItem(name: contactURINameKey, value: name),
            URLQueryItem(name: contactURIPublicKeyKey, value: publicKey.uppercaseHexString()),
            URLQueryItem(name: contactURITypeKey, value: String(type.rawValue))
        ]
        return components.url?.absoluteString ?? ""
    }

    /// Import a contact from card data
    /// - Parameter cardData: The contact card data
    public func importContact(cardData: Data) async throws {
        do {
            try await session.importContact(cardData: cardData)
        } catch let error as MeshCoreError {
            throw ContactServiceError.sessionError(error)
        }
    }

    // MARK: - Local Database Operations

    /// Get all contacts for a device from local database
    public func getContacts(radioID: UUID) async throws -> [ContactDTO] {
        try await dataStore.fetchContacts(radioID: radioID)
    }

    /// Get conversations (contacts with messages) from local database
    public func getConversations(radioID: UUID) async throws -> [ContactDTO] {
        try await dataStore.fetchConversations(radioID: radioID)
    }

    /// Get a contact by ID from local database
    public func getContactByID(_ id: UUID) async throws -> ContactDTO? {
        try await dataStore.fetchContact(id: id)
    }

    /// Update local contact preferences (nickname, blocked, favorite)
    public func updateContactPreferences(
        contactID: UUID,
        nickname: String? = nil,
        isBlocked: Bool? = nil,
        isFavorite: Bool? = nil
    ) async throws {
        guard let existing = try await dataStore.fetchContact(id: contactID) else {
            throw ContactServiceError.contactNotFound
        }

        // Check blocking state transitions
        let isBeingBlocked = isBlocked == true && !existing.isBlocked
        let isBeingUnblocked = isBlocked == false && existing.isBlocked

        // Create updated DTO preserving existing values
        let updated = ContactDTO(
            from: Contact(
                id: existing.id,
                radioID: existing.radioID,
                publicKey: existing.publicKey,
                name: existing.name,
                typeRawValue: existing.typeRawValue,
                flags: existing.flags,
                outPathLength: existing.outPathLength,
                outPath: existing.outPath,
                lastAdvertTimestamp: existing.lastAdvertTimestamp,
                latitude: existing.latitude,
                longitude: existing.longitude,
                lastModified: existing.lastModified,
                nickname: nickname ?? existing.nickname,
                isBlocked: isBlocked ?? existing.isBlocked,
                isFavorite: isFavorite ?? existing.isFavorite,
                lastMessageDate: existing.lastMessageDate,
                unreadCount: isBeingBlocked ? 0 : existing.unreadCount,
                ocvPreset: existing.ocvPreset,
                customOCVArrayString: existing.customOCVArrayString
            )
        )

        try await dataStore.saveContact(updated)

        // Trigger cleanup for blocking state changes
        if isBeingBlocked {
            await cleanupHandler?(contactID, .blocked, existing.publicKey)
        } else if isBeingUnblocked {
            await cleanupHandler?(contactID, .unblocked, existing.publicKey)
        }
    }

    /// Updates OCV settings for a contact
    /// - Parameters:
    ///   - contactID: The contact's ID
    ///   - preset: The OCV preset name
    ///   - customArray: Custom OCV array string (for custom preset)
    public func updateContactOCVSettings(
        contactID: UUID,
        preset: String,
        customArray: String?
    ) async throws {
        guard let existing = try await dataStore.fetchContact(id: contactID) else {
            throw ContactServiceError.contactNotFound
        }

        let updated = ContactDTO(
            from: Contact(
                id: existing.id,
                radioID: existing.radioID,
                publicKey: existing.publicKey,
                name: existing.name,
                typeRawValue: existing.typeRawValue,
                flags: existing.flags,
                outPathLength: existing.outPathLength,
                outPath: existing.outPath,
                lastAdvertTimestamp: existing.lastAdvertTimestamp,
                latitude: existing.latitude,
                longitude: existing.longitude,
                lastModified: existing.lastModified,
                nickname: existing.nickname,
                isBlocked: existing.isBlocked,
                isFavorite: existing.isFavorite,
                lastMessageDate: existing.lastMessageDate,
                unreadCount: existing.unreadCount,
                ocvPreset: preset,
                customOCVArrayString: customArray
            )
        )

        try await dataStore.saveContact(updated)
    }

    // MARK: - Device Favorite Sync

    /// Sets a contact's favorite status on the device and updates local storage.
    ///
    /// This method updates the device's contact flags (bit 0 = favorite), waits for
    /// confirmation, then updates the local SwiftData contact.
    ///
    /// - Parameters:
    ///   - contactID: The contact's UUID.
    ///   - isFavorite: Whether to mark the contact as a favorite.
    /// - Throws: `ContactServiceError` if the device update fails.
    public func setContactFavorite(_ contactID: UUID, isFavorite: Bool) async throws {
        guard let existing = try await dataStore.fetchContact(id: contactID) else {
            throw ContactServiceError.contactNotFound
        }

        // Calculate new flags: set or clear bit 0
        let newFlags: UInt8 = isFavorite
            ? existing.flags | 0x01
            : existing.flags & ~0x01

        // Build MeshContact for device update
        let meshContact = MeshContact(
            id: existing.publicKey.uppercaseHexString(),
            publicKey: existing.publicKey,
            type: ContactType(rawValue: existing.typeRawValue) ?? .chat,
            flags: ContactFlags(rawValue: existing.flags),
            outPathLength: existing.outPathLength,
            outPath: existing.outPath,
            advertisedName: existing.name,
            lastAdvertisement: Date(timeIntervalSince1970: TimeInterval(existing.lastAdvertTimestamp)),
            latitude: existing.latitude,
            longitude: existing.longitude,
            lastModified: Date(timeIntervalSince1970: TimeInterval(existing.lastModified))
        )

        // Push to device and wait for confirmation
        do {
            try await session.changeContactFlags(meshContact, flags: ContactFlags(rawValue: newFlags))
        } catch let error as MeshCoreError {
            throw ContactServiceError.sessionError(error)
        }

        // Device confirmed - update local storage
        let updated = ContactDTO(
            from: Contact(
                id: existing.id,
                radioID: existing.radioID,
                publicKey: existing.publicKey,
                name: existing.name,
                typeRawValue: existing.typeRawValue,
                flags: newFlags,
                outPathLength: existing.outPathLength,
                outPath: existing.outPath,
                lastAdvertTimestamp: existing.lastAdvertTimestamp,
                latitude: existing.latitude,
                longitude: existing.longitude,
                lastModified: existing.lastModified,
                nickname: existing.nickname,
                isBlocked: existing.isBlocked,
                isFavorite: isFavorite,
                lastMessageDate: existing.lastMessageDate,
                unreadCount: existing.unreadCount,
                ocvPreset: existing.ocvPreset,
                customOCVArrayString: existing.customOCVArrayString
            )
        )

        try await dataStore.saveContact(updated)
    }

    // MARK: - Telemetry Permissions

    /// Whether a contact has telemetry permission flags set (bits 1-3).
    public static func hasTelemetryPermissions(flags: UInt8) -> Bool {
        (flags & 0x0E) != 0
    }

    /// Sets telemetry permission flags on a contact's device record.
    /// Bits 1-3 of contact.flags control base/location/environment permissions.
    /// Bit 0 (favourite) is preserved.
    ///
    /// - Parameters:
    ///   - contactID: The contact's UUID.
    ///   - granted: Whether to grant telemetry permissions.
    /// - Throws: `ContactServiceError` if the device update fails.
    public func setTelemetryPermissions(_ contactID: UUID, granted: Bool) async throws {
        guard let existing = try await dataStore.fetchContact(id: contactID) else {
            throw ContactServiceError.contactNotFound
        }

        // Set or clear bits 1-3, preserving bit 0 (favourite)
        let newFlags: UInt8 = granted
            ? existing.flags | 0x0E
            : existing.flags & ~0x0E

        // Build MeshContact for device update
        let meshContact = MeshContact(
            id: existing.publicKey.uppercaseHexString(),
            publicKey: existing.publicKey,
            type: ContactType(rawValue: existing.typeRawValue) ?? .chat,
            flags: ContactFlags(rawValue: existing.flags),
            outPathLength: existing.outPathLength,
            outPath: existing.outPath,
            advertisedName: existing.name,
            lastAdvertisement: Date(timeIntervalSince1970: TimeInterval(existing.lastAdvertTimestamp)),
            latitude: existing.latitude,
            longitude: existing.longitude,
            lastModified: Date(timeIntervalSince1970: TimeInterval(existing.lastModified))
        )

        // Push to device and wait for confirmation
        do {
            try await session.changeContactFlags(meshContact, flags: ContactFlags(rawValue: newFlags))
        } catch let error as MeshCoreError {
            throw ContactServiceError.sessionError(error)
        }

        // Device confirmed - update local storage
        let updated = ContactDTO(
            from: Contact(
                id: existing.id,
                radioID: existing.radioID,
                publicKey: existing.publicKey,
                name: existing.name,
                typeRawValue: existing.typeRawValue,
                flags: newFlags,
                outPathLength: existing.outPathLength,
                outPath: existing.outPath,
                lastAdvertTimestamp: existing.lastAdvertTimestamp,
                latitude: existing.latitude,
                longitude: existing.longitude,
                lastModified: existing.lastModified,
                nickname: existing.nickname,
                isBlocked: existing.isBlocked,
                isFavorite: existing.isFavorite,
                lastMessageDate: existing.lastMessageDate,
                unreadCount: existing.unreadCount,
                ocvPreset: existing.ocvPreset,
                customOCVArrayString: existing.customOCVArrayString
            )
        )

        try await dataStore.saveContact(updated)
    }

    // MARK: - Favorites Migration

    private static let favoritesMigrationKey = "hasMigratedContactFavorites"

    /// Migrates existing app favorites to device flags (one-time operation).
    ///
    /// On first run after upgrade, this merges app favorites with device favorites:
    /// any contact that is favorite in either location becomes favorite in both.
    /// After migration, device wins for future syncs.
    ///
    /// - Parameter radioID: The connected device's UUID.
    /// - Returns: Number of contacts migrated to device.
    @discardableResult
    public func migrateAppFavoritesToDevice(radioID: UUID) async throws -> Int {
        // Check if already migrated
        if UserDefaults.standard.bool(forKey: Self.favoritesMigrationKey) {
            return 0
        }

        logger.info("Starting favorites migration to device")

        // Find contacts that are favorite in app but not on device
        let contacts = try await dataStore.fetchContacts(radioID: radioID)
        let toMigrate = contacts.filter { contact in
            contact.isFavorite && (contact.flags & 0x01) == 0
        }

        if toMigrate.isEmpty {
            logger.info("No favorites to migrate, marking complete")
            UserDefaults.standard.set(true, forKey: Self.favoritesMigrationKey)
            return 0
        }

        logger.info("Migrating \(toMigrate.count) favorites to device")

        var migratedCount = 0
        for contact in toMigrate {
            do {
                try await setContactFavorite(contact.id, isFavorite: true)
                migratedCount += 1
            } catch {
                logger.warning("Failed to migrate favorite for \(contact.name): \(error)")
                // Continue with other contacts, will retry on next connect
            }
        }

        // Only mark complete if all were migrated
        if migratedCount == toMigrate.count {
            UserDefaults.standard.set(true, forKey: Self.favoritesMigrationKey)
            logger.info("Favorites migration complete: \(migratedCount) contacts")
        } else {
            logger.warning("Partial migration: \(migratedCount)/\(toMigrate.count), will retry")
        }

        return migratedCount
    }
}

// MARK: - ContactServiceProtocol Conformance

extension ContactService: ContactServiceProtocol {
    // Already implements syncContacts(radioID:since:) -> ContactSyncResult
}

// MARK: - MeshContact Extensions

extension MeshContact {
    /// Converts a MeshContact to a ContactFrame for persistence
    func toContactFrame() -> ContactFrame {
        ContactFrame(
            publicKey: publicKey,
            type: type,
            typeRawValue: typeRawValue,
            flags: flags.rawValue,
            outPathLength: outPathLength,
            outPath: outPath,
            name: advertisedName,
            lastAdvertTimestamp: UInt32(lastAdvertisement.timeIntervalSince1970),
            latitude: latitude,
            longitude: longitude,
            lastModified: UInt32(lastModified.timeIntervalSince1970)
        )
    }
}

// MARK: - ContactFrame Extensions

extension ContactFrame {
    /// Converts a ContactFrame to a MeshContact for session operations
    public func toMeshContact() -> MeshContact {
        MeshContact(
            id: publicKey.uppercaseHexString(),
            publicKey: publicKey,
            type: type,
            typeRawValue: typeRawValue,
            flags: ContactFlags(rawValue: flags),
            outPathLength: outPathLength,
            outPath: outPath,
            advertisedName: name,
            lastAdvertisement: Date(timeIntervalSince1970: TimeInterval(lastAdvertTimestamp)),
            latitude: latitude,
            longitude: longitude,
            lastModified: Date(timeIntervalSince1970: TimeInterval(lastModified))
        )
    }
}
