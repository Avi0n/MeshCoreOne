import Foundation

/// Parsed contact identity recovered from a deep-link URL or a contact share token.
public struct ContactResult: Identifiable, Sendable {
    public let name: String
    public let publicKey: Data
    public let contactType: ContactType
    public var id: String { publicKey.hexString() }

    public init(name: String, publicKey: Data, contactType: ContactType) {
        self.name = name
        self.publicKey = publicKey
        self.contactType = contactType
    }
}
