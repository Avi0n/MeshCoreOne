import Foundation

/// Session operations for querying and managing the device's contact list.
public protocol ContactSessionOps: Actor {

    /// Retrieves contacts from the device.
    ///
    /// - Parameter lastModified: An optional date for incremental synchronization.
    /// - Returns: An array of `MeshContact` objects retrieved from the device.
    /// - Throws: `MeshCoreError` if the contact query fails.
    func getContacts(since lastModified: Date?) async throws -> [MeshContact]

    /// Fetches a single contact from the device by public key.
    ///
    /// - Parameter publicKey: The full 32-byte public key of the contact.
    /// - Returns: The contact if found, or `nil` if no contact exists with that key.
    /// - Throws: `MeshCoreError` if the query fails.
    func getContact(publicKey: Data) async throws -> MeshContact?

    /// Adds a contact to the device.
    ///
    /// - Parameter contact: The contact to add to the device's storage.
    /// - Throws: `MeshCoreError` if the contact cannot be added.
    func addContact(_ contact: MeshContact) async throws

    /// Removes a contact from the device.
    ///
    /// - Parameter publicKey: The contact's public key to remove.
    /// - Throws: `MeshCoreError` if the contact cannot be removed.
    func removeContact(publicKey: Data) async throws

    /// Resets the path to a contact.
    ///
    /// Triggers path re-discovery for the specified contact by clearing existing routing info.
    ///
    /// - Parameter publicKey: The contact's public key.
    /// - Throws: `MeshCoreError` if the path reset command fails.
    func resetPath(publicKey: Data) async throws

    /// Sends a path discovery request to a contact.
    ///
    /// - Parameter destination: The contact's public key.
    /// - Returns: A `MessageSentInfo` object containing information about the discovery request.
    /// - Throws: `MeshCoreError` if the discovery request fails.
    func sendPathDiscovery(to destination: Data) async throws -> MessageSentInfo

    /// Shares a contact via zero-hop broadcast.
    ///
    /// - Parameter publicKey: The contact's 32-byte public key.
    /// - Throws: `MeshCoreError` if the share fails.
    func shareContact(publicKey: Data) async throws

    /// Exports a contact to a shareable URI.
    ///
    /// - Parameter publicKey: The contact's public key (nil for self).
    /// - Returns: The contact URI string.
    /// - Throws: `MeshCoreError` if the export fails.
    func exportContact(publicKey: Data?) async throws -> String

    /// Imports a contact from card data.
    ///
    /// - Parameter cardData: The contact card data.
    /// - Throws: `MeshCoreError` if the import fails.
    func importContact(cardData: Data) async throws

    /// Updates a contact's flags on the device.
    ///
    /// Use this to modify contact flags (e.g., favorite bit) while preserving other contact data.
    ///
    /// - Parameters:
    ///   - contact: The contact to update.
    ///   - flags: The new flags value.
    /// - Throws: `MeshCoreError` if the update fails.
    func changeContactFlags(_ contact: MeshContact, flags: ContactFlags) async throws
}
