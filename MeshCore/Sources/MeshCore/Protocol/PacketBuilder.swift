import Foundation

/// Stateless packet builder for constructing MeshCore protocol commands.
///
/// `PacketBuilder` provides static methods to construct the binary command packets
/// sent to a MeshCore device. Each method returns a `Data` object ready to send
/// via the transport layer.
///
/// ## Usage
///
/// These methods are typically called internally by ``MeshCoreSession``, but can be
/// used directly for low-level protocol access:
///
/// ```swift
/// // Build an appStart command
/// let packet = PacketBuilder.appStart(clientId: "MyApp")
///
/// // Build a message command
/// let message = PacketBuilder.sendMessage(
///     to: contactPublicKey,
///     text: "Hello, mesh!",
///     timestamp: Date()
/// )
///
/// // Send via transport
/// try await transport.send(packet)
/// ```
///
/// ## Protocol Format
///
/// All commands follow the format:
/// - Byte 0: Command code (see ``CommandCode``)
/// - Bytes 1+: Command-specific payload
///
/// Multi-byte integers are little-endian. Strings are UTF-8 encoded.
public enum PacketBuilder: Sendable {

    /// Size of a public key in bytes.
    static let publicKeySize = 32
    /// Size of an expanded private key in bytes (firmware `PRV_KEY_SIZE`).
    static let privateKeySize = 64
    static let rawDataMaxPathBytes = 64
    static let rawDataMaxPayloadBytes = 184
    /// Flood sentinel for `path_len` fields: firmware treats `0xFF` as "route via flood".
    public static let floodPathSentinel: UInt8 = 0xFF
    /// Maximum payload bytes for `CMD_SEND_CHANNEL_DATA` (`MAX_FRAME_SIZE - 9` per firmware).
    static let channelDataMaxPayloadBytes = 163
    /// Default-scope name-field width on the wire (31 bytes, zero-padded).
    static let defaultScopeNameField = 31
    /// Default-scope name maximum UTF-8 length (30 bytes; byte 31 is the null terminator
    /// firmware's `strlen` relies on at `MyMesh.cpp:1895`).
    static let defaultScopeMaxNameBytes = 30
    /// Default-scope key width on the wire (16 bytes).
    static let defaultScopeKeyBytes = 16
    /// Fixed-point scale firmware applies to latitude/longitude (degrees × 1e6, stored as Int32).
    static let coordinateScale: Double = 1_000_000
    /// Valid latitude range in degrees.
    public static let latitudeRange: ClosedRange<Double> = -90...90
    /// Valid longitude range in degrees.
    public static let longitudeRange: ClosedRange<Double> = -180...180
    /// Valid radio frequency range in kHz, matching firmware `CMD_SET_RADIO_PARAMS`.
    public static let frequencyRangeKHz: ClosedRange<UInt32> = 150_000...2_500_000
    /// Valid radio bandwidth range in Hz, matching firmware `CMD_SET_RADIO_PARAMS`.
    public static let bandwidthRangeHz: ClosedRange<UInt32> = 7_000...500_000
    /// Valid LoRa spreading factor range, matching firmware `CMD_SET_RADIO_PARAMS`.
    public static let spreadingFactorRange: ClosedRange<UInt8> = 5...12
    /// Valid LoRa coding rate range, matching firmware `CMD_SET_RADIO_PARAMS`.
    public static let codingRateRange: ClosedRange<UInt8> = 5...8
    /// Minimum LoRa transmit power in dBm (firmware rejects below this). The upper bound is the
    /// device-reported `maxTxPower`, not a fixed constant, so it is supplied per device.
    public static let txPowerFloor: Int8 = -9

    private static func encodePublicKey(_ publicKey: Data) -> Data {
        if publicKey.count >= publicKeySize {
            return publicKey.prefix(publicKeySize)
        }
        return publicKey + Data(repeating: 0, count: publicKeySize - publicKey.count)
    }

    /// Scales a coordinate (degrees) into the firmware's `Int32` fixed-point form,
    /// clamping to a finite, valid range. A NaN, infinite, or out-of-range value
    /// saturates instead of trapping `Int32(_:)`, so no caller can crash the encoder.
    static func scaledCoordinate(_ degrees: Double, in range: ClosedRange<Double>) -> Int32 {
        let clamped = degrees.isFinite ? min(max(degrees, range.lowerBound), range.upperBound) : 0
        return Int32(clamped * coordinateScale)
    }

    /// Clamps a date to the firmware's unsigned 32-bit seconds-since-epoch field,
    /// saturating pre-1970 and post-2106 dates instead of trapping `UInt32(_:)`.
    static func epochSeconds32(_ date: Date) -> UInt32 {
        let seconds = date.timeIntervalSince1970
        guard seconds.isFinite, seconds > 0 else { return 0 }
        guard seconds < Double(UInt32.max) else { return UInt32.max }
        return UInt32(seconds)
    }

    // MARK: - Device Commands

    /// Builds an appStart command to initialize the session.
    ///
    /// - Parameter clientId: Client identifier string (max 5 characters, will be truncated).
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// (Per firmware MyMesh.cpp:842-845)
    /// - Offset 0 (1 byte): Command code `0x01` (appStart)
    /// - Offset 1 (1 byte): Protocol version marker `0x03`
    /// - Offset 2 (6 bytes): Reserved padding (ASCII spaces `0x20`)
    /// - Offset 8 (N bytes): Truncated Client ID (UTF-8)
    public static func appStart(clientId: String = "MCore") -> Data {
        var data = Data([CommandCode.appStart.rawValue, 0x03])
        // Add 6 reserved bytes (spaces) per Python reference device.py:15
        data.append(contentsOf: [0x20, 0x20, 0x20, 0x20, 0x20, 0x20])
        // Client ID: 5 chars max (firmware reads from byte 8, limited display space)
        let truncatedId = String(clientId.prefix(5))
        data.append(truncatedId.data(using: .utf8) ?? Data())
        return data
    }

    /// Builds a deviceQuery command to request device capabilities.
    ///
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x16` (deviceQuery)
    /// - Offset 1 (1 byte): Constant `0x03`
    public static func deviceQuery() -> Data {
        Data([CommandCode.deviceQuery.rawValue, 0x03])
    }

    /// Builds a getBattery command to request battery level and storage info.
    ///
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x14` (getBattery)
    public static func getBattery() -> Data {
        Data([CommandCode.getBattery.rawValue])
    }

    /// Builds a getTime command to request the device's current time.
    ///
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x05` (getTime)
    public static func getTime() -> Data {
        Data([CommandCode.getTime.rawValue])
    }

    /// Builds a setTime command to set the device's clock.
    ///
    /// - Parameter date: The date to set.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x06` (setTime)
    /// - Offset 1 (4 bytes): Unix timestamp (seconds), Little-endian UInt32
    public static func setTime(_ date: Date) -> Data {
        var data = Data([CommandCode.setTime.rawValue])
        let timestamp = UInt32(date.timeIntervalSince1970)
        data.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Array($0) })
        return data
    }

    /// Builds a setName command to set the advertised device name.
    ///
    /// - Parameter name: The new name for the device.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x08` (setName)
    /// - Offset 1 (N bytes): Name string (UTF-8 encoded)
    public static func setName(_ name: String) -> Data {
        var data = Data([CommandCode.setName.rawValue])
        // Firmware uses char[32]; truncate to 31 bytes to leave room for null terminator
        let truncated = name.utf8Prefix(maxBytes: 31)
        data.append(truncated.data(using: .utf8) ?? Data())
        return data
    }

    /// Builds a setCoordinates command to set the device's GPS position.
    ///
    /// - Parameters:
    ///   - latitude: Latitude in degrees.
    ///   - longitude: Longitude in degrees.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x0E` (setCoordinates)
    /// - Offset 1 (4 bytes): Latitude scaled by 1,000,000, Little-endian Int32
    /// - Offset 5 (4 bytes): Longitude scaled by 1,000,000, Little-endian Int32
    /// - Offset 9 (4 bytes): Altitude placeholder (zeros)
    public static func setCoordinates(latitude: Double, longitude: Double) -> Data {
        var data = Data([CommandCode.setCoordinates.rawValue])
        let lat = scaledCoordinate(latitude, in: latitudeRange)
        let lon = scaledCoordinate(longitude, in: longitudeRange)
        data.append(contentsOf: withUnsafeBytes(of: lat.littleEndian) { Array($0) })
        data.append(contentsOf: withUnsafeBytes(of: lon.littleEndian) { Array($0) })
        data.append(contentsOf: [0, 0, 0, 0]) // altitude placeholder
        return data
    }

    /// Builds a setTxPower command to set the radio transmission power.
    ///
    /// - Parameter power: Transmission power in dBm (range: -9 to 30).
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x0C` (setTxPower)
    /// - Offset 1 (1 byte): Power value, Int8
    public static func setTxPower(_ power: Int8) -> Data {
        Data([CommandCode.setTxPower.rawValue, UInt8(bitPattern: power)])
    }

    /// Builds a setRadio command to configure radio modulation parameters.
    ///
    /// - Parameters:
    ///   - frequency: Frequency in MHz.
    ///   - bandwidth: Bandwidth in kHz.
    ///   - spreadingFactor: LoRa spreading factor (6-12).
    ///   - codingRate: LoRa coding rate (5-8).
    ///   - clientRepeat: Whether to enable client repeat mode (v9+ firmware, omitted if nil).
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x0B` (setRadio)
    /// - Offset 1 (4 bytes): Frequency scaled by 1,000, Little-endian UInt32
    /// - Offset 5 (4 bytes): Bandwidth scaled by 1,000, Little-endian UInt32
    /// - Offset 9 (1 byte): Spreading Factor
    /// - Offset 10 (1 byte): Coding Rate
    /// - Offset 11 (1 byte, optional): Client repeat flag (v9+)
    public static func setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: UInt8,
        codingRate: UInt8,
        clientRepeat: Bool? = nil
    ) -> Data {
        var data = Data([CommandCode.setRadio.rawValue])
        let freq = UInt32(frequency * 1000)
        let bw = UInt32(bandwidth * 1000)
        data.append(contentsOf: withUnsafeBytes(of: freq.littleEndian) { Array($0) })
        data.append(contentsOf: withUnsafeBytes(of: bw.littleEndian) { Array($0) })
        data.append(spreadingFactor)
        data.append(codingRate)
        if let clientRepeat {
            data.append(clientRepeat ? 1 : 0)
        }
        return data
    }

    /// Builds a getRepeatFreq command to request allowed client repeat frequency ranges (v9+).
    ///
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x3C` (getRepeatFreq)
    public static func getRepeatFreq() -> Data {
        Data([CommandCode.getRepeatFreq.rawValue])
    }

    /// Builds a sendAdvertisement command to broadcast device presence.
    ///
    /// - Parameter flood: Whether to flood the advertisement through the mesh.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x07` (sendAdvertisement)
    /// - Offset 1 (1 byte, optional): Flood flag (`0x01` if true, omitted if false)
    public static func sendAdvertisement(flood: Bool = false) -> Data {
        flood ? Data([CommandCode.sendAdvertisement.rawValue, 0x01]) : Data([CommandCode.sendAdvertisement.rawValue])
    }

    /// Builds a reboot command to restart the device.
    ///
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x13` (reboot)
    /// - Offset 1 (6 bytes): "reboot" string (UTF-8)
    public static func reboot() -> Data {
        var data = Data([CommandCode.reboot.rawValue])
        data.append("reboot".data(using: .utf8) ?? Data())
        return data
    }

    // MARK: - Contact Commands

    /// Builds a getContacts command to fetch the contact list.
    ///
    /// - Parameter lastModified: Optional timestamp to fetch only contacts modified since this date.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x04` (getContacts)
    /// - Offset 1 (4 bytes, optional): Last modified timestamp, Little-endian UInt32
    public static func getContacts(since lastModified: Date? = nil) -> Data {
        var data = Data([CommandCode.getContacts.rawValue])
        if let lastMod = lastModified {
            let timestamp = UInt32(lastMod.timeIntervalSince1970)
            data.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Array($0) })
        }
        return data
    }

    /// Builds a resetPath command to clear the routing path to a contact.
    ///
    /// - Parameter publicKey: The 32-byte public key of the contact.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x0D` (resetPath)
    /// - Offset 1 (32 bytes): Full public key
    public static func resetPath(publicKey: Data) -> Data {
        var data = Data([CommandCode.resetPath.rawValue])
        data.append(publicKey.prefix(publicKeySize))
        return data
    }

    /// Builds a removeContact command to delete a contact from the device.
    ///
    /// - Parameter publicKey: The 32-byte public key of the contact.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x0F` (removeContact)
    /// - Offset 1 (32 bytes): Full public key
    public static func removeContact(publicKey: Data) -> Data {
        var data = Data([CommandCode.removeContact.rawValue])
        data.append(publicKey.prefix(publicKeySize))
        return data
    }

    /// Builds a shareContact command to broadcast a contact's info.
    ///
    /// - Parameter publicKey: The 32-byte public key of the contact to share.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x10` (shareContact)
    /// - Offset 1 (32 bytes): Full public key
    public static func shareContact(publicKey: Data) -> Data {
        var data = Data([CommandCode.shareContact.rawValue])
        data.append(publicKey.prefix(publicKeySize))
        return data
    }

    /// Builds an exportContact command to generate a contact URI.
    ///
    /// - Parameter publicKey: Optional 32-byte public key. If nil, exports the local device's contact.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x11` (exportContact)
    /// - Offset 1 (32 bytes, optional): Full public key
    public static func exportContact(publicKey: Data? = nil) -> Data {
        var data = Data([CommandCode.exportContact.rawValue])
        if let key = publicKey {
            data.append(key.prefix(publicKeySize))
        }
        return data
    }

    // MARK: - Messaging Commands

    /// Builds a getMessage command to fetch the next pending message.
    ///
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x0A` (getMessage)
    public static func getMessage() -> Data {
        Data([CommandCode.getMessage.rawValue])
    }

    /// Builds a sendMessage command for direct messaging.
    ///
    /// - Parameters:
    ///   - destination: Destination public key (first 6 bytes used for prefix).
    ///   - text: Message text (UTF-8 encoded).
    ///   - timestamp: Message timestamp.
    ///   - attempt: Retry attempt number (for duplicate detection).
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x02` (sendMessage)
    /// - Offset 1 (1 byte): Message type `0x00` (text)
    /// - Offset 2 (1 byte): Retry attempt counter
    /// - Offset 3 (4 bytes): Unix timestamp (seconds), Little-endian UInt32
    /// - Offset 7 (6 bytes): Destination public key prefix
    /// - Offset 13 (N bytes): Message payload (UTF-8)
    public static func sendMessage(
        to destination: Data,
        text: String,
        timestamp: Date = Date(),
        attempt: UInt8 = 0
    ) -> Data {
        var data = Data([CommandCode.sendMessage.rawValue, 0x00, attempt])
        let ts = UInt32(timestamp.timeIntervalSince1970)
        data.append(contentsOf: withUnsafeBytes(of: ts.littleEndian) { Array($0) })
        data.append(destination.prefix(6))
        data.append(text.data(using: .utf8) ?? Data())
        return data
    }

    /// Builds a command packet for sending structured commands to remote nodes.
    ///
    /// - Parameters:
    ///   - destination: Destination public key prefix (6 bytes).
    ///   - command: Command string to execute.
    ///   - timestamp: Command timestamp.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x02` (sendMessage)
    /// - Offset 1 (1 byte): Message type `0x01` (structured command)
    /// - Offset 2 (1 byte): Reserved `0x00`
    /// - Offset 3 (4 bytes): Unix timestamp, Little-endian UInt32
    /// - Offset 7 (6 bytes): Destination prefix
    /// - Offset 13 (N bytes): Command payload (UTF-8)
    public static func sendCommand(
        to destination: Data,
        command: String,
        timestamp: Date = Date()
    ) -> Data {
        var data = Data([CommandCode.sendMessage.rawValue, 0x01, 0x00])
        let ts = UInt32(timestamp.timeIntervalSince1970)
        data.append(contentsOf: withUnsafeBytes(of: ts.littleEndian) { Array($0) })
        data.append(destination.prefix(6))
        data.append(command.data(using: .utf8) ?? Data())
        return data
    }

    /// Builds a sendChannelMessage command for broadcasting to a mesh channel.
    ///
    /// - Parameters:
    ///   - channel: The 0-based index of the channel.
    ///   - text: Message text (UTF-8 encoded).
    ///   - timestamp: Message timestamp.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x03` (sendChannelMessage)
    /// - Offset 1 (1 byte): Message type `0x00`
    /// - Offset 2 (1 byte): Channel index
    /// - Offset 3 (4 bytes): Unix timestamp, Little-endian UInt32
    /// - Offset 7 (N bytes): Message payload (UTF-8)
    public static func sendChannelMessage(
        channel: UInt8,
        text: String,
        timestamp: Date = Date()
    ) -> Data {
        var data = Data([CommandCode.sendChannelMessage.rawValue, 0x00, channel])
        let ts = UInt32(timestamp.timeIntervalSince1970)
        data.append(contentsOf: withUnsafeBytes(of: ts.littleEndian) { Array($0) })
        data.append(text.data(using: .utf8) ?? Data())
        return data
    }

    /// Builds a sendLogin command to authenticate with a remote node.
    ///
    /// - Parameters:
    ///   - destination: The 32-byte public key of the node to login to.
    ///   - password: The password for authentication.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x1A` (sendLogin)
    /// - Offset 1 (32 bytes): Full public key of target
    /// - Offset 33 (N bytes): Password (UTF-8)
    public static func sendLogin(to destination: Data, password: String) -> Data {
        var data = Data([CommandCode.sendLogin.rawValue])
        data.append(destination.prefix(publicKeySize))
        data.append(password.data(using: .utf8) ?? Data())
        return data
    }

    /// Builds a sendLogout command to end an authenticated session.
    ///
    /// - Parameter destination: The 32-byte public key of the node.
    /// - Returns: The command packet data.
    public static func sendLogout(to destination: Data) -> Data {
        var data = Data([CommandCode.sendLogout.rawValue])
        data.append(destination.prefix(publicKeySize))
        return data
    }

    /// Builds a sendStatusRequest command to query a remote node's status.
    ///
    /// - Parameter destination: The 32-byte public key of the node.
    /// - Returns: The command packet data.
    public static func sendStatusRequest(to destination: Data) -> Data {
        var data = Data([CommandCode.sendStatusRequest.rawValue])
        data.append(destination.prefix(publicKeySize))
        return data
    }

    // MARK: - Binary Protocol Commands

    /// Builds a binaryRequest command for specialized data requests.
    ///
    /// - Parameters:
    ///   - destination: The 32-byte public key of the target node.
    ///   - type: The type of binary request (e.g., MMA, neighbours).
    ///   - payload: Optional additional request data.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x32` (binaryRequest)
    /// - Offset 1 (32 bytes): Full public key
    /// - Offset 33 (1 byte): Request type code
    /// - Offset 34 (N bytes, optional): Payload
    public static func binaryRequest(
        to destination: Data,
        type: BinaryRequestType,
        payload: Data? = nil
    ) -> Data {
        var data = Data([CommandCode.binaryRequest.rawValue])
        data.append(destination.prefix(publicKeySize))
        data.append(type.rawValue)
        if let payload = payload {
            data.append(payload)
        }
        return data
    }

    // MARK: - Channel Commands

    /// Builds a getChannel command to fetch configuration for a specific channel.
    ///
    /// - Parameter index: The 0-based index of the channel.
    /// - Returns: The command packet data.
    public static func getChannel(index: UInt8) -> Data {
        Data([CommandCode.getChannel.rawValue, index])
    }

    /// Builds a setChannel command to configure a mesh channel.
    ///
    /// - Parameters:
    ///   - index: The 0-based index of the channel to configure.
    ///   - name: The name of the channel (max 32 bytes).
    ///   - secret: The 16-byte PSK (Pre-Shared Key) for the channel.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x20` (setChannel)
    /// - Offset 1 (1 byte): Channel index
    /// - Offset 2 (32 bytes): Padded channel name (UTF-8, zero-filled)
    /// - Offset 34 (16 bytes): PSK secret
    public static func setChannel(
        index: UInt8,
        name: String,
        secret: Data
    ) -> Data {
        var data = Data([CommandCode.setChannel.rawValue, index])

        // Pad name to 32 bytes (UTF-8-safe truncation)
        data.append(name.utf8PaddedOrTruncated(to: 32))

        // Secret must be 16 bytes
        data.append(secret.prefix(16))
        return data
    }

    // MARK: - Stats Commands

    /// Builds a command to fetch core system statistics.
    ///
    /// - Returns: The command packet data.
    public static func getStatsCore() -> Data {
        Data([CommandCode.getStats.rawValue, StatsType.core.rawValue])
    }

    /// Builds a command to fetch radio performance statistics.
    ///
    /// - Returns: The command packet data.
    public static func getStatsRadio() -> Data {
        Data([CommandCode.getStats.rawValue, StatsType.radio.rawValue])
    }

    /// Builds a command to fetch packet counters.
    ///
    /// - Returns: The command packet data.
    public static func getStatsPackets() -> Data {
        Data([CommandCode.getStats.rawValue, StatsType.packets.rawValue])
    }

    // MARK: - Additional Commands (from Python reference)

    /// Builds an updateContact command to sync a full contact record to firmware.
    ///
    /// - Parameter contact: The contact to update.
    /// - Returns: The command packet data (147 bytes).
    ///
    /// ### Binary Format
    /// (Per firmware MyMesh.cpp updateContactFromFrame and Python meshcore_py)
    /// - Offset 0 (1 byte): Command code `0x09`
    /// - Offset 1 (32 bytes): Public key
    /// - Offset 33 (1 byte): Type
    /// - Offset 34 (1 byte): Flags
    /// - Offset 35 (1 byte): Out path length (encoded: upper 2 bits = hash mode, lower 6 bits = hop count; 0xFF = flood)
    /// - Offset 36 (64 bytes): Out path (zero-padded)
    /// - Offset 100 (32 bytes): Advertised name (UTF-8, zero-padded)
    /// - Offset 132 (4 bytes): Last advert timestamp (UInt32 LE)
    /// - Offset 136 (4 bytes): Latitude (Int32 LE, scaled by 1,000,000)
    /// - Offset 140 (4 bytes): Longitude (Int32 LE, scaled by 1,000,000)
    /// - Offset 144 (3 bytes): Reserved/padding (zeros)
    ///
    /// Total: 147 bytes
    public static func updateContact(_ contact: MeshContact) -> Data {
        var data = Data([CommandCode.updateContact.rawValue])              // 1 byte
        data.append(contact.publicKey.paddedOrTruncated(to: 32))           // 32 bytes
        data.append(contact.typeRawValue)                                    // 1 byte
        data.append(contact.flags.rawValue)                                  // 1 byte
        data.append(contact.outPathLength)                                    // 1 byte
        data.append(contact.outPath.paddedOrTruncated(to: 64))              // 64 bytes
        data.append(contact.advertisedName.utf8PaddedOrTruncated(to: 32))   // 32 bytes

        let timestamp = epochSeconds32(contact.lastAdvertisement)
        data.appendLittleEndian(timestamp)                                  // 4 bytes

        let lat = scaledCoordinate(contact.latitude, in: latitudeRange)
        data.appendLittleEndian(lat)                                        // 4 bytes

        let lon = scaledCoordinate(contact.longitude, in: longitudeRange)
        data.appendLittleEndian(lon)                                        // 4 bytes

        // Subtotal so far: 1 + 32 + 1 + 1 + 1 + 64 + 32 + 4 + 4 + 4 = 144 bytes.
        // Firmware expects a 147-byte frame, so pad with 3 reserved bytes.
        data.append(contentsOf: [0x00, 0x00, 0x00])                         // 3 bytes

        return data  // 144 + 3 = 147 bytes
    }

    /// Builds a setTuning command to adjust low-level radio timing.
    ///
    /// - Parameters:
    ///   - rxDelay: Receive delay in microseconds.
    ///   - af: Automatic frequency correction parameter.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x15` (setTuning)
    /// - Offset 1 (4 bytes): rxDelay, Little-endian UInt32
    /// - Offset 5 (4 bytes): af, Little-endian UInt32
    /// - Offset 9 (2 bytes): Reserved padding (zeros)
    public static func setTuning(rxDelay: UInt32, af: UInt32) -> Data {
        var data = Data([CommandCode.setTuning.rawValue])
        data.append(contentsOf: withUnsafeBytes(of: rxDelay.littleEndian) { Array($0) })
        data.append(contentsOf: withUnsafeBytes(of: af.littleEndian) { Array($0) })
        data.append(contentsOf: [0, 0])  // 2 reserved bytes
        return data
    }

    /// Builds a setOtherParams command for various system configurations.
    ///
    /// - Parameters:
    ///   - manualAddContacts: Whether to allow manual contact addition.
    ///   - telemetryModeEnvironment: Environment telemetry mode (0-3).
    ///   - telemetryModeLocation: Location telemetry mode (0-3).
    ///   - telemetryModeBase: Base telemetry mode (0-3).
    ///   - advertisementLocationPolicy: Location advertisement policy.
    ///   - multiAcks: Optional multi-ACK configuration (newer firmware).
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// (Per Python device.py:95-128)
    /// - Offset 0 (1 byte): Command code `0x26` (setOtherParams)
    /// - Offset 1 (1 byte): Manual contact add flag (0/1)
    /// - Offset 2 (1 byte): Combined telemetry mode (Env:2 | Loc:2 | Base:2)
    /// - Offset 3 (1 byte): Advertisement location policy
    /// - Offset 4 (1 byte, optional): Multi-ACKs flag
    public static func setOtherParams(
        manualAddContacts: Bool,
        telemetryModeEnvironment: UInt8,
        telemetryModeLocation: UInt8,
        telemetryModeBase: UInt8,
        advertisementLocationPolicy: UInt8,
        multiAcks: UInt8? = nil
    ) -> Data {
        var data = Data([CommandCode.setOtherParams.rawValue])
        data.append(manualAddContacts ? 1 : 0)
        // Combine telemetry modes into single byte: env(2) | loc(2) | base(2)
        let telemetryMode = ((telemetryModeEnvironment & 0b11) << 4) |
                           ((telemetryModeLocation & 0b11) << 2) |
                           (telemetryModeBase & 0b11)
        data.append(telemetryMode)
        data.append(advertisementLocationPolicy)
        if let multiAcks = multiAcks {
            data.append(multiAcks)
        }
        return data
    }

    /// Builds a packet to get the auto-add configuration.
    public static func getAutoAddConfig() -> Data {
        Data([CommandCode.getAutoAddConfig.rawValue])
    }

    /// Builds a packet to set the auto-add configuration.
    /// - Parameter config: The auto-add configuration (bitmask + max hops).
    public static func setAutoAddConfig(_ config: AutoAddConfig) -> Data {
        Data([CommandCode.setAutoAddConfig.rawValue, config.bitmask, config.maxHops])
    }

    /// Builds a getSelfTelemetry command to request current sensor data from the device.
    ///
    /// - Parameter destination: Optional 32-byte public key to send telemetry to.
    /// - Returns: The command packet data.
    public static func getSelfTelemetry(destination: Data? = nil) -> Data {
        var data = Data([CommandCode.getSelfTelemetry.rawValue, 0x00, 0x00, 0x00])
        if let dest = destination {
            data.append(dest.prefix(publicKeySize))
        }
        return data
    }

    // MARK: - Security Commands

    /// Builds a setDevicePin command to set the BLE pairing PIN.
    ///
    /// - Parameter pin: The 6-digit PIN code.
    /// - Returns: The command packet data.
    public static func setDevicePin(_ pin: UInt32) -> Data {
        var data = Data([CommandCode.setDevicePin.rawValue])
        data.append(contentsOf: withUnsafeBytes(of: pin.littleEndian) { Array($0) })
        return data
    }

    /// Builds a getCustomVars command to fetch user-defined variables.
    ///
    /// - Returns: The command packet data.
    public static func getCustomVars() -> Data {
        Data([CommandCode.getCustomVars.rawValue])
    }

    /// Builds a setCustomVar command to set a user-defined key-value pair.
    ///
    /// - Parameters:
    ///   - key: The variable name.
    ///   - value: The variable value.
    /// - Returns: The command packet data.
    public static func setCustomVar(key: String, value: String) -> Data {
        var data = Data([CommandCode.setCustomVar.rawValue])
        data.append((key + ":" + value).data(using: .utf8) ?? Data())
        return data
    }

    /// Builds an exportPrivateKey command to retrieve the device's private key.
    ///
    /// - Returns: The command packet data.
    public static func exportPrivateKey() -> Data {
        Data([CommandCode.exportPrivateKey.rawValue])
    }

    /// Builds an importPrivateKey command to set the device's private key.
    ///
    /// - Parameter key: The private key data.
    /// - Returns: The command packet data.
    public static func importPrivateKey(_ key: Data) -> Data {
        var data = Data([CommandCode.importPrivateKey.rawValue])
        data.append(key)
        return data
    }

    // MARK: - Signing Commands

    /// Builds a signStart command to begin a cryptographic signing session.
    ///
    /// - Returns: The command packet data.
    public static func signStart() -> Data {
        Data([CommandCode.signStart.rawValue])
    }

    /// Builds a signData command to append a chunk of data for signing.
    ///
    /// - Parameter chunk: The data chunk to sign.
    /// - Returns: The command packet data.
    public static func signData(_ chunk: Data) -> Data {
        var data = Data([CommandCode.signData.rawValue])
        data.append(chunk)
        return data
    }

    /// Builds a signFinish command to finalize signing and receive the signature.
    ///
    /// - Returns: The command packet data.
    public static func signFinish() -> Data {
        Data([CommandCode.signFinish.rawValue])
    }

    // MARK: - Path Discovery Commands

    /// Builds a sendPathDiscovery command to initiate route finding to a destination.
    ///
    /// - Parameter destination: The 32-byte public key of the target node.
    /// - Returns: The command packet data.
    public static func sendPathDiscovery(to destination: Data) -> Data {
        var data = Data([CommandCode.pathDiscovery.rawValue, 0x00])
        data.append(destination.prefix(publicKeySize))
        return data
    }

    /// Builds a sendTrace command to test packet routing and signal strength.
    ///
    /// - Parameters:
    ///   - tag: 32-bit identifier for this trace (used to match response).
    ///   - authCode: 32-bit authentication code for secure tracing.
    ///   - flags: 8-bit flags field for trace configuration.
    ///   - path: Optional sequence of repeater pubkey hashes for source routing.
    /// - Returns: The command packet data.
    public static func sendTrace(
        tag: UInt32,
        authCode: UInt32,
        flags: UInt8,
        path: Data? = nil
    ) -> Data {
        var data = Data([CommandCode.sendTrace.rawValue])
        data.append(contentsOf: withUnsafeBytes(of: tag.littleEndian) { Array($0) })
        data.append(contentsOf: withUnsafeBytes(of: authCode.littleEndian) { Array($0) })
        data.append(flags)
        if let path = path {
            data.append(path)
        }
        return data
    }

    /// Builds a setFloodScope command to restrict message routing to a specific group.
    ///
    /// - Parameter scopeKey: 16-byte scope key (or zeros to disable scope).
    /// - Returns: The command packet data.
    public static func setFloodScope(_ scopeKey: Data) -> Data {
        var data = Data([CommandCode.setFloodScope.rawValue, 0x00])
        data.append(scopeKey.prefix(16))
        return data
    }

    /// Builds a setFloodScope command that forces un-scoped flood broadcasts,
    /// overriding any persisted default flood scope on the device.
    ///
    /// Unlike ``setFloodScope(_:)`` (sub-command 0, which resets the session scope
    /// and falls back to the device default), this emits sub-command 1, which sets
    /// the firmware `send_unscoped` flag. Requires firmware ver 12+; older firmware has
    /// no handler for sub-command 1 and rejects the frame with `ERR_CODE_UNSUPPORTED_CMD`,
    /// so callers must gate on the reported firmware version. No scope key follows the
    /// sub-command byte.
    ///
    /// - Returns: The command packet data.
    public static func setFloodScopeUnscoped() -> Data {
        Data([CommandCode.setFloodScope.rawValue, 0x01])
    }

    /// Builds a `sendChannelData` command for sending a binary datagram to a channel.
    ///
    /// Requires firmware v11+ (MeshCore v1.15.0+).
    ///
    /// Wire format:
    /// - Offset 0 (1 byte): Command code `0x3E`
    /// - Offset 1 (1 byte): Channel index (0–255)
    /// - Offset 2 (1 byte): Encoded `pathLength` byte (`0xFF` = flood)
    /// - Offset 3 (pathBytes.count bytes, absent if flood): Path bytes, caller-supplied verbatim
    /// - Next (2 bytes): `dataType` (little-endian `UInt16`)
    /// - Remaining: Payload bytes (clamped to 163 bytes)
    ///
    /// `pathLength` is the **encoded** path-length byte used by firmware's packet format
    /// (see `Packet::isValidPathLen` at `MeshCore-references/MeshCore/src/Packet.cpp:13-18`):
    /// - Upper 2 bits (6–7): hash size mode (0 = 1 byte, 1 = 2 bytes, 2 = 3 bytes, 3 = reserved).
    /// - Lower 6 bits (0–5): hop count (0–63).
    /// - Special value `0xFF`: flood routing (no path bytes follow).
    ///
    /// The number of bytes consumed from `pathBytes` by firmware is
    /// `hash_count * hash_size` — NOT `pathBytes.count`. The builder passes both
    /// `pathLength` and `pathBytes` through verbatim; callers are responsible for
    /// keeping them consistent. Callers that already have a ``MeshContact`` can pass
    /// `contact.outPathLength` and `contact.outPath` directly.
    ///
    /// - Note: Upstream `companion_protocol.md` §6 documents an incorrect wire format
    ///   (wrong field order, missing `path_len`) as of v1.15.0. `MyMesh.cpp` is canonical.
    ///
    /// - Parameters:
    ///   - channelIndex: The channel slot index.
    ///   - dataType: Application data-type namespace. At runtime, firmware rejects only
    ///     `0x0000` with `ERR_CODE_ILLEGAL_ARG` (see `MyMesh.cpp:1153-1154`). Values
    ///     `0x0001–0x00FF` are reserved by convention in `number_allocations.md` but are
    ///     not enforced by firmware; `0xFF00–0xFFFF` is the developer/testing namespace.
    ///     Custom apps should request an allocation in `0x0100–0xFEFF`.
    ///   - payload: Binary payload, clamped to ``channelDataMaxPayloadBytes`` (163).
    ///   - pathLength: Encoded `path_len` byte. Defaults to ``floodPathSentinel`` (`0xFF`).
    ///     When set to a non-flood value it must satisfy `Packet::isValidPathLen`.
    ///   - pathBytes: Path bytes, written verbatim after `pathLength`. Pass `Data()` (the
    ///     default) for flood; firmware ignores any bytes when `pathLength == 0xFF` so the
    ///     builder omits them.
    /// - Returns: The command packet bytes.
    public static func sendChannelData(
        channelIndex: UInt8,
        dataType: UInt16,
        payload: Data,
        pathLength: UInt8 = floodPathSentinel,
        pathBytes: Data = Data()
    ) -> Data {
        var data = Data([CommandCode.sendChannelData.rawValue, channelIndex, pathLength])

        if pathLength != floodPathSentinel {
            data.append(pathBytes)
        }

        data.append(contentsOf: withUnsafeBytes(of: dataType.littleEndian) { Array($0) })
        data.append(payload.prefix(channelDataMaxPayloadBytes))
        return data
    }

    /// Builds a `setDefaultFloodScope` command to persist the device's default flood scope.
    ///
    /// The device uses this scope for flood sends when no session-scoped key has been set.
    /// Passing an empty name clears the persisted scope. Firmware's name-field parse at
    /// `MyMesh.cpp:1896` accepts only `0 < strlen(name) < 31`, so both empty and 31-plus-byte
    /// names are rejected with `ERR_CODE_ILLEGAL_ARG`. The builder defends against both:
    /// empty-name normalises to the single-byte clear form, and the 30-byte UTF-8 cap plus
    /// zero-padding to 31 guarantees `strlen < 31` for any non-empty input.
    ///
    /// Requires firmware v11+ (MeshCore v1.15.0+).
    ///
    /// Wire format:
    /// - `[0x3F]` (1 byte) to clear the default scope.
    /// - `[0x3F][name:31 zero-padded UTF-8][key:16]` (48 bytes) to set it.
    ///
    /// - Parameters:
    ///   - name: Display name. Encoded to at most 30 UTF-8 bytes; byte 31 is always zero so
    ///     firmware's `strlen`-based parse terminates. Truncation happens at grapheme-cluster
    ///     boundaries via ``Swift/String/utf8Prefix(maxBytes:)`` so invalid UTF-8 is never
    ///     emitted. Passing an empty name clears the scope.
    ///   - scopeKey: 16-byte scope key. Shorter keys are right-padded with zeros.
    /// - Returns: The command packet bytes.
    public static func setDefaultFloodScope(name: String, scopeKey: Data) -> Data {
        // Firmware rejects n == 0 — normalise empty name to clear, ignoring any key.
        if name.isEmpty {
            return Data([CommandCode.setDefaultFloodScope.rawValue])
        }

        var data = Data([CommandCode.setDefaultFloodScope.rawValue])

        // Cap UTF-8 at 30 bytes (grapheme-cluster safe) then zero-pad to 31. Zero-padding
        // guarantees at least one null byte in the 31-byte field for firmware's strlen.
        let truncated = name.utf8Prefix(maxBytes: defaultScopeMaxNameBytes)
        data.append(Data(truncated.utf8).paddedOrTruncated(to: defaultScopeNameField))

        data.append(scopeKey.paddedOrTruncated(to: defaultScopeKeyBytes))

        return data
    }

    /// Convenience overload that derives the 16-byte key from a ``FloodScope``.
    ///
    /// - Parameters:
    ///   - name: Display name for the scope (stored on-device).
    ///   - scope: Any ``FloodScope`` case; `.disabled` clears the scope.
    /// - Returns: The command packet bytes.
    public static func setDefaultFloodScope(name: String, scope: FloodScope) -> Data {
        if case .disabled = scope {
            return setDefaultFloodScope(name: "", scopeKey: Data())
        }
        return setDefaultFloodScope(name: name, scopeKey: scope.scopeKey())
    }

    /// Builds a getDefaultFloodScope command.
    ///
    /// Requires firmware v11+ (MeshCore v1.15.0+).
    ///
    /// - Returns: A single-byte command packet.
    public static func getDefaultFloodScope() -> Data {
        Data([CommandCode.getDefaultFloodScope.rawValue])
    }

    /// Builds a command to send an anonymous request to a remote node.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x39`
    /// - Offset 1–32 (32 bytes): Destination public key
    /// - Offset 33 (1 byte): Anonymous request type
    /// - Offset 34 (1 byte): Encoded path length (bits 7-6 = hash_size-1, bits 5-0 = hop count)
    /// - Offset 35+ (variable): Out-path bytes, reversed to form the return route
    ///
    /// - Parameters:
    ///   - publicKey: The 32-byte public key of the destination node.
    ///   - type: The anonymous request type.
    ///   - pathLength: The encoded path length byte.
    ///   - path: The raw out-path bytes for the destination. Reversed to form the return route.
    /// - Returns: The command data to send to the companion radio.
    public static func sendAnonReq(
        to publicKey: Data,
        type: AnonRequestType,
        pathLength: UInt8,
        path: Data
    ) -> Data {
        var data = Data([CommandCode.sendAnonReq.rawValue])
        data.append(publicKey.prefix(publicKeySize))
        data.append(type.rawValue)
        data.append(pathLength)
        data.append(Data(path.reversed()))
        return data
    }

    /// Builds a setPathHashMode command to configure the path hash size.
    ///
    /// - Parameter mode: Hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes).
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x3D`
    /// - Offset 1 (1 byte): Reserved `0x00`
    /// - Offset 2 (1 byte): Mode value (0, 1, or 2)
    public static func setPathHashMode(_ mode: UInt8) -> Data {
        Data([CommandCode.setPathHashMode.rawValue, 0x00, min(mode, UInt8(PathEncoding.maxPathHashMode))])
    }

    /// Builds a factoryReset command to wipe all settings and data from the device.
    ///
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// (Per firmware MyMesh.cpp - requires guard string)
    /// - Offset 0 (1 byte): Command code `0x33`
    /// - Offset 1 (5 bytes): Guard string "reset" (UTF-8)
    public static func factoryReset() -> Data {
        var data = Data([CommandCode.factoryReset.rawValue])
        data.append(contentsOf: "reset".utf8)
        return data
    }

    // MARK: - Control Data Commands

    /// Builds a generic sendControlData command for protocol-level signalling.
    ///
    /// - Parameters:
    ///   - type: The control data type code.
    ///   - payload: The data payload.
    /// - Returns: The command packet data.
    public static func sendControlData(type: UInt8, payload: Data) -> Data {
        var data = Data([CommandCode.sendControlData.rawValue, type])
        data.append(payload)
        return data
    }

    /// Builds a nodeDiscoverRequest command to find active nodes in the mesh.
    ///
    /// - Parameters:
    ///   - filter: Filter criteria for discovery.
    ///   - prefixOnly: Whether to return only public key prefixes (saves bandwidth).
    ///   - tag: Optional 32-bit tag. If nil, a random tag is generated.
    ///   - since: Optional timestamp to fetch nodes seen since this time.
    /// - Returns: The command packet data.
    public static func sendNodeDiscoverRequest(
        filter: UInt8,
        prefixOnly: Bool = true,
        tag: UInt32? = nil,
        since: UInt32? = nil
    ) -> Data {
        let actualTag = tag ?? UInt32.random(in: 1...UInt32.max)
        let flags: UInt8 = prefixOnly ? 1 : 0
        let controlType = ControlType.nodeDiscoverRequest.rawValue | flags

        var data = Data([CommandCode.sendControlData.rawValue, controlType])
        data.append(filter)
        data.append(contentsOf: withUnsafeBytes(of: actualTag.littleEndian) { Array($0) })
        if let since = since {
            data.append(contentsOf: withUnsafeBytes(of: since.littleEndian) { Array($0) })
        }
        return data
    }

    // MARK: - Additional Commands (Issue #1)

    /// Builds a sendRawData command to send raw data through the mesh.
    ///
    /// - Parameters:
    ///   - path: The routing path data.
    ///   - payload: The raw payload to send.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x19`
    /// - Offset 1 (1 byte): Path length
    /// - Offset 2 (N bytes): Path data
    /// - Offset 2+N (M bytes): Payload
    public static func sendRawData(path: Data, payload: Data) -> Data {
        var data = Data([CommandCode.sendRawData.rawValue])
        let clampedPath = path.prefix(rawDataMaxPathBytes)
        data.append(UInt8(clampedPath.count))
        data.append(clampedPath)
        data.append(payload.prefix(rawDataMaxPayloadBytes))
        return data
    }

    /// Builds a hasConnection command to check if a connection exists to a node.
    ///
    /// - Parameter publicKey: The 32-byte public key of the node.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x1C`
    /// - Offset 1 (32 bytes): Full public key
    public static func hasConnection(publicKey: Data) -> Data {
        var data = Data([CommandCode.hasConnection.rawValue])
        data.append(encodePublicKey(publicKey))
        return data
    }

    /// Builds a getContactByKey command to retrieve a contact by public key.
    ///
    /// - Parameter publicKey: The 32-byte public key of the contact.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x1E`
    /// - Offset 1 (32 bytes): Full public key
    public static func getContactByKey(publicKey: Data) -> Data {
        var data = Data([CommandCode.getContactByKey.rawValue])
        data.append(encodePublicKey(publicKey))
        return data
    }

    /// Builds a getAdvertPath command to retrieve the advertisement path to a contact.
    ///
    /// - Parameter publicKey: The 32-byte public key of the contact.
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x2A`
    /// - Offset 1 (1 byte): Reserved `0x00`
    /// - Offset 2 (32 bytes): Full public key
    public static func getAdvertPath(publicKey: Data) -> Data {
        var data = Data([CommandCode.getAdvertPath.rawValue, 0x00])
        data.append(encodePublicKey(publicKey))
        return data
    }

    /// Builds a getTuningParams command to retrieve radio tuning parameters.
    ///
    /// - Returns: The command packet data.
    ///
    /// ### Binary Format
    /// - Offset 0 (1 byte): Command code `0x2B`
    public static func getTuningParams() -> Data {
        Data([CommandCode.getTuningParams.rawValue])
    }
}
