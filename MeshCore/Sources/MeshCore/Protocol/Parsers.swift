import Foundation
import os

// MARK: - Packet Size Constants

/// Named constants for packet size validation to avoid magic numbers.
enum PacketSize {
    /// Full contact structure size.
    static let contact = 147
    /// Minimum size for self info response.
    static let selfInfoMinimum = 57
    /// Minimum size for message sent confirmation.
    static let messageSentMinimum = 9
    /// Minimum size for version 1 contact messages.
    static let contactMessageV1Minimum = 12
    /// Minimum size for version 3 contact messages.
    static let contactMessageV3Minimum = 15
    /// Minimum size for version 1 channel messages.
    static let channelMessageV1Minimum = 7
    /// Minimum size for version 3 channel messages.
    static let channelMessageV3Minimum = 10
    /// Minimum size for private key export.
    static let privateKeyMinimum = 64
    /// Minimum size for basic battery info.
    static let batteryMinimum = 2
    /// Size for battery info with storage statistics.
    static let batteryExtended = 10
    /// Minimum size for signing session start.
    static let signStartMinimum = 5
    /// Full size for version 3 device info.
    static let deviceInfoV3Full = 79
    /// Minimum size for acknowledgement packets.
    static let ackMinimum = 4
    /// Size for acknowledgement packets that include firmware trip time.
    static let ackWithTripTime = 8
    /// Minimum size for contact synchronization start.
    static let contactsStartMinimum = 4
    /// Minimum size for core system statistics.
    static let coreStatsMinimum = 9
    /// Minimum size for radio statistics.
    static let radioStatsMinimum = 12
    /// Minimum size for packet counters.
    static let packetStatsMinimum = 24
    /// Size for packet counters with receive errors field.
    static let packetStatsWithReceiveErrors = 28
    /// Minimum size for channel configuration info.
    static let channelInfoMinimum = 49
    /// Size of the public key in contact deleted notifications.
    static let contactDeletedPublicKey = 32
    /// Minimum size for status response push notification.
    /// Format: `reserved(1) + pubkey(6) + fields(51) = 58 bytes`
    static let statusResponseMinimum = 58
    /// Minimum size for trace route data.
    static let traceDataMinimum = 11
    /// Minimum size for raw packet data.
    /// Format: `[snr:1][rssi:1][reserved:1]`
    static let rawDataMinimum = 3
    /// Minimum size for control protocol data.
    static let controlDataMinimum = 4
    /// Minimum size for path discovery results.
    /// Format: `reserved(1) + pubkey(6) + out_path_len(1) + in_path_len(1) = 9 bytes`
    static let pathDiscoveryMinimum = 9
    /// Minimum size for login success response (legacy format).
    /// Format: `[adminIndicator:1][pubkeyPrefix:6]` (companion radio hardcodes `0` for
    /// legacy "OK" replies).
    static let loginSuccessMinimum = 7
    /// Size for v7+ login success with ACL permissions.
    /// Format: `[adminIndicator:1][pubkeyPrefix:6][timestamp:4][aclPermissions:1][fwVersion:1]`
    /// where `adminIndicator == 1` means admin; any other value is non-admin.
    static let loginSuccessExtended = 13
    /// Size for binary response status payload without rxAirtime field (48 bytes).
    static let binaryResponseStatusBase = 48
    /// Minimum size for binary response status payload with rxAirtime field (52 bytes).
    static let binaryResponseStatusWithRxAirtime = 52
    /// Minimum size for binary response status payload with receiveErrors field (56 bytes).
    static let binaryResponseStatusWithReceiveErrors = 56
    /// Minimum size for channel datagram payload (header fields before data).
    /// Format: `[snr:1][rsv:2][channel:1][path_len:1][data_type:2][data_len:1]` = 8 bytes.
    static let channelDatagramMinimum = 8
    /// Default-scope name-field width on the wire (zero-padded, null-terminated).
    static let defaultFloodScopeNameField = 31
    /// Default-scope key width on the wire.
    static let defaultFloodScopeKeyBytes = 16
    /// Size for populated default flood scope response (name field + key).
    static let defaultFloodScopeSet = defaultFloodScopeNameField + defaultFloodScopeKeyBytes
}

// MARK: - Parser Logger

private let parserLogger = Logger(subsystem: "MeshCore", category: "Parsers")

// MARK: - Path Encoding Utilities

/// Limits for the multibyte path-length encoding, shared by ``encodePathLen(hashSize:hopCount:)``,
/// `setPathHashMode`, and the config-import path validator so the valid ranges live in one place.
public enum PathEncoding {
    /// Highest valid path hash-size mode (0 = 1-byte, 1 = 2-byte, 2 = 3-byte hashes; mode 3 is reserved).
    public static let maxPathHashMode = 2
    /// Highest hop count representable in the encoded path-length byte's lower 6 bits.
    public static let maxHopCount = 63
    /// Maximum encoded out-path length in bytes (firmware `MAX_PATH_SIZE`); firmware
    /// `isValidPathLen` rejects `hash_count * hash_size` beyond this.
    public static let maxPathBytes = 64
}

/// Decoded components of a multibyte-encoded path length byte.
public struct PathLenDecoded: Sendable {
    /// Bytes per hop hash (1, 2, or 3).
    public let hashSize: Int
    /// Number of hops in the path (0-63).
    public let hopCount: Int
    /// Total path byte length (hashSize * hopCount).
    public let byteLength: Int
}

/// Decodes a multibyte-encoded path length byte.
///
/// The encoding packs two fields into a single byte:
/// - Bits 7-6: hash size mode (0=1-byte, 1=2-byte, 2=3-byte, 3=reserved)
/// - Bits 5-0: hop count (0-63)
///
/// - Parameter encoded: The raw path length byte from the wire.
/// - Returns: Decoded components, or `nil` if mode 3 (reserved).
public func decodePathLen(_ encoded: UInt8) -> PathLenDecoded? {
    let mode = encoded >> 6
    guard mode < 3 else { return nil }  // mode 3 is reserved
    let hashSize = Int(mode) + 1
    let hopCount = Int(encoded & 63)
    return PathLenDecoded(hashSize: hashSize, hopCount: hopCount, byteLength: hashSize * hopCount)
}

/// Encodes hash size and hop count into a single path length byte.
///
/// This is the inverse of ``decodePathLen(_:)``.
///
/// - Parameters:
///   - hashSize: Bytes per hop (1, 2, or 3).
///   - hopCount: Number of hops (0-63).
/// - Returns: The encoded path length byte.
public func encodePathLen(hashSize: Int, hopCount: Int) -> UInt8 {
    precondition(1...3 ~= hashSize, "hashSize must be 1, 2, or 3")
    let mode = UInt8(hashSize - 1)
    let hops = UInt8(min(hopCount, PathEncoding.maxHopCount))
    return (mode << 6) | hops
}

/// Namespace for complex protocol parsers.
///
/// This enum contains specialized parsers for various mesh protocol data structures.
/// Each sub-parser is responsible for validating the input data size and correctly
/// interpreting multi-byte fields (mostly little-endian).
public enum Parsers {

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

    // MARK: - SelfInfo

    /// Parser for local device configuration info.
    enum SelfInfo {
        /// Parses self info response (57+ bytes).
        ///
        /// - Parameter data: Raw self info data.
        /// - Returns: A `.selfInfo` event or `.parseFailure`.
        ///
        /// ### Binary Format
        /// - Offset 0-2 (3 bytes): Adv type, Tx power, Max Tx power
        /// - Offset 3 (32 bytes): Public Key
        /// - Offset 35 (8 bytes): Lat/Lon scaled by 1e6 (Int32 LE)
        /// - Offset 43-45 (3 bytes): Multi-ACKs, Adv policy, Telemetry mode
        /// - Offset 46 (1 byte): Manual add contacts flag
        /// - Offset 47 (8 bytes): Radio Freq/BW scaled by 1000 (UInt32 LE)
        /// - Offset 55-56 (2 bytes): Spreading factor, Coding rate
        /// - Offset 57+ (N bytes): Local name (UTF-8)
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.selfInfoMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "SelfInfo response too short: \(data.count) < \(PacketSize.selfInfoMinimum)"
                )
            }

            var offset = 0
            let advType = data[offset]; offset += 1
            let txPower = Int8(bitPattern: data[offset]); offset += 1
            let maxTxPower = Int8(bitPattern: data[offset]); offset += 1
            let publicKey = Data(data[offset..<offset+32]); offset += 32
            let lat = Double(data.readInt32LE(at: offset)) / 1_000_000; offset += 4
            let lon = Double(data.readInt32LE(at: offset)) / 1_000_000; offset += 4
            let multiAcks = data[offset]; offset += 1
            let advLocPolicy = data[offset]; offset += 1
            let telemetryMode = data[offset]; offset += 1
            let manualAdd = data[offset] > 0; offset += 1
            let radioFreq = Double(data.readUInt32LE(at: offset)) / 1000; offset += 4
            let radioBW = Double(data.readUInt32LE(at: offset)) / 1000; offset += 4
            let radioSF = data[offset]; offset += 1
            let radioCR = data[offset]; offset += 1
            let name = String(data: data[offset...], encoding: .utf8)?
                .trimmingCharacters(in: .controlCharacters) ?? ""

            let info = MeshCore.SelfInfo(
                advertisementType: advType,
                txPower: txPower,
                maxTxPower: maxTxPower,
                publicKey: publicKey,
                latitude: lat,
                longitude: lon,
                multiAcks: multiAcks,
                advertisementLocationPolicy: advLocPolicy,
                telemetryModeEnvironment: (telemetryMode >> 4) & 0b11,
                telemetryModeLocation: (telemetryMode >> 2) & 0b11,
                telemetryModeBase: telemetryMode & 0b11,
                manualAddContacts: manualAdd,
                radioFrequency: radioFreq,
                radioBandwidth: radioBW,
                radioSpreadingFactor: radioSF,
                radioCodingRate: radioCR,
                name: name
            )
            return .selfInfo(info)
        }
    }

    // MARK: - DeviceInfo

    /// Parser for device capabilities and versioning.
    enum DeviceInfo {
        /// Parses device info with version-specific handling.
        ///
        /// - Parameter data: Raw device info data.
        /// - Returns: A `.deviceInfo` event.
        ///
        /// ### Binary Format (v3+)
        /// - Offset 0 (1 byte): Firmware version
        /// - Offset 1 (1 byte): Max contacts (stored as count/2)
        /// - Offset 2 (1 byte): Max channels
        /// - Offset 3 (4 bytes): BLE PIN (UInt32 LE)
        /// - Offset 7 (12 bytes): Firmware build string (UTF-8)
        /// - Offset 19 (40 bytes): Model string (UTF-8)
        /// - Offset 59 (20 bytes): Hardware version string (UTF-8)
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= 1 else {
                return .parseFailure(data: data, reason: "DeviceInfo response empty")
            }

            let fwVer = data[0]
            var offset = 1
            var maxContacts: Int?
            var maxChannels: Int?
            var blePin: UInt32?
            var fwBuild: String?
            var model: String?
            var version: String?

            if fwVer >= 3 && data.count < PacketSize.deviceInfoV3Full {
                return .parseFailure(
                    data: data,
                    reason: "DeviceInfo v\(fwVer) response too short: \(data.count) < \(PacketSize.deviceInfoV3Full)"
                )
            }

            // v3+ format: fwBuild=12, model=40, version=20 bytes
            if fwVer >= 3 && data.count >= PacketSize.deviceInfoV3Full {
                maxContacts = Int(data[offset]) * 2  /// Stored as count/2 in firmware.
                offset += 1
                maxChannels = Int(data[offset])
                offset += 1
                blePin = data.readUInt32LE(at: offset)
                offset += 4
                let fwBuildData = data[offset..<offset+12]
                fwBuild = String(data: fwBuildData, encoding: .utf8)?
                    .trimmingCharacters(in: .controlCharacters)
                offset += 12
                let modelData = data[offset..<offset+40]
                model = String(data: modelData, encoding: .utf8)?
                    .trimmingCharacters(in: .controlCharacters)
                offset += 40
                let versionData = data[offset..<offset+20]
                version = String(data: versionData, encoding: .utf8)?
                    .trimmingCharacters(in: .controlCharacters)
                offset += 20
            }

            // v9+: client_repeat byte after version string (tolerant — defaults to false if missing)
            var clientRepeat = false
            if fwVer >= 9 && offset >= PacketSize.deviceInfoV3Full && data.count > offset {
                clientRepeat = data[offset] != 0
                offset += 1
            }

            // v10+: path_hash_mode byte after client_repeat (tolerant — defaults to 0 if missing)
            var pathHashMode: UInt8 = 0
            if fwVer >= 10 && offset >= PacketSize.deviceInfoV3Full && data.count > offset {
                pathHashMode = data[offset]
            }

            return .deviceInfo(DeviceCapabilities(
                firmwareVersion: fwVer,
                maxContacts: maxContacts ?? 0,
                maxChannels: maxChannels ?? 0,
                blePin: blePin ?? 0,
                firmwareBuild: fwBuild ?? "",
                model: model ?? "",
                version: version ?? "",
                clientRepeat: clientRepeat,
                pathHashMode: pathHashMode
            ))
        }
    }

    // MARK: - ContactMessage

    /// Parser for incoming direct messages.
    enum ContactMessage {
        /// Supported protocol versions for message parsing.
        enum Version { case v1, v3 }

        /// Parses a contact message.
        ///
        /// - Parameters:
        ///   - data: Raw message data.
        ///   - version: Protocol version (v1 or v3).
        /// - Returns: A `.contactMessageReceived` event or `.parseFailure`.
        ///
        /// ### Binary Format (v3)
        /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
        /// - Offset 1 (2 bytes): Reserved
        /// - Offset 3 (6 bytes): Sender Public Key Prefix
        /// - Offset 9 (1 byte): Path Length
        /// - Offset 10 (1 byte): Text Type
        /// - Offset 11 (4 bytes): Sender Timestamp (UInt32 LE)
        /// - Offset 15+ (N bytes): Message payload (UTF-8)
        static func parse(_ data: Data, version: Version) -> MeshEvent {
            var offset = 0
            var snr: Double?

            let minSize = version == .v3 ? PacketSize.contactMessageV3Minimum : PacketSize.contactMessageV1Minimum
            guard data.count >= minSize else {
                return .parseFailure(
                    data: data,
                    reason: "ContactMessage response too short: \(data.count) < \(minSize)"
                )
            }

            if version == .v3 {
                snr = Double(Int8(bitPattern: data[offset])) / 4.0
                offset += 1
                offset += 2 // reserved
            }

            let pubkeyPrefix = Data(data[offset..<offset+6]); offset += 6
            let pathLen = data[offset]; offset += 1
            let txtType = data[offset]; offset += 1
            let timestamp = Date(timeIntervalSince1970: TimeInterval(data.readUInt32LE(at: offset))); offset += 4

            var signature: Data?
            if txtType == 2 {
                guard data.count >= offset + 4 else {
                    return .parseFailure(
                        data: data,
                        reason: "ContactMessage signature truncated: \(data.count) < \(offset + 4)"
                    )
                }
                signature = Data(data[offset..<offset+4]); offset += 4
            }

            // Handle UTF-8 decoding with explicit failure logging
            let textData = Data(data[offset...])
            let text: String
            if let decoded = String(data: textData, encoding: .utf8) {
                text = decoded
            } else {
                parserLogger.warning("ContactMessage: Invalid UTF-8 in message payload, using lossy conversion")
                text = String(decoding: textData, as: UTF8.self)  // Replaces invalid sequences with replacement char
            }

            return .contactMessageReceived(MeshCore.ContactMessage(
                senderPublicKeyPrefix: pubkeyPrefix,
                pathLength: pathLen,
                textType: txtType,
                senderTimestamp: timestamp,
                signature: signature,
                text: text,
                snr: snr
            ))
        }
    }

    // MARK: - ChannelMessage

    /// Parser for incoming channel (broadcast) messages.
    enum ChannelMessage {
        /// Supported protocol versions for message parsing.
        enum Version { case v1, v3 }

        /// Parses a channel message.
        ///
        /// - Parameters:
        ///   - data: Raw message data.
        ///   - version: Protocol version.
        /// - Returns: A `.channelMessageReceived` event or `.parseFailure`.
        ///
        /// ### Binary Format (v3)
        /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
        /// - Offset 1 (2 bytes): Reserved
        /// - Offset 3 (1 byte): Channel Index
        /// - Offset 4 (1 byte): Path Length
        /// - Offset 5 (1 byte): Text Type
        /// - Offset 6 (4 bytes): Sender Timestamp (UInt32 LE)
        /// - Offset 10+ (N bytes): Message payload (UTF-8)
        static func parse(_ data: Data, version: Version) -> MeshEvent {
            var offset = 0
            var snr: Double?

            let minSize = version == .v3 ? PacketSize.channelMessageV3Minimum : PacketSize.channelMessageV1Minimum
            guard data.count >= minSize else {
                return .parseFailure(
                    data: data,
                    reason: "ChannelMessage response too short: \(data.count) < \(minSize)"
                )
            }

            if version == .v3 {
                snr = Double(Int8(bitPattern: data[offset])) / 4.0
                offset += 1
                offset += 2 // reserved
            }

            let channelIndex = data[offset]; offset += 1
            let pathLen = data[offset]; offset += 1
            let txtType = data[offset]; offset += 1
            let timestamp = Date(timeIntervalSince1970: TimeInterval(data.readUInt32LE(at: offset))); offset += 4

            // Handle UTF-8 decoding
            let textData = Data(data[offset...])
            let text: String
            if let decoded = String(data: textData, encoding: .utf8) {
                text = decoded
            } else {
                parserLogger.warning("ChannelMessage: Invalid UTF-8 in message payload, using lossy conversion")
                text = String(decoding: textData, as: UTF8.self)
            }

            return .channelMessageReceived(MeshCore.ChannelMessage(
                channelIndex: channelIndex,
                pathLength: pathLen,
                textType: txtType,
                senderTimestamp: timestamp,
                text: text,
                snr: snr
            ))
        }
    }

    // MARK: - ChannelDatagram

    /// Parser for incoming channel binary datagrams. Firmware v11+ (MeshCore v1.15.0+).
    enum ChannelDatagram {
        /// Parses a channel datagram.
        ///
        /// ### Binary Format (offsets exclude the `0x1B` opcode byte, stripped by ``PacketParser``)
        /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
        /// - Offset 1 (2 bytes): Reserved
        /// - Offset 3 (1 byte): Channel Index
        /// - Offset 4 (1 byte): Path Length — `0xFF` = direct route; otherwise flood-accumulated path encoding
        /// - Offset 5 (2 bytes): Data Type (UInt16 LE)
        /// - Offset 7 (1 byte): Data Length
        /// - Offset 8+: Binary payload (length = data_len, clamped to remaining bytes)
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.channelDatagramMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "ChannelDatagram response too short: \(data.count) < \(PacketSize.channelDatagramMinimum)"
                )
            }

            // `PacketParser.parse` already normalises payloads via `Data(data.dropFirst())`,
            // so `data.startIndex == 0` here; offset-based subscripting is slice-safe.
            // Matches the convention used by `Parsers.ChannelMessage.parse`.
            var offset = 0
            let snr = Double(Int8(bitPattern: data[offset])) / 4.0
            offset += 1
            offset += 2 // reserved
            let channelIndex = data[offset]; offset += 1
            let pathLen = data[offset]; offset += 1
            let dataType = data.readUInt16LE(at: offset); offset += 2
            let declared = Int(data[offset]); offset += 1

            let remaining = data.count - offset
            let length = min(declared, remaining)
            let payload = Data(data[offset..<offset + length])

            return .channelDataReceived(MeshCore.ChannelDatagram(
                channelIndex: channelIndex,
                pathLength: pathLen,
                dataType: dataType,
                data: payload,
                snr: snr
            ))
        }
    }

    // MARK: - DefaultFloodScope

    /// Parser for the persisted default flood scope. Firmware v11+ (MeshCore v1.15.0+).
    enum DefaultFloodScope {
        /// Parses the default flood scope response.
        ///
        /// ### Binary Format (offsets exclude the `0x1C` opcode byte, stripped by ``PacketParser``)
        /// - Empty payload (0 bytes): No default scope configured, emits `.defaultFloodScope(nil)`.
        /// - Populated (exactly 47 bytes): `[name:31 zero-padded UTF-8][key:16]`.
        ///
        /// Firmware emits exactly 0 or 47 bytes (`MyMesh.cpp:1915-1917`); other lengths
        /// indicate protocol drift and fall through to `parseFailure`.
        static func parse(_ data: Data) -> MeshEvent {
            if data.isEmpty {
                return .defaultFloodScope(nil)
            }
            guard data.count == PacketSize.defaultFloodScopeSet else {
                return .parseFailure(
                    data: data,
                    reason: "DefaultFloodScope response wrong size: \(data.count), expected 0 or \(PacketSize.defaultFloodScopeSet)"
                )
            }

            // `PacketParser.parse` zero-aligns the payload; use `data` directly,
            // matching the sibling `Parsers.ChannelMessage.parse` convention.
            var offset = 0
            let nameField = Data(data[offset..<(offset + PacketSize.defaultFloodScopeNameField)])
            offset += PacketSize.defaultFloodScopeNameField
            let nullIdx = nameField.firstIndex(of: 0) ?? nameField.endIndex
            let nameBytes = Data(nameField[..<nullIdx])

            // Mirror `ContactMessage` / `ChannelMessage` lossy-UTF-8 handling so a corrupt
            // name doesn't silently misclassify a populated scope as null — the scope itself
            // is set, only the display name is garbled.
            let name: String
            if let decoded = String(data: nameBytes, encoding: .utf8) {
                name = decoded
            } else {
                parserLogger.warning("DefaultFloodScope: Invalid UTF-8 in name field, using lossy conversion")
                name = String(decoding: nameBytes, as: UTF8.self)
            }

            let key = Data(data[offset..<(offset + PacketSize.defaultFloodScopeKeyBytes)])

            return .defaultFloodScope(MeshCore.DefaultFloodScope(name: name, scopeKey: key))
        }
    }

    // MARK: - PrivateKey

    /// Parser for exported private key data.
    enum PrivateKey {
        /// Parses the 64-byte private key.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.privateKeyMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "PrivateKey response too short: \(data.count) < \(PacketSize.privateKeyMinimum)"
                )
            }
            return .privateKey(Data(data.prefix(PacketSize.privateKeyMinimum)))
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

    // MARK: - StatusResponse

    /// Parser for remote node status reports.
    enum StatusResponse {
        /// Parses remote node status (58 bytes).
        ///
        /// ### Binary Format
        /// - Offset 0 (1 byte): Reserved (skipped)
        /// - Offset 1 (6 bytes): Public Key Prefix
        /// - Offset 7 (2 bytes): Battery level in mV (UInt16 LE)
        /// - Offset 9 (2 bytes): Tx queue length (UInt16 LE)
        /// - Offset 11 (2 bytes): Noise floor (Int16 LE)
        /// - Offset 13 (2 bytes): Last RSSI (Int16 LE)
        /// - Offset 15 (8 bytes): Total packets recv/sent (UInt32 LE)
        /// - Offset 23 (8 bytes): Airtime/Uptime in seconds (UInt32 LE)
        /// - Offset 31 (16 bytes): Stats for flood/direct comms (UInt32 LE)
        /// - Offset 47 (2 bytes): Full events counter
        /// - Offset 49 (2 bytes): Last SNR scaled by 4 (Int16 LE)
        /// - Offset 51 (4 bytes): Duplicate counters
        /// - Offset 55 (4 bytes): Repeater: Rx airtime (UInt32 LE); Room server: posted count (UInt16 LE) + post-push count (UInt16 LE)
        /// - Offset 59 (4 bytes): Repeater only: Receive errors (UInt32 LE, optional)
        static func parse(_ data: Data, layout: MeshCore.StatusResponse.Layout = .repeater) -> MeshEvent {
            guard data.count >= PacketSize.statusResponseMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "StatusResponse too short: \(data.count) < \(PacketSize.statusResponseMinimum)"
                )
            }

            var offset = 0
            offset += 1  // Skip reserved byte (per firmware and Python parsing.py)
            let pubkeyPrefix = Data(data[offset..<offset+6]); offset += 6
            let battery = Int(data.readUInt16LE(at: offset)); offset += 2
            let txQueueLen = Int(data.readUInt16LE(at: offset)); offset += 2
            let noiseFloor = Int(data.readInt16LE(at: offset)); offset += 2
            let lastRSSI = Int(data.readInt16LE(at: offset)); offset += 2
            let packetsRecv = data.readUInt32LE(at: offset); offset += 4
            let packetsSent = data.readUInt32LE(at: offset); offset += 4
            let airtime = data.readUInt32LE(at: offset); offset += 4
            let uptime = data.readUInt32LE(at: offset); offset += 4
            let sentFlood = data.readUInt32LE(at: offset); offset += 4
            let sentDirect = data.readUInt32LE(at: offset); offset += 4
            let recvFlood = data.readUInt32LE(at: offset); offset += 4
            let recvDirect = data.readUInt32LE(at: offset); offset += 4
            let fullEvents = Int(data.readUInt16LE(at: offset)); offset += 2
            let lastSNR = Double(data.readInt16LE(at: offset)) / 4.0; offset += 2
            let directDups = Int(data.readUInt16LE(at: offset)); offset += 2
            let floodDups = Int(data.readUInt16LE(at: offset)); offset += 2
            switch layout {
            case .repeater:
                let rxAirtime = data.readUInt32LE(at: offset); offset += 4
                let receiveErrors: UInt32 = data.count >= offset + 4 ? data.readUInt32LE(at: offset) : 0

                return .statusResponse(MeshCore.StatusResponse(
                    layout: .repeater,
                    publicKeyPrefix: pubkeyPrefix,
                    battery: battery,
                    txQueueLength: txQueueLen,
                    noiseFloor: noiseFloor,
                    lastRSSI: lastRSSI,
                    packetsReceived: packetsRecv,
                    packetsSent: packetsSent,
                    airtime: airtime,
                    uptime: uptime,
                    sentFlood: sentFlood,
                    sentDirect: sentDirect,
                    receivedFlood: recvFlood,
                    receivedDirect: recvDirect,
                    fullEvents: fullEvents,
                    lastSNR: lastSNR,
                    directDuplicates: directDups,
                    floodDuplicates: floodDups,
                    rxAirtime: rxAirtime,
                    receiveErrors: receiveErrors
                ))

            case .roomServer:
                let postedCount: UInt16? = data.count >= offset + 4
                    ? data.readUInt16LE(at: offset) : nil
                let postPushCount: UInt16? = data.count >= offset + 4
                    ? data.readUInt16LE(at: offset + 2) : nil

                return .statusResponse(MeshCore.StatusResponse(
                    layout: .roomServer,
                    publicKeyPrefix: pubkeyPrefix,
                    battery: battery,
                    txQueueLength: txQueueLen,
                    noiseFloor: noiseFloor,
                    lastRSSI: lastRSSI,
                    packetsReceived: packetsRecv,
                    packetsSent: packetsSent,
                    airtime: airtime,
                    uptime: uptime,
                    sentFlood: sentFlood,
                    sentDirect: sentDirect,
                    receivedFlood: recvFlood,
                    receivedDirect: recvDirect,
                    fullEvents: fullEvents,
                    lastSNR: lastSNR,
                    directDuplicates: directDups,
                    floodDuplicates: floodDups,
                    rxAirtime: 0,
                    receiveErrors: 0,
                    roomServerPostedCount: postedCount,
                    roomServerPostPushCount: postPushCount
                ))
            }
        }

        /// Parses status data from a BINARY_RESPONSE (0x8C) payload.
        ///
        /// ### Binary Format (Format 2 - no pubkey header)
        /// Fields start at offset 0:
        /// - Offset 0 (2 bytes): Battery level in mV (UInt16 LE)
        /// - Offset 2 (2 bytes): Tx queue length (UInt16 LE)
        /// - Offset 4 (2 bytes): Noise floor (Int16 LE)
        /// - Offset 6 (2 bytes): Last RSSI (Int16 LE)
        /// - Offset 8 (4 bytes): Packets received (UInt32 LE)
        /// - Offset 12 (4 bytes): Packets sent (UInt32 LE)
        /// - Offset 16 (4 bytes): Airtime in seconds (UInt32 LE)
        /// - Offset 20 (4 bytes): Uptime in seconds (UInt32 LE)
        /// - Offset 24 (4 bytes): Sent flood (UInt32 LE)
        /// - Offset 28 (4 bytes): Sent direct (UInt32 LE)
        /// - Offset 32 (4 bytes): Received flood (UInt32 LE)
        /// - Offset 36 (4 bytes): Received direct (UInt32 LE)
        /// - Offset 40 (2 bytes): Full events counter (UInt16 LE)
        /// - Offset 42 (2 bytes): Last SNR scaled by 4 (Int16 LE)
        /// - Offset 44 (2 bytes): Direct duplicates (UInt16 LE)
        /// - Offset 46 (2 bytes): Flood duplicates (UInt16 LE)
        /// - Offset 48 (4 bytes): Rx airtime (UInt32 LE, optional)
        /// - Offset 52 (4 bytes): Receive errors (UInt32 LE, optional, v1.12+)
        ///
        /// - Parameters:
        ///   - data: Raw binary response payload (without the 4-byte tag).
        ///   - publicKeyPrefix: The 6-byte public key prefix from the pending request context.
        /// - Returns: A `StatusResponse` if parsing succeeds, `nil` otherwise.
        static func parseFromBinaryResponse(
            _ data: Data,
            publicKeyPrefix: Data,
            layout: MeshCore.StatusResponse.Layout = .repeater
        ) -> MeshCore.StatusResponse? {
            // Accept exactly 48 (no rxAirtime), 52 (with rxAirtime), or 56+ (with receiveErrors).
            // Reject malformed payloads with incomplete fields (49-51, 53-55).
            guard data.count == PacketSize.binaryResponseStatusBase ||
                  data.count == PacketSize.binaryResponseStatusWithRxAirtime ||
                  data.count >= PacketSize.binaryResponseStatusWithReceiveErrors else { return nil }

            var offset = 0
            let battery = Int(data.readUInt16LE(at: offset)); offset += 2
            let txQueueLen = Int(data.readUInt16LE(at: offset)); offset += 2
            let noiseFloor = Int(data.readInt16LE(at: offset)); offset += 2
            let lastRSSI = Int(data.readInt16LE(at: offset)); offset += 2
            let packetsRecv = data.readUInt32LE(at: offset); offset += 4
            let packetsSent = data.readUInt32LE(at: offset); offset += 4
            let airtime = data.readUInt32LE(at: offset); offset += 4
            let uptime = data.readUInt32LE(at: offset); offset += 4
            let sentFlood = data.readUInt32LE(at: offset); offset += 4
            let sentDirect = data.readUInt32LE(at: offset); offset += 4
            let recvFlood = data.readUInt32LE(at: offset); offset += 4
            let recvDirect = data.readUInt32LE(at: offset); offset += 4
            let fullEvents = Int(data.readUInt16LE(at: offset)); offset += 2
            let lastSNR = Double(data.readInt16LE(at: offset)) / 4.0; offset += 2
            let directDups = Int(data.readUInt16LE(at: offset)); offset += 2
            let floodDups = Int(data.readUInt16LE(at: offset)); offset += 2
            switch layout {
            case .repeater:
                let rxAirtime: UInt32 = data.count >= PacketSize.binaryResponseStatusWithRxAirtime
                    ? data.readUInt32LE(at: offset) : 0
                offset += 4
                let receiveErrors: UInt32 = data.count >= PacketSize.binaryResponseStatusWithReceiveErrors
                    ? data.readUInt32LE(at: offset) : 0

                return MeshCore.StatusResponse(
                    layout: .repeater,
                    publicKeyPrefix: publicKeyPrefix,
                    battery: battery,
                    txQueueLength: txQueueLen,
                    noiseFloor: noiseFloor,
                    lastRSSI: lastRSSI,
                    packetsReceived: packetsRecv,
                    packetsSent: packetsSent,
                    airtime: airtime,
                    uptime: uptime,
                    sentFlood: sentFlood,
                    sentDirect: sentDirect,
                    receivedFlood: recvFlood,
                    receivedDirect: recvDirect,
                    fullEvents: fullEvents,
                    lastSNR: lastSNR,
                    directDuplicates: directDups,
                    floodDuplicates: floodDups,
                    rxAirtime: rxAirtime,
                    receiveErrors: receiveErrors
                )

            case .roomServer:
                let postedCount: UInt16? = data.count >= PacketSize.binaryResponseStatusWithRxAirtime
                    ? data.readUInt16LE(at: offset) : nil
                let postPushCount: UInt16? = data.count >= PacketSize.binaryResponseStatusWithRxAirtime
                    ? data.readUInt16LE(at: offset + 2) : nil

                return MeshCore.StatusResponse(
                    layout: .roomServer,
                    publicKeyPrefix: publicKeyPrefix,
                    battery: battery,
                    txQueueLength: txQueueLen,
                    noiseFloor: noiseFloor,
                    lastRSSI: lastRSSI,
                    packetsReceived: packetsRecv,
                    packetsSent: packetsSent,
                    airtime: airtime,
                    uptime: uptime,
                    sentFlood: sentFlood,
                    sentDirect: sentDirect,
                    receivedFlood: recvFlood,
                    receivedDirect: recvDirect,
                    fullEvents: fullEvents,
                    lastSNR: lastSNR,
                    directDuplicates: directDups,
                    floodDuplicates: floodDups,
                    rxAirtime: 0,
                    receiveErrors: 0,
                    roomServerPostedCount: postedCount,
                    roomServerPostPushCount: postPushCount
                )
            }
        }
    }

    // MARK: - TelemetryResponse

    /// Parser for remote sensor telemetry.
    enum TelemetryResponse {
        /// Parses a telemetry push notification.
        ///
        /// ### Binary Format
        /// (Per firmware MyMesh.cpp push_telemetry_response)
        /// - Offset 0 (1 byte): Reserved
        /// - Offset 1 (6 bytes): Public key prefix
        /// - Offset 7 (N bytes): Raw LPP telemetry data
        static func parse(_ data: Data) -> MeshEvent {
            // Minimum: reserved(1) + pubkey(6) = 7 bytes
            guard data.count >= 7 else {
                return .parseFailure(data: data, reason: "TelemetryResponse too short: \(data.count) bytes, need 7")
            }

            // Skip reserved byte at offset 0
            let pubkeyPrefix = Data(data[1..<7])
            // LPP data starts at byte 7, no tag in push frames
            let rawData = Data(data.dropFirst(7))

            return .telemetryResponse(MeshCore.TelemetryResponse(
                publicKeyPrefix: pubkeyPrefix,
                tag: nil,
                rawData: rawData
            ))
        }

        /// Parses telemetry data from a BINARY_RESPONSE (0x8C) payload.
        ///
        /// ### Binary Format (Format 2 - no pubkey header)
        /// Raw LPP data starts at offset 0.
        ///
        /// - Parameters:
        ///   - data: Raw binary response payload (without the 4-byte tag).
        ///   - publicKeyPrefix: The 6-byte public key prefix from the pending request context.
        /// - Returns: A `TelemetryResponse` with the raw data for LPP decoding.
        static func parseFromBinaryResponse(_ data: Data, publicKeyPrefix: Data) -> MeshCore.TelemetryResponse {
            MeshCore.TelemetryResponse(
                publicKeyPrefix: publicKeyPrefix,
                tag: nil,
                rawData: data
            )
        }
    }

    // MARK: - BinaryResponse

    /// Parser for generic binary protocol responses.
    enum BinaryResponse {
        /// Parses a generic binary response.
        ///
        /// - Parameter data: Raw response data.
        /// - Returns: A `.binaryResponse` event.
        ///
        /// Note: This returns a generic `.binaryResponse` event. The caller should
        /// use specialized parsers (like ``ACLParser`` or ``MMAParser``) to decode
        /// the `responseData` based on the request context.
        static func parse(_ data: Data) -> MeshEvent {
            // Binary response format:
            // - Byte 0: Request type (unused, skip)
            // - Bytes 1-4: Tag (matches expectedAck from messageSent)
            // - Bytes 5+: Response data
            guard data.count >= 5 else {
                return .parseFailure(data: data, reason: "BinaryResponse too short: \(data.count) < 5")
            }
            let tag = Data(data[1..<5])
            let responseData = Data(data.dropFirst(5))
            return .binaryResponse(tag: tag, data: responseData)
        }
    }

    // MARK: - PathDiscoveryResponse

    /// Parser for path discovery results.
    enum PathDiscoveryResponse {
        /// Parses a path discovery response.
        ///
        /// ### Binary Format
        /// (Per firmware MyMesh.cpp push_path_discovery_response)
        /// - Offset 0 (1 byte): Reserved
        /// - Offset 1 (6 bytes): Public key prefix
        /// - Offset 7 (1 byte): Outbound path length
        /// - Offset 8 (N bytes): Outbound path data
        /// - Offset 8+N (1 byte): Inbound path length
        /// - Offset 9+N (M bytes): Inbound path data
        static func parse(_ data: Data) -> MeshEvent {
            // Minimum: reserved(1) + pubkey(6) + out_path_len(1) + in_path_len(1) = 9 bytes
            guard data.count >= PacketSize.pathDiscoveryMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "PathDiscoveryResponse too short: \(data.count) bytes, need \(PacketSize.pathDiscoveryMinimum)"
                )
            }

            // Skip reserved byte at offset 0
            let pubkeyPrefix = Data(data[1..<7])
            var offset = 7

            var outPathLength: UInt8 = 0
            var outPath = Data()
            var inPathLength: UInt8 = 0
            var inPath = Data()

            // Parse outbound path (multibyte encoded). Preserve the raw length
            // byte so the UI can display an accurate hop count without needing
            // the device's cached `hashSize`. A truncated payload where the
            // declared byte length runs past the end of `data` is surfaced as
            // `.parseFailure` so `PathInfo`'s size invariant holds.
            if data.count > offset {
                outPathLength = data[offset]
                offset += 1
                if let decoded = decodePathLen(outPathLength), decoded.byteLength > 0 {
                    guard data.count >= offset + decoded.byteLength else {
                        return .parseFailure(
                            data: data,
                            reason: "PathDiscoveryResponse truncated outbound path: need \(decoded.byteLength) bytes, have \(data.count - offset)"
                        )
                    }
                    outPath = Data(data[offset..<offset + decoded.byteLength])
                    offset += decoded.byteLength
                }
            }

            // Parse inbound path (multibyte encoded)
            if data.count > offset {
                inPathLength = data[offset]
                offset += 1
                if let decoded = decodePathLen(inPathLength), decoded.byteLength > 0 {
                    guard data.count >= offset + decoded.byteLength else {
                        return .parseFailure(
                            data: data,
                            reason: "PathDiscoveryResponse truncated inbound path: need \(decoded.byteLength) bytes, have \(data.count - offset)"
                        )
                    }
                    inPath = Data(data[offset..<offset + decoded.byteLength])
                }
            }

            return .pathResponse(PathInfo(
                publicKeyPrefix: pubkeyPrefix,
                outPathLength: outPathLength,
                outPath: outPath,
                inPathLength: inPathLength,
                inPath: inPath
            ))
        }
    }

    // MARK: - ControlData

    /// Parser for low-level protocol control data.
    enum ControlData {
        /// Parses SNR, RSSI, and payload from a control packet.
        ///
        /// This parser automatically detects DISCOVER_RESP payloads (upper nibble 0x9)
        /// and returns a structured `.discoverResponse` event instead of raw `.controlData`.
        ///
        /// ### Binary Format
        /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
        /// - Offset 1 (1 byte): RSSI (Int8)
        /// - Offset 2 (1 byte): Path length
        /// - Offset 3 (1 byte): Payload type (upper nibble 0x9 = DISCOVER_RESP)
        /// - Offset 4+ (N bytes): Payload data
        ///
        /// ### DISCOVER_RESP Inner Payload Format
        /// - Offset 0 (1 byte): SNR in scaled by 4 (Int8)
        /// - Offset 1-4 (4 bytes): Tag (UInt32 LE)
        /// - Offset 5+ (8 or 32 bytes): Public key (prefix or full)
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.controlDataMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "ControlData too short: \(data.count) < \(PacketSize.controlDataMinimum)"
                )
            }
            let snr = Double(Int8(bitPattern: data[0])) / 4.0
            let rssi = Int(Int8(bitPattern: data[1]))
            let pathLen = data[2]
            let payloadType = data[3]
            let payload = Data(data.dropFirst(4))

            // Check for DISCOVER_RESP (upper nibble 0x9)
            // Minimum inner payload: snr_in(1) + tag(4) = 5 bytes
            if payloadType & 0xF0 == 0x90 && payload.count >= 5 {
                let nodeType = payloadType & 0x0F
                let snrIn = Double(Int8(bitPattern: payload[0])) / 4.0
                let tag = Data(payload[1..<5])

                // Pubkey: 32 bytes if available, otherwise 8-byte prefix
                let pubkey: Data
                if payload.count >= 37 {
                    pubkey = Data(payload[5..<37])
                } else if payload.count >= 13 {
                    pubkey = Data(payload[5..<13])
                } else {
                    pubkey = Data(payload.dropFirst(5))
                }

                return .discoverResponse(DiscoverResponse(
                    nodeType: nodeType,
                    snrIn: snrIn,
                    snr: snr,
                    rssi: rssi,
                    pathLength: pathLen,
                    tag: tag,
                    publicKey: pubkey
                ))
            }

            return .controlData(ControlDataInfo(
                snr: snr,
                rssi: rssi,
                pathLength: pathLen,
                payloadType: payloadType,
                payload: payload
            ))
        }
    }

    // MARK: - Signature

    /// Parser for cryptographic signature responses.
    enum Signature {
        /// Wraps the signature data in a `.signature` event.
        static func parse(_ data: Data) -> MeshEvent {
            return .signature(data)
        }
    }

    // MARK: - CoreStats

    /// Parser for core system statistics.
    enum CoreStats {
        /// Parses battery, uptime, errors, and queue length.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.coreStatsMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "CoreStats too short: \(data.count) < \(PacketSize.coreStatsMinimum)"
                )
            }
            let batteryMV = data.readUInt16LE(at: 0)
            let uptime = data.readUInt32LE(at: 2)
            let errors = data.readUInt16LE(at: 6)
            let queueLen = data[8]

            return .statsCore(MeshCore.CoreStats(
                batteryMV: batteryMV,
                uptimeSeconds: uptime,
                errors: errors,
                queueLength: queueLen
            ))
        }
    }

    // MARK: - RadioStats

    /// Parser for radio performance statistics.
    enum RadioStats {
        /// Parses noise floor, SNR, and radio airtime.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.radioStatsMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "RadioStats too short: \(data.count) < \(PacketSize.radioStatsMinimum)"
                )
            }
            let noiseFloor = data.readInt16LE(at: 0)
            let lastRSSI = Int8(bitPattern: data[2])
            let lastSNR = Double(Int8(bitPattern: data[3])) / 4.0
            let txAir = data.readUInt32LE(at: 4)
            let rxAir = data.readUInt32LE(at: 8)

            return .statsRadio(MeshCore.RadioStats(
                noiseFloor: noiseFloor,
                lastRSSI: lastRSSI,
                lastSNR: lastSNR,
                txAirtimeSeconds: txAir,
                rxAirtimeSeconds: rxAir
            ))
        }
    }

    // MARK: - PacketStats

    /// Parser for packet counters.
    enum PacketStats {
        /// Parses total sent/received, flood/direct packet counts, and receive errors.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.packetStatsMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "PacketStats too short: \(data.count) < \(PacketSize.packetStatsMinimum)"
                )
            }
            let receiveErrors: UInt32 = data.count >= PacketSize.packetStatsWithReceiveErrors
                ? data.readUInt32LE(at: 24) : 0
            return .statsPackets(MeshCore.PacketStats(
                received: data.readUInt32LE(at: 0),
                sent: data.readUInt32LE(at: 4),
                floodTx: data.readUInt32LE(at: 8),
                directTx: data.readUInt32LE(at: 12),
                floodRx: data.readUInt32LE(at: 16),
                directRx: data.readUInt32LE(at: 20),
                receiveErrors: receiveErrors
            ))
        }
    }

    // MARK: - ChannelInfo

    /// Parser for channel configuration data.
    enum ChannelInfo {
        /// Parses channel index, name, and PSK secret.
        ///
        /// The channel name is a null-terminated C string in a 32-byte buffer.
        /// Bytes after the null terminator may be uninitialized garbage from the firmware,
        /// so we must find the null and decode only the bytes before it.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.channelInfoMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "ChannelInfo too short: \(data.count) < \(PacketSize.channelInfoMinimum)"
                )
            }
            let index = data[0]
            let nameData = data[1..<33]

            // Find first null byte - firmware uses strcpy which leaves garbage after the null
            let nullIndex = nameData.firstIndex(of: 0) ?? nameData.endIndex
            let validNameData = nameData[nameData.startIndex..<nullIndex]
            let name = String(decoding: validNameData, as: UTF8.self)

            let secret = Data(data[33..<49])

            return .channelInfo(MeshCore.ChannelInfo(
                index: index,
                name: name,
                secret: secret
            ))
        }
    }

    // MARK: - CustomVars

    /// Parser for user-defined custom variables.
    enum CustomVars {
        /// Parses custom vars from a comma-separated key:value string.
        ///
        /// Format: `key1:value1,key2:value2,...`
        static func parse(_ data: Data) -> MeshEvent {
            var vars: [String: String] = [:]

            guard let rawString = String(data: data, encoding: .utf8),
                  !rawString.isEmpty else {
                return .customVars(vars)
            }

            let pairs = rawString.split(separator: ",")
            for pair in pairs {
                let parts = pair.split(separator: ":", maxSplits: 1)
                if parts.count == 2 {
                    let key = String(parts[0])
                    let value = String(parts[1])
                    vars[key] = value
                }
            }
            return .customVars(vars)
        }
    }

    // MARK: - TraceData

    /// Parser for full trace route results.
    public enum TraceData {
        /// Parses trace route data.
        ///
        /// ### Binary Format
        /// (Per firmware MyMesh.cpp onTraceRecv, v1.11+)
        /// - Offset 0 (1 byte): Reserved
        /// - Offset 1 (1 byte): Path length (total hash bytes, not hop count)
        /// - Offset 2 (1 byte): Flags (bits 0-1: path_sz, determines hash size)
        /// - Offset 3 (4 bytes): Tag (UInt32 LE)
        /// - Offset 7 (4 bytes): Auth code (UInt32 LE)
        /// - Offset 11 (pathLen bytes): Hash bytes
        /// - Offset 11+pathLen (hopCount bytes): SNR bytes (one per hop)
        /// - Offset 11+pathLen+hopCount (1 byte): Final SNR at destination
        ///
        /// path_sz encoding:
        /// - 0: 1-byte hashes (pathLen = hopCount)
        /// - 1: 2-byte hashes (hopCount = pathLen / 2)
        /// - 2: 4-byte hashes (hopCount = pathLen / 4)
        /// - 3: 8-byte hashes (hopCount = pathLen / 8)
        static func parse(_ data: Data) -> MeshEvent {
            // Minimum: reserved(1) + pathLen(1) + flags(1) + tag(4) + authCode(4) = 11 bytes
            guard data.count >= PacketSize.traceDataMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "TraceData too short: \(data.count) bytes, need \(PacketSize.traceDataMinimum)"
                )
            }

            let pathLength = Int(data[1])
            let flags = data[2]
            let pathSz = Int(flags & 0x03)
            let hashSize = 1 << pathSz  // 1, 2, 4, or 8 bytes per hop
            let hopCount = pathLength > 0 ? pathLength / hashSize : 0

            let tag = data.readUInt32LE(at: 3)
            let authCode = data.readUInt32LE(at: 7)

            let hashesStart = 11
            let snrsStart = hashesStart + pathLength
            let finalSnrOffset = snrsStart + hopCount

            // Validate we have enough data
            guard data.count >= finalSnrOffset + 1 else {
                return .parseFailure(
                    data: data,
                    reason: "TraceData too short for path: need \(finalSnrOffset + 1), have \(data.count)"
                )
            }

            var path: [TraceNode] = []

            // Parse each hop
            for i in 0..<hopCount {
                let hashOffset = hashesStart + (i * hashSize)
                let hashBytes = Data(data[hashOffset..<(hashOffset + hashSize)])
                let snrOffset = snrsStart + i
                let snr = Double(Int8(bitPattern: data[snrOffset])) / 4.0

                // Check if all hash bytes are 0xFF (destination marker)
                let isDestination = hashBytes.allSatisfy { $0 == 0xFF }
                path.append(TraceNode(hashBytes: isDestination ? nil : hashBytes, snr: snr))
            }

            // Final SNR at destination
            let finalSnr = Double(Int8(bitPattern: data[finalSnrOffset])) / 4.0
            path.append(TraceNode(hashBytes: nil, snr: finalSnr))

            return .traceData(TraceInfo(
                tag: tag,
                authCode: authCode,
                flags: flags,
                pathLength: UInt8(pathLength),
                path: path
            ))
        }
    }

    // MARK: - RawData

    /// Parser for generic raw packet notifications.
    enum RawData {
        /// Parses raw radio data.
        ///
        /// ### Binary Format
        /// (Per firmware MyMesh.cpp push_raw_data)
        /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
        /// - Offset 1 (1 byte): RSSI (Int8)
        /// - Offset 2 (1 byte): Reserved (0xFF)
        /// - Offset 3 (N bytes): Payload data
        static func parse(_ data: Data) -> MeshEvent {
            // Minimum: snr(1) + rssi(1) + reserved(1) = 3 bytes
            guard data.count >= PacketSize.rawDataMinimum else {
                return .parseFailure(data: data, reason: "RawData too short: \(data.count) bytes, need \(PacketSize.rawDataMinimum)")
            }

            let snr = Double(Int8(bitPattern: data[0])) / 4.0
            let rssi = Int(Int8(bitPattern: data[1]))
            // Skip reserved byte at offset 2
            let payload = Data(data.dropFirst(3))

            return .rawData(RawDataInfo(snr: snr, rssi: rssi, payload: payload))
        }
    }

    // MARK: - LogData

    /// Parser for remote debug log entries.
    enum LogData {
        /// Parses log messages with optional signal metadata.
        /// Returns rxLogData with parsed RF packet if parsing succeeds,
        /// otherwise returns logData with raw payload.
        static func parse(_ data: Data) -> MeshEvent {
            if data.count >= 2 {
                let snr = Double(Int8(bitPattern: data[0])) / 4.0
                let rssi = Int(Int8(bitPattern: data[1]))
                let payload = Data(data.dropFirst(2))
                if let parsed = RxLogParser.parse(snr: snr, rssi: rssi, payload: payload) {
                    return .rxLogData(parsed)
                }
                return .logData(LogDataInfo(snr: snr, rssi: rssi, payload: payload))
            }
            if let parsed = RxLogParser.parse(snr: nil, rssi: nil, payload: data) {
                return .rxLogData(parsed)
            }
            return .logData(LogDataInfo(snr: nil, rssi: nil, payload: data))
        }
    }

    // MARK: - LoginSuccess

    /// Parser for successful login responses (push opcode 0x85).
    ///
    /// After the opcode byte is stripped, two payload formats are seen in the wild:
    ///
    /// **Legacy format (7 bytes):**
    /// - byte 0: admin indicator (companion radio hardcodes `0` for legacy "OK" replies).
    /// - bytes 1–6: pubkey prefix.
    ///
    /// **v7+ extended format (13 bytes):**
    /// - byte 0: admin indicator. Populated by the server and forwarded verbatim by the
    ///           companion radio. `1` means admin; any other value (including `0` and `2`)
    ///           means non-admin. The official C++ room server uses a tri-state encoding
    ///           (`0` / `1` / `2`) where `2` signals guest for downstream role awareness;
    ///           clients must treat `2` as non-admin. Only `== 1` is a safe admin test.
    /// - bytes 1–6: pubkey prefix.
    /// - bytes 7–10: server timestamp (not parsed).
    /// - byte 11: full ACL permissions byte. Encoding is firmware-specific:
    ///            - Official C++ firmware: `0 = guest`, `1 = read-only`, `2 = read-write`,
    ///              `3 = admin`.
    ///            - pyMC: `0x01 = non-admin (guest)`, `0x02 = admin`.
    ///            This parser uses byte 0 for the admin gate. For non-admin sessions, only byte 11
    ///            = 2 maps to `.readWrite`; every other value (including C++ `1 = read-only`)
    ///            falls back to `.guest` so a non-posting client cannot acquire `canPost`.
    /// - byte 12: firmware version level (not parsed).
    ///
    /// The byte-0 admin indicator is preferred over re-deriving admin status from the
    /// permissions byte because the latter is encoding-specific and has produced
    /// cross-implementation bugs.
    enum LoginSuccess {
        /// Parses permissions and admin status.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.loginSuccessMinimum else {
                return .loginSuccess(LoginInfo(permissions: 0, isAdmin: false, publicKeyPrefix: Data()))
            }

            let pubkeyPrefix = Data(data[1..<7])

            // See type-level doc for byte-0 admin gate and byte-11 coalescing rationale.
            if data.count >= PacketSize.loginSuccessExtended {
                let isAdmin = data[0] == 1
                let firmwarePermissions = data[11]

                let normalizedPermissions: UInt8
                if isAdmin {
                    normalizedPermissions = 0x02  // RoomPermissionLevel.admin
                } else if firmwarePermissions == 0x02 {
                    normalizedPermissions = 0x01  // RoomPermissionLevel.readWrite
                } else {
                    normalizedPermissions = 0x00  // RoomPermissionLevel.guest (read-only / unknowns)
                }

                return .loginSuccess(LoginInfo(
                    permissions: normalizedPermissions,
                    isAdmin: isAdmin,
                    publicKeyPrefix: pubkeyPrefix
                ))
            }

            // Legacy format: convert legacy indicator to RoomPermissionLevel values
            // Legacy byte 0: 0=member/readWrite, 1=admin, 2=guest
            // RoomPermissionLevel: 0x00=guest, 0x01=readWrite, 0x02=admin
            let legacyIndicator = data[0]
            let permissions: UInt8
            let isAdmin: Bool

            switch legacyIndicator {
            case 1:
                // Admin
                permissions = 0x02  // RoomPermissionLevel.admin
                isAdmin = true
            case 2:
                // Guest/readonly
                permissions = 0x00  // RoomPermissionLevel.guest
                isAdmin = false
            default:
                // Member/readWrite (legacy 0 or unknown values)
                permissions = 0x01  // RoomPermissionLevel.readWrite
                isAdmin = false
            }

            return .loginSuccess(LoginInfo(
                permissions: permissions,
                isAdmin: isAdmin,
                publicKeyPrefix: pubkeyPrefix
            ))
        }
    }

    // MARK: - New Response Parsers (Issue #1)

    /// Parser for advertisement path responses.
    ///
    /// ### Binary Format
    /// - Offset 0-3 (4 bytes): Receive timestamp (UInt32 LE)
    /// - Offset 4 (1 byte): Path length
    /// - Offset 5+ (N bytes): Path data (length = pathLength)
    public enum AdvertPathResponse {
        /// Parses an advertisement path response.
        ///
        /// - Parameter data: Raw response data.
        /// - Returns: An `.advertPathResponse` event or `.parseFailure`.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= 5 else {
                return .parseFailure(
                    data: data,
                    reason: "AdvertPathResponse too short: \(data.count) bytes, need 5"
                )
            }

            let timestamp = data.readUInt32LE(at: 0)
            let pathLen = data[4]
            guard let decoded = decodePathLen(pathLen) else {
                return .parseFailure(
                    data: data,
                    reason: "AdvertPathResponse uses reserved path length encoding: 0x\(String(format: "%02X", pathLen))"
                )
            }
            let byteLen = decoded.byteLength
            guard data.count >= 5 + byteLen else {
                return .parseFailure(
                    data: data,
                    reason: "AdvertPathResponse path truncated: \(data.count) < \(5 + byteLen)"
                )
            }
            let path = Data(data.dropFirst(5).prefix(byteLen))

            return .advertPathResponse(MeshCore.AdvertPathResponse(
                recvTimestamp: timestamp,
                pathLength: pathLen,
                path: path
            ))
        }
    }

    /// Parser for tuning parameters responses.
    ///
    /// ### Binary Format
    /// - Offset 0-3 (4 bytes): RX delay base * 1000 (UInt32 LE)
    /// - Offset 4-7 (4 bytes): Airtime factor * 1000 (UInt32 LE)
    public enum TuningParamsResponse {
        /// Parses a tuning parameters response.
        ///
        /// - Parameter data: Raw response data.
        /// - Returns: A `.tuningParamsResponse` event or `.parseFailure`.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= 8 else {
                return .parseFailure(
                    data: data,
                    reason: "TuningParamsResponse too short: \(data.count) bytes, need 8"
                )
            }

            let rxDelayRaw = data.readUInt32LE(at: 0)
            let airtimeRaw = data.readUInt32LE(at: 4)

            return .tuningParamsResponse(MeshCore.TuningParamsResponse(
                rxDelayBase: Double(rxDelayRaw) / 1000.0,
                airtimeFactor: Double(airtimeRaw) / 1000.0
            ))
        }
    }
    // MARK: - AllowedRepeatFreq

    /// Parser for allowed repeat frequency ranges (v9+).
    ///
    /// ### Binary Format
    /// Sequence of 8-byte pairs:
    /// - Offset N (4 bytes): Lower frequency in kHz (UInt32 LE)
    /// - Offset N+4 (4 bytes): Upper frequency in kHz (UInt32 LE)
    public enum AllowedRepeatFreq {
        /// Parses allowed frequency ranges.
        static func parse(_ data: Data) -> MeshEvent {
            var ranges: [FrequencyRange] = []
            var offset = 0
            while offset + 8 <= data.count {
                let lower = data.readUInt32LE(at: offset)
                let upper = data.readUInt32LE(at: offset + 4)
                ranges.append(FrequencyRange(lowerKHz: lower, upperKHz: upper))
                offset += 8
            }
            return .allowedRepeatFreq(ranges)
        }
    }
}

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

// MARK: - MMA Parser

/// Specialized parser for MMA (Min/Max/Average) sensor data.
enum MMAParser {
    /// Parses MMA entries from binary protocol data.
    ///
    /// - Parameter data: Raw MMA data.
    /// - Returns: An array of ``MMAEntry`` structs.
    ///
    /// ### Binary Format
    /// `[channel:1][type:1][min:N][max:N][avg:N]`... where N is sensor data size.
    ///
    /// LPP sensor values use **Big-Endian** byte order.
    static func parse(_ data: Data) -> [MMAEntry] {
        var entries: [MMAEntry] = []
        var offset = 0

        while offset < data.count {
            guard offset + 2 <= data.count else { break }

            let channel = data[offset]
            let typeCode = data[offset + 1]
            offset += 2

            guard let sensorType = LPPSensorType(rawValue: typeCode) else { break }

            let valueSize = sensorType.dataSize
            guard offset + valueSize * 3 <= data.count else { break }

            let minData = data.subdata(in: offset..<(offset + valueSize))
            offset += valueSize
            let maxData = data.subdata(in: offset..<(offset + valueSize))
            offset += valueSize
            let avgData = data.subdata(in: offset..<(offset + valueSize))
            offset += valueSize

            let minValue = decodeToDouble(type: sensorType, data: minData)
            let maxValue = decodeToDouble(type: sensorType, data: maxData)
            let avgValue = decodeToDouble(type: sensorType, data: avgData)

            entries.append(MMAEntry(
                channel: channel,
                type: sensorType.name,
                min: minValue,
                max: maxValue,
                avg: avgValue
            ))
        }

        return entries
    }

    /// Decodes an LPP value to a double for MMA entries.
    ///
    /// - Parameters:
    ///   - type: The sensor type.
    ///   - data: Raw sensor data (Big-Endian).
    /// - Returns: Decoded floating point value.
    private static func decodeToDouble(type: LPPSensorType, data: Data) -> Double {
        switch type {
        case .digitalInput, .digitalOutput, .presence, .switchValue:
            return Double(data[0])
        case .percentage:
            return Double(data[0])
        case .humidity:
            return Double(data[0]) * 0.5
        case .temperature:
            return Double(readInt16BE(data)) / 10.0
        case .barometer:
            return Double(readUInt16BE(data)) / 10.0
        case .voltage:
            return Double(readUInt16BE(data)) / 100.0
        case .current:
            return Double(readUInt16BE(data)) / 1000.0
        case .illuminance, .concentration, .power, .direction:
            return Double(readUInt16BE(data))
        case .altitude:
            return Double(readInt16BE(data))
        case .load:
            return Double(readInt24BE(data)) / 1000.0
        case .analogInput, .analogOutput:
            return Double(readInt16BE(data)) / 100.0
        case .genericSensor:
            return Double(readUInt32BE(data))
        case .frequency:
            return Double(readUInt32BE(data))
        case .distance, .energy:
            return Double(readUInt32BE(data)) / 1000.0
        case .unixTime:
            return Double(readUInt32BE(data))
        case .accelerometer, .gyrometer, .colour, .gps:
            // Complex types - return first component only for MMA
            return Double(readInt16BE(data)) / (type == .accelerometer ? 1000.0 : 100.0)
        }
    }

    /// Reads a 16-bit signed integer (Big-Endian).
    private static func readInt16BE(_ data: Data, offset: Int = 0) -> Int16 {
        guard offset + 2 <= data.count else { return 0 }
        return Int16(data[offset]) << 8 | Int16(data[offset + 1])
    }

    /// Reads a 24-bit signed integer (Big-Endian).
    private static func readInt24BE(_ data: Data, offset: Int = 0) -> Int32 {
        guard offset + 3 <= data.count else { return 0 }
        var value: Int32 = Int32(data[offset]) << 16
                         | Int32(data[offset + 1]) << 8
                         | Int32(data[offset + 2])
        // Sign extend if negative (bit 23 is set)
        if value & 0x800000 != 0 {
            value |= Int32(bitPattern: 0xFF000000)
        }
        return value
    }

    /// Reads a 16-bit unsigned integer (Big-Endian).
    private static func readUInt16BE(_ data: Data, offset: Int = 0) -> UInt16 {
        guard offset + 2 <= data.count else { return 0 }
        return UInt16(data[offset]) << 8 | UInt16(data[offset + 1])
    }

    /// Reads a 32-bit unsigned integer (Big-Endian).
    private static func readUInt32BE(_ data: Data, offset: Int = 0) -> UInt32 {
        guard offset + 4 <= data.count else { return 0 }
        return UInt32(data[offset]) << 24 | UInt32(data[offset + 1]) << 16
             | UInt32(data[offset + 2]) << 8 | UInt32(data[offset + 3])
    }
}

// MARK: - Neighbours Parser

/// Specialized parser for remote node neighbour lists.
enum NeighboursParser {
    /// Parses Neighbours response data from binary protocol.
    ///
    /// - Parameters:
    ///   - data: Raw response data.
    ///   - publicKeyPrefix: Target node's public key prefix.
    ///   - tag: Request tag.
    ///   - prefixLength: Expected length of neighbour pubkey prefixes (default 4).
    /// - Returns: A ``NeighboursResponse`` containing the parsed list.
    ///
    /// ### Binary Format
    /// - Offset 0 (2 bytes): Total neighbours count (Int16 LE)
    /// - Offset 2 (2 bytes): Results count in this response (Int16 LE)
    /// - Entries: `[prefix:N][secs_ago:4][snr:1]` where N = `prefixLength`.
    static func parse(
        _ data: Data,
        publicKeyPrefix: Data,
        tag: Data,
        prefixLength: Int = 4
    ) -> NeighboursResponse {
        guard data.count >= 4 else {
            return NeighboursResponse(
                publicKeyPrefix: publicKeyPrefix,
                tag: tag,
                totalCount: 0,
                neighbours: []
            )
        }

        let totalCount = Int(data.readInt16LE(at: 0))
        let resultsCount = Int(data.readInt16LE(at: 2))

        var neighbours: [Neighbour] = []
        let entrySize = prefixLength + 4 + 1 // pubkey + secs_ago + snr
        var offset = 4

        for _ in 0..<resultsCount {
            guard offset + entrySize <= data.count else { break }

            let keyPrefix = Data(data[offset..<(offset + prefixLength)])
            offset += prefixLength

            let secondsAgo = Int(data.readInt32LE(at: offset))
            offset += 4

            let snr = Double(Int8(bitPattern: data[offset])) / 4.0
            offset += 1

            neighbours.append(Neighbour(
                publicKeyPrefix: keyPrefix,
                secondsAgo: secondsAgo,
                snr: snr
            ))
        }

        return NeighboursResponse(
            publicKeyPrefix: publicKeyPrefix,
            tag: tag,
            totalCount: totalCount,
            neighbours: neighbours
        )
    }
}

// MARK: - Regions Parser

enum RegionsParser {
    /// Parses a region query response from binary response data.
    ///
    /// The response data layout (after the binary response parser strips the frame header):
    /// - Offset 0–3 (4 bytes): Repeater timestamp (UInt32 LE) — skipped
    /// - Offset 4+ (variable): Comma-separated UTF-8 region names
    ///
    /// The sender timestamp (4 bytes) is already consumed as the tag by the binary response parser.
    ///
    /// - Parameter responseData: The `data` field from `.binaryResponse(tag:data:)`.
    /// - Returns: An array of region name strings. Empty array if no regions are configured.
    /// - Throws: ``MeshCoreError/parseError(_:)`` if the response is too short or not valid UTF-8.
    static func parse(_ responseData: Data) throws -> [String] {
        guard responseData.count >= 4 else {
            throw MeshCoreError.parseError("Region response too short (\(responseData.count) bytes)")
        }
        let regionData = responseData.dropFirst(4)
        guard let regionString = String(data: regionData, encoding: .utf8) else {
            throw MeshCoreError.parseError("Invalid UTF-8 in region response")
        }
        let trimmed = regionString.trimmingCharacters(in: .controlCharacters)
        if trimmed.isEmpty { return [] }
        return trimmed.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && $0 != "*" }
    }
}
