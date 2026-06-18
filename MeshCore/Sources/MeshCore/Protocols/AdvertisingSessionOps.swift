import Foundation

/// Session operations for broadcasting self-advertisements and refreshing
/// advertised identity data.
public protocol AdvertisingSessionOps: Actor {

    /// Sends an advertisement broadcast.
    ///
    /// - Parameter flood: If `true`, the advertisement is broadcast using flood routing.
    /// - Throws: `MeshCoreError` on timeout or device error.
    func sendAdvertisement(flood: Bool) async throws

    /// Sets the device's advertised name.
    ///
    /// - Parameter name: The name to advertise (max 32 bytes UTF-8).
    /// - Throws: `MeshCoreError` on timeout or device error.
    func setName(_ name: String) async throws

    /// Sets the device's GPS coordinates.
    ///
    /// - Parameters:
    ///   - latitude: Latitude in degrees (-90 to 90).
    ///   - longitude: Longitude in degrees (-180 to 180).
    /// - Throws: `MeshCoreError` on timeout or device error.
    func setCoordinates(latitude: Double, longitude: Double) async throws

    /// Fetches a single contact from the device by public key.
    ///
    /// - Parameter publicKey: The full 32-byte public key of the contact.
    /// - Returns: The contact if found, or `nil` if no contact exists with that key.
    /// - Throws: `MeshCoreError` if the query fails.
    func getContact(publicKey: Data) async throws -> MeshContact?
}
