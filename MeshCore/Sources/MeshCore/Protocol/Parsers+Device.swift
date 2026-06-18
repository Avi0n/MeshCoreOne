import Foundation

extension Parsers {

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
}
