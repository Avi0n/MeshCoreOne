import Foundation
import os

/// Manages contact storage, caching, and lifecycle.
///
/// This is a non-Sendable struct designed to be owned by `MeshCoreSession`.
/// All access is synchronous within the session's actor isolation domain.
struct ContactManager {
    private let logger = Logger(subsystem: "MeshCore", category: "ContactManager")

    // MARK: - State

    private var contacts: [String: MeshContact] = [:]
    private var pendingContacts: [String: MeshContact] = [:]
    private var lastModified: Date?
    private var isDirty = true
    private var autoUpdate = false

    // MARK: - Public Properties

    /// Retrieves all currently cached contacts.
    var cachedContacts: [MeshContact] {
        Array(contacts.values)
    }

    /// Retrieves all pending contacts awaiting confirmation.
    var cachedPendingContacts: [MeshContact] {
        Array(pendingContacts.values)
    }

    /// Indicates whether the contact cache needs to be refreshed from the device.
    var needsRefresh: Bool {
        isDirty
    }

    /// Retrieves the last modified date of the contacts as reported by the device.
    var contactsLastModified: Date? {
        lastModified
    }

    /// Indicates whether the contact cache is empty.
    var isEmpty: Bool {
        contacts.isEmpty
    }

    // MARK: - Lookup Methods

    /// Finds a contact by their advertised name.
    ///
    /// - Parameters:
    ///   - name: The name to search for.
    ///   - exactMatch: If true, requires an exact case-insensitive match; otherwise, uses localized search.
    /// - Returns: The matching `MeshContact`, or `nil` if not found.
    func getByName(_ name: String, exactMatch: Bool = false) -> MeshContact? {
        if exactMatch {
            return contacts.values.first { $0.advertisedName.lowercased() == name.lowercased() }
        }
        return contacts.values.first { $0.advertisedName.localizedStandardContains(name) }
    }

    /// Finds a contact by their public key prefix (hex string).
    ///
    /// - Parameter prefix: The hex string prefix of the public key.
    /// - Returns: The matching `MeshContact`, or `nil` if not found.
    func getByKeyPrefix(_ prefix: String) -> MeshContact? {
        let normalizedPrefix = prefix.lowercased()
        return contacts.values.first { $0.publicKey.hexString.lowercased().hasPrefix(normalizedPrefix) }
    }

    /// Finds a contact by their public key prefix (Data).
    ///
    /// - Parameter prefix: The raw data prefix of the public key.
    /// - Returns: The matching `MeshContact`, or `nil` if not found.
    func getByKeyPrefix(_ prefix: Data) -> MeshContact? {
        contacts.values.first { $0.publicKey.prefix(prefix.count) == prefix }
    }

    /// Finds a contact by their full public key.
    ///
    /// - Parameter key: The full public key data.
    /// - Returns: The matching `MeshContact`, or `nil` if not found.
    func getByPublicKey(_ key: Data) -> MeshContact? {
        contacts[key.hexString]
    }

    // MARK: - Cache Management

    /// Stores a single contact in the cache.
    ///
    /// - Parameter contact: The contact to store.
    mutating func store(_ contact: MeshContact) {
        contacts[contact.id] = contact
    }

    /// Updates the contact cache with new contacts and a modification date.
    ///
    /// - Parameters:
    ///   - newContacts: An array of contacts to add or update in the cache.
    ///   - lastModified: The date these contacts were fetched.
    mutating func updateCache(_ newContacts: [MeshContact], lastModified: Date) {
        for contact in newContacts {
            contacts[contact.id] = contact
        }
        self.lastModified = lastModified
        isDirty = false
        logger.debug("Updated cache with \(newContacts.count) contacts")
    }

    /// Marks the cache as clean (synchronized with the device).
    ///
    /// - Parameter lastModified: The date of the last synchronization.
    mutating func markClean(lastModified: Date) {
        self.lastModified = lastModified
        isDirty = false
    }

    /// Marks the cache as needing a refresh from the device.
    mutating func markDirty() {
        isDirty = true
    }

    /// Adds a contact to the pending contacts list.
    ///
    /// - Parameter contact: The contact to add to the pending list.
    mutating func addPending(_ contact: MeshContact) {
        pendingContacts[contact.id] = contact
    }

    /// Removes and returns a pending contact by their public key hex string.
    ///
    /// - Parameter publicKey: The hex string of the public key.
    /// - Returns: The removed `MeshContact`, or `nil` if not found.
    mutating func popPending(publicKey: String) -> MeshContact? {
        pendingContacts.removeValue(forKey: publicKey)
    }

    /// Removes all contacts from the pending list.
    mutating func flushPending() {
        pendingContacts.removeAll()
    }

    /// Removes a contact from both the active and pending caches.
    ///
    /// - Parameter contactId: The identifier (hex string) of the contact to remove.
    mutating func remove(_ contactId: String) {
        contacts.removeValue(forKey: contactId)
        pendingContacts.removeValue(forKey: contactId)
        isDirty = true
    }

    /// Clears all cached contact data and marks the cache as dirty.
    mutating func clear() {
        contacts.removeAll()
        pendingContacts.removeAll()
        lastModified = nil
        isDirty = true
    }

    // MARK: - Auto-Update

    /// Indicates whether automatic contact updates are enabled.
    var isAutoUpdateEnabled: Bool {
        autoUpdate
    }

    /// Enables or disables automatic contact updates based on device events.
    ///
    /// - Parameter enabled: `true` to enable automatic updates.
    mutating func setAutoUpdate(_ enabled: Bool) {
        autoUpdate = enabled
    }

    /// Tracks contact changes based on events received from the device.
    ///
    /// - Parameter event: The `MeshEvent` to process for contact changes.
    mutating func trackChanges(from event: MeshEvent) {
        switch event {
        case .contact(let contact):
            contacts[contact.id] = contact
        case .newContact(let contact):
            addPending(contact)
            isDirty = true
        case .contactsEnd(let modifiedDate):
            lastModified = modifiedDate
            isDirty = false
        case .advertisement, .pathUpdate:
            isDirty = true
        case .contactDeleted(let publicKey):
            let contactId = publicKey.hexString
            let keyPrefix = publicKey.prefix(6).hexString
            if let cached = contacts[contactId] {
                logger.info(
                    """
                    Overwrite oldest: removing '\(cached.advertisedName, privacy: .public)' \
                    (\(keyPrefix, privacy: .public)...) from contacts cache \
                    (type=\(cached.type.rawValue), \
                    lastAdvert=\(cached.lastAdvertisement.description, privacy: .public), \
                    lastModified=\(cached.lastModified.description, privacy: .public))
                    """
                )
            } else if pendingContacts[contactId] != nil {
                logger.info("Overwrite oldest: removing \(keyPrefix, privacy: .public)... from pending contacts cache")
            } else {
                logger.info("Overwrite oldest: contact \(keyPrefix, privacy: .public)... not found in cache (already removed or never synced)")
            }
            contacts.removeValue(forKey: contactId)
            pendingContacts.removeValue(forKey: contactId)
            isDirty = true
        case .contactsFull:
            isDirty = true
        default:
            break
        }
    }
}
