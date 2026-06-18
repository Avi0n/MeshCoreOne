import Foundation

/// Utilities for the MeshCore contact share token format: `<publicKeyHex:type:name>`.
///
/// - `publicKeyHex` is the 32-byte public key as 64 hex characters (uppercase on emit,
///   case-insensitive on parse).
/// - `type` is a ``ContactType`` raw value (1 chat, 2 repeater, 3 room).
/// - `name` is the node's advertised name. `>` is the reserved terminator and is stripped
///   from the name on emit; the name may legitimately contain `:`.
public enum ContactShareUtilities {
    /// Marks the start of a share token.
    private static let tokenOpen: Character = "<"

    /// Marks the end of a share token and is reserved out of names.
    private static let tokenClose: Character = ">"

    /// Separates the public key, type, and name fields inside a token.
    private static let fieldSeparator: Character = ":"

    /// Splitting the interior keeps the public key and type as their own fields while
    /// letting the name retain any internal `:` characters.
    private static let interiorMaxSplits = 2

    /// Number of fields a well-formed token interior splits into.
    private static let expectedFieldCount = 3

    /// Regex pattern for a contact share token: `<64-hex:digits:name>`.
    public static let shareTokenPattern = #"<[0-9a-fA-F]{64}:\d+:[^>]+>"#

    /// Pre-compiled regex for token matching (avoids recompilation per call).
    public static let shareTokenRegex: NSRegularExpression? = {
        try? NSRegularExpression(pattern: shareTokenPattern)
    }()

    /// Formats a contact into a share token.
    /// - Parameters:
    ///   - publicKey: The contact's 32-byte public key (rendered as uppercase hex).
    ///   - type: The contact type.
    ///   - name: The contact's advertised name; any `>` characters are stripped.
    /// - Returns: A `<publicKeyHex:type:name>` token.
    public static func formatShare(publicKey: Data, type: ContactType, name: String) -> String {
        let sanitizedName = name.filter { $0 != tokenClose }
        return "\(tokenOpen)\(publicKey.uppercaseHexString())\(fieldSeparator)\(type.rawValue)\(fieldSeparator)\(sanitizedName)\(tokenClose)"
    }

    /// Parses the first contact share token found in the input.
    /// - Parameter token: Text that may contain a share token.
    /// - Returns: The recovered ``ContactResult``, or nil if no valid token is present.
    public static func parseShare(_ token: String) -> ContactResult? {
        guard let regex = shareTokenRegex else { return nil }

        let range = NSRange(token.startIndex..., in: token)
        guard let match = regex.firstMatch(in: token, range: range),
              let matchRange = Range(match.range, in: token) else {
            return nil
        }

        return contactResult(fromMatched: token[matchRange])
    }

    /// Extracts every contact share token from the input text.
    /// - Parameter text: Text that may contain share tokens.
    /// - Returns: The recovered contacts in the order they appear.
    public static func extractShares(from text: String) -> [ContactResult] {
        guard let regex = shareTokenRegex else { return [] }

        let range = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: range)

        return matches.compactMap { match in
            guard let matchRange = Range(match.range, in: text) else { return nil }
            return contactResult(fromMatched: text[matchRange])
        }
    }

    /// Validates a regex-matched token and builds a ``ContactResult``.
    /// - Parameter matched: The full matched token including delimiters.
    /// - Returns: The recovered contact, or nil if any field is invalid.
    private static func contactResult(fromMatched matched: Substring) -> ContactResult? {
        let interior = matched.dropFirst().dropLast()
        let fields = interior.split(
            separator: fieldSeparator,
            maxSplits: interiorMaxSplits,
            omittingEmptySubsequences: false
        )
        guard fields.count == expectedFieldCount else { return nil }

        let publicKeyHex = String(fields[0])
        guard let publicKey = Data(hexString: publicKeyHex),
              publicKey.count == ProtocolLimits.publicKeySize else {
            return nil
        }

        guard let typeValue = Int(fields[1]),
              let typeByte = UInt8(exactly: typeValue),
              let contactType = ContactType(rawValue: typeByte) else {
            return nil
        }

        let name = String(fields[2])
        guard !name.isEmpty else { return nil }

        return ContactResult(name: name, publicKey: publicKey, contactType: contactType)
    }
}
