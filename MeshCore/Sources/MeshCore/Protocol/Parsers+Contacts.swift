import Foundation

extension Parsers {

    // MARK: - Contact Parsing Helper

    /// Parses a 147-byte contact structure into a MeshContact.
    ///
    /// ### Binary Format
    /// (Per Python reader.py)
    /// - Offset 0 (32 bytes): Public Key
    /// - Offset 32 (1 byte): Contact Type
    /// - Offset 33 (1 byte): Flags
    /// - Offset 34 (1 byte): Path Length (encoded: upper 2 bits = hash mode, lower 6 bits = hop count; 0xFF = flood)
    /// - Offset 35 (64 bytes): Routing Path
    /// - Offset 99 (32 bytes): Advertised Name (UTF-8, padded)
    /// - Offset 131 (4 bytes): Last Advertisement Time (UInt32 LE)
    /// - Offset 135 (4 bytes): Latitude scaled by 1e6 (Int32 LE)
    /// - Offset 139 (4 bytes): Longitude scaled by 1e6 (Int32 LE)
    /// - Offset 143 (4 bytes): Last Modified Time (UInt32 LE)
    static func parseContactData(_ data: Data) -> MeshContact? {
        guard data.count >= PacketSize.contact else { return nil }

        var offset = 0
        let publicKey = Data(data[offset..<offset+32]); offset += 32
        let typeByte = data[offset]
        let type = ContactType(rawValue: typeByte) ?? .chat; offset += 1
        let flags = ContactFlags(rawValue: data[offset]); offset += 1
        let pathLen = data[offset]; offset += 1
        guard pathLen == 0xFF || decodePathLen(pathLen) != nil else { return nil }
        let actualPathLen = (pathLen == 0xFF) ? 0 : (decodePathLen(pathLen)?.byteLength ?? 0)
        // Read full 64-byte path field, but only use first actualPathLen bytes
        let pathBytes = Data(data[offset..<offset+64])
        let path = actualPathLen > 0 ? Data(pathBytes.prefix(actualPathLen)) : Data()
        offset += 64
        let nameData = data[offset..<offset+32]
        let name = String(data: nameData, encoding: .utf8)?
            .trimmingCharacters(in: .controlCharacters) ?? ""
        offset += 32
        let lastAdvert = Date(timeIntervalSince1970: TimeInterval(data.readUInt32LE(at: offset))); offset += 4
        let lat = Double(data.readInt32LE(at: offset)) / 1_000_000; offset += 4
        let lon = Double(data.readInt32LE(at: offset)) / 1_000_000; offset += 4
        let lastMod = Date(timeIntervalSince1970: TimeInterval(data.readUInt32LE(at: offset)))

        return MeshContact(
            id: publicKey.hexString,
            publicKey: publicKey,
            type: type,
            typeRawValue: typeByte,
            flags: flags,
            outPathLength: pathLen,
            outPath: path,
            advertisedName: name,
            lastAdvertisement: lastAdvert,
            latitude: lat,
            longitude: lon,
            lastModified: lastMod
        )
    }

    // MARK: - Contact

    /// Parser for mesh contact structures.
    enum Contact {
        /// Parses a 147-byte contact structure.
        ///
        /// - Parameter data: Raw contact data.
        /// - Returns: A `.contact` event or `.parseFailure`.
        static func parse(_ data: Data) -> MeshEvent {
            if data.count >= PacketSize.contact {
                let pathLen = data[34]
                if pathLen != 0xFF && decodePathLen(pathLen) == nil {
                    return .parseFailure(
                        data: data,
                        reason: "Contact response uses reserved path length encoding: 0x\(String(format: "%02X", pathLen))"
                    )
                }
            }
            guard let contact = parseContactData(data) else {
                return .parseFailure(
                    data: data,
                    reason: "Contact response too short: \(data.count) < \(PacketSize.contact)"
                )
            }
            return .contact(contact)
        }
    }

    // MARK: - Advertisement

    /// Parser for node advertisement (beacon) data.
    enum Advertisement {
        /// Parses a 32-byte public key advertisement.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketBuilder.publicKeySize else {
                return .parseFailure(data: data, reason: "Advertisement too short: \(data.count) < \(PacketBuilder.publicKeySize)")
            }
            let publicKey = Data(data.prefix(PacketBuilder.publicKeySize))
            return .advertisement(publicKey: publicKey)
        }
    }

    // MARK: - NewAdvertisement

    /// Parser for advertisements from previously unknown nodes (manual-add mode).
    enum NewAdvertisement {
        /// Parses a new node advertisement and returns `.newContact` event.
        ///
        /// This is sent by the device when `manualAddContacts` is enabled and a new
        /// advertisement is received. Unlike `.advertisement` which only contains
        /// a public key prefix, this contains full contact data.
        static func parse(_ data: Data) -> MeshEvent {
            if let contact = parseContactData(data) {
                return .newContact(contact)
            } else if data.count >= PacketBuilder.publicKeySize {
                // Fallback: insufficient data for full contact, but we have public key
                return .parseFailure(
                    data: data,
                    reason: "NewAdvertisement has public key but insufficient contact data: \(data.count) < \(PacketSize.contact)"
                )
            }
            return .parseFailure(data: data, reason: "NewAdvertisement too short: \(data.count)")
        }
    }

    // MARK: - PathUpdate

    /// Parser for routing path update notifications.
    enum PathUpdate {
        /// Parses a 32-byte public key path update.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketBuilder.publicKeySize else {
                return .parseFailure(data: data, reason: "PathUpdate too short: \(data.count) < \(PacketBuilder.publicKeySize)")
            }
            let publicKey = Data(data.prefix(PacketBuilder.publicKeySize))
            return .pathUpdate(publicKey: publicKey)
        }
    }

    // MARK: - ContactDeleted

    /// Parser for contact deletion notifications.
    enum ContactDeleted {
        /// Parses a contact deletion notification containing the 32-byte public key.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.contactDeletedPublicKey else {
                return .parseFailure(
                    data: data,
                    reason: "ContactDeleted too short: \(data.count) < \(PacketSize.contactDeletedPublicKey)"
                )
            }
            let publicKey = Data(data.prefix(PacketSize.contactDeletedPublicKey))
            return .contactDeleted(publicKey: publicKey)
        }
    }

    // MARK: - ContactsFull

    /// Parser for contacts full notifications.
    enum ContactsFull {
        /// Parses a contacts full notification (no payload required).
        static func parse(_ data: Data) -> MeshEvent {
            return .contactsFull
        }
    }
}
