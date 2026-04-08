import Foundation
import MC1Services

/// Parses meshcore:// deep link URLs for channel and contact imports.
enum MeshCoreURLParser {

    static let scheme = "meshcore"

    /// Parsed channel data from a meshcore://channel/add URL
    struct ChannelResult: Identifiable {
        let name: String
        let secret: Data

        var id: String { secret.hexString() }
    }

    /// Parsed contact data from a meshcore://contact/add URL
    struct ContactResult: Identifiable {
        let name: String
        let publicKey: Data
        let contactType: ContactType

        var id: String { publicKey.hexString() }
    }

    /// Parses a meshcore://channel/add URL string.
    /// Returns nil if the string is not a valid channel URL.
    static func parseChannelURL(_ string: String) -> ChannelResult? {
        guard let url = URL(string: string),
              url.scheme == "meshcore",
              url.host() == "channel",
              url.path() == "/add",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            return nil
        }

        let name = queryItems.first(where: { $0.name == "name" })?.value ?? ""
        let secretHex = queryItems.first(where: { $0.name == "secret" })?.value ?? ""

        guard !name.isEmpty,
              let secretData = Data(hexString: secretHex),
              secretData.count == 16 else {
            return nil
        }

        return ChannelResult(name: name, secret: secretData)
    }

    /// Parses a meshcore://contact/add URL string.
    /// Returns nil if the string is not a valid contact URL.
    static func parseContactURL(_ string: String) -> ContactResult? {
        guard let url = URL(string: string),
              url.scheme == "meshcore",
              url.host() == "contact",
              url.path() == "/add",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            return nil
        }

        // URLQueryItem decodes %20 but not + (form-urlencoded spaces)
        let rawName = queryItems.first(where: { $0.name == "name" })?.value ?? ""
        let name = rawName.replacing("+", with: " ")
        let publicKeyHex = queryItems.first(where: { $0.name == "public_key" })?.value ?? ""

        guard !name.isEmpty,
              let keyData = Data(hexString: publicKeyHex),
              keyData.count == ProtocolLimits.publicKeySize else {
            return nil
        }

        let typeValue = queryItems.first(where: { $0.name == "type" })?.value.flatMap { Int($0) } ?? 1
        let contactType = ContactType(rawValue: UInt8(typeValue)) ?? .chat

        return ContactResult(name: name, publicKey: keyData, contactType: contactType)
    }
}
