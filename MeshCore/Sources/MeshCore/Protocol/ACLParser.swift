import Foundation

// MARK: - ACL Parser

/// Specialized parser for Access Control List data.
enum ACLParser {
    /// Parses ACL entries from binary protocol data.
    ///
    /// - Parameter data: Raw ACL data.
    /// - Returns: An array of ``ACLEntry`` structs.
    ///
    /// ### Binary Format
    /// (Per entry): `[pubkey_prefix:6][permissions:1]` (7 bytes total)
    static func parse(_ data: Data) -> [ACLEntry] {
        var entries: [ACLEntry] = []
        var offset = 0

        while offset + 7 <= data.count {
            let keyPrefix = Data(data[offset..<offset+6])
            let permissions = data[offset + 6]
            offset += 7

            // Skip null entries (all zeros)
            if keyPrefix.allSatisfy({ $0 == 0 }) {
                continue
            }

            entries.append(ACLEntry(keyPrefix: keyPrefix, permissions: permissions))
        }

        return entries
    }
}
