import Foundation
import MeshCore
import OSLog

// MARK: - Node Config Service Errors

public enum NodeConfigServiceError: Error, LocalizedError, Sendable {
    case invalidChannelSecret(index: Int, hexLength: Int)
    case invalidContactPublicKey(name: String)
    case invalidPathHashMode(name: String, mode: UInt8)
    case invalidPrivateKey(hexLength: Int)
    case noAvailableChannelSlot(name: String)

    public var errorDescription: String? {
        switch self {
        case .invalidChannelSecret(let index, let hexLength):
            "Channel \(index) has invalid secret (\(hexLength) hex chars, expected 32)"
        case .invalidContactPublicKey(let name):
            "Contact \"\(name)\" has an invalid public key"
        case .invalidPathHashMode(let name, let mode):
            "Contact \"\(name)\" has unsupported path hash mode \(mode) (expected 0, 1, or 2)"
        case .invalidPrivateKey(let hexLength):
            "Invalid private key (\(hexLength) hex chars, expected \(ProtocolLimits.privateKeySize * 2))"
        case .noAvailableChannelSlot(let name):
            "No empty channel slot available for \"\(name)\""
        }
    }
}

// MARK: - Import Progress

/// Reports import progress to the UI.
public struct ImportProgress: Sendable {
    public let step: String
    public let current: Int
    public let total: Int
}

// MARK: - Node Config Service

/// Exports device configuration to `MeshCoreNodeConfig` and imports it back,
/// handling section filtering, other-params merging, and safe import ordering.
public actor NodeConfigService {
    private let session: MeshCoreSession
    private let settingsService: SettingsService
    private let channelService: ChannelService
    private let dataStore: any PersistenceStoreProtocol
    private weak var syncCoordinator: SyncCoordinator?
    private let logger = Logger(subsystem: "com.mc1", category: "NodeConfigService")

    public init(
        session: MeshCoreSession,
        settingsService: SettingsService,
        channelService: ChannelService,
        dataStore: any PersistenceStoreProtocol
    ) {
        self.session = session
        self.settingsService = settingsService
        self.channelService = channelService
        self.dataStore = dataStore
    }

    public func setSyncCoordinator(_ coordinator: SyncCoordinator) {
        self.syncCoordinator = coordinator
    }

    /// Whether a sync coordinator has been wired via `setSyncCoordinator`.
    var hasSyncCoordinatorWired: Bool { syncCoordinator != nil }

    // MARK: - Export

    /// Reads the device state and builds a `MeshCoreNodeConfig`.
    /// - Parameter sections: Which sections to include in the export.
    /// - Returns: A populated config struct.
    public func exportConfig(sections: ConfigSections) async throws -> MeshCoreNodeConfig {
        let selfInfo = try await settingsService.getSelfInfo()

        var config = MeshCoreNodeConfig()

        if sections.nodeIdentity {
            config.name = selfInfo.name
            config.publicKey = selfInfo.publicKey.hexString().lowercased()
            let privateKey = try await settingsService.exportPrivateKey()
            config.privateKey = privateKey.hexString().lowercased()
        }

        if sections.radioSettings {
            config.radioSettings = Self.buildRadioSettings(from: selfInfo)
        }

        if sections.positionSettings {
            config.positionSettings = MeshCoreNodeConfig.PositionSettings(
                latitude: String(selfInfo.latitude),
                longitude: String(selfInfo.longitude)
            )
        }

        if sections.otherSettings {
            config.otherSettings = Self.buildOtherSettings(from: selfInfo)
        }

        if sections.channels {
            let capabilities = try await settingsService.queryDevice()
            config.channels = try await exportChannels(maxChannels: UInt8(capabilities.maxChannels))
        }

        if sections.contacts {
            let meshContacts = try await session.getContacts(since: nil)
            config.contacts = meshContacts.map { Self.buildContactConfig(from: $0) }
        }

        return config
    }

    // MARK: - Import

    /// Writes a `MeshCoreNodeConfig` to the device in safe order (radio last).
    /// - Parameters:
    ///   - config: The config to import.
    ///   - sections: Which sections to actually apply.
    ///   - radioID: The connected device UUID (needed for channel writes).
    ///   - onProgress: Optional callback for UI progress updates.
    public func importConfig(
        _ config: MeshCoreNodeConfig,
        sections: ConfigSections,
        radioID: UUID,
        onProgress: (@Sendable (ImportProgress) -> Void)? = nil
    ) async throws {
        let totalSteps = countImportSteps(config: config, sections: sections)
        var currentStep = 0

        func progress(_ step: String) {
            currentStep += 1
            onProgress?(ImportProgress(step: step, current: currentStep, total: totalSteps))
        }

        func checkCancellation() throws {
            guard !Task.isCancelled else { throw CancellationError() }
        }

        // Steps 1-2: Node identity (private key + name)
        if sections.nodeIdentity {
            try await importIdentity(config, checkCancellation: checkCancellation, progress: progress)
        }

        // Step 3: Position
        if sections.positionSettings, let position = config.positionSettings {
            try checkCancellation()
            let lat = Double(position.latitude) ?? 0
            let lon = Double(position.longitude) ?? 0
            progress("Setting position")
            try await settingsService.setLocation(latitude: lat, longitude: lon)
            logger.info("Set position: \(lat), \(lon)")
        }

        // Step 4: Other params (merge with current device values)
        if sections.otherSettings, let other = config.otherSettings {
            try checkCancellation()
            progress("Setting other parameters")
            try await importOtherParams(other)
            logger.info("Set other params")
        }

        // Step 5: Channels
        if sections.channels, let channels = config.channels {
            try await importChannels(
                channels, radioID: radioID,
                checkCancellation: checkCancellation, progress: progress
            )
        }

        // Step 6: Contacts
        if sections.contacts, let contacts = config.contacts {
            try await importContacts(
                contacts, radioID: radioID,
                checkCancellation: checkCancellation, progress: progress
            )
            await syncCoordinator?.notifyContactsChanged()
        }
        // Step 7: Radio — LAST (minimizes mesh isolation on BLE disconnect)
        if sections.radioSettings, let radio = config.radioSettings {
            try await importRadio(radio, checkCancellation: checkCancellation, progress: progress)
        }
    }

    // MARK: - Internal Helpers

    /// Imports node identity (private key and name).
    private func importIdentity(
        _ config: MeshCoreNodeConfig,
        checkCancellation: () throws -> Void,
        progress: (String) -> Void
    ) async throws {
        if let privateKeyHex = config.privateKey,
           let privateKeyData = Data(hexString: privateKeyHex) {
            guard privateKeyData.count == ProtocolLimits.privateKeySize else {
                throw NodeConfigServiceError.invalidPrivateKey(hexLength: privateKeyHex.count)
            }
            try checkCancellation()
            progress("Importing private key")
            try await settingsService.importPrivateKey(privateKeyData)
            logger.info("Imported private key")
        }

        if let name = config.name {
            try checkCancellation()
            progress("Setting node name")
            try await settingsService.setNodeName(name)
            logger.info("Set node name: \(name)")
        }
    }

    /// Reads all configured channels from the device.
    private func exportChannels(maxChannels: UInt8) async throws -> [MeshCoreNodeConfig.ChannelConfig] {
        var channels: [MeshCoreNodeConfig.ChannelConfig] = []
        for index in 0..<maxChannels {
            let info = try await session.getChannel(index: index)
            guard ChannelService.isChannelConfigured(name: info.name, secret: info.secret) else {
                continue
            }
            channels.append(MeshCoreNodeConfig.ChannelConfig(
                name: info.name,
                secret: info.secret.hexString().lowercased()
            ))
        }
        return channels
    }

    /// Imports radio settings and TX power.
    private func importRadio(
        _ radio: MeshCoreNodeConfig.RadioSettings,
        checkCancellation: () throws -> Void,
        progress: (String) -> Void
    ) async throws {
        try checkCancellation()
        progress("Setting radio parameters")
        // bandwidthKHz parameter actually takes Hz (misnomer); pass directly.
        try await settingsService.setRadioParams(
            frequencyKHz: radio.frequency,
            bandwidthKHz: radio.bandwidth,
            spreadingFactor: radio.spreadingFactor,
            codingRate: radio.codingRate
        )
        logger.info("Set radio params")

        try checkCancellation()
        progress("Setting TX power")
        try await settingsService.setTxPower(radio.txPower)
        logger.info("Set TX power: \(radio.txPower)")
    }

    /// Imports channels using merge semantics: matches existing channels by name (hashtag)
    /// or secret (non-hashtag), updating in-place or adding to empty slots.
    private func importChannels(
        _ channels: [MeshCoreNodeConfig.ChannelConfig],
        radioID: UUID,
        checkCancellation: () throws -> Void,
        progress: (String) -> Void
    ) async throws {
        // Read current device channels to build lookup tables
        let capabilities = try await settingsService.queryDevice()
        let maxChannels = UInt8(capabilities.maxChannels)

        progress("Reading current channels")

        var hashtagNameToIndex: [String: UInt8] = [:]
        var secretToIndex: [String: UInt8] = [:]
        var emptyIndices: [UInt8] = []

        for index in 0 as UInt8..<maxChannels {
            let info = try await session.getChannel(index: index)
            if ChannelService.isChannelConfigured(name: info.name, secret: info.secret) {
                if info.name.hasPrefix("#") {
                    hashtagNameToIndex[info.name] = index
                } else {
                    secretToIndex[info.secret.hexString().lowercased()] = index
                }
            } else {
                emptyIndices.append(index)
            }
        }

        // Merge each imported channel
        for (i, channel) in channels.enumerated() {
            try checkCancellation()

            guard let secretData = Data(hexString: channel.secret),
                  secretData.count == ProtocolLimits.channelSecretSize else {
                throw NodeConfigServiceError.invalidChannelSecret(
                    index: i, hexLength: channel.secret.count
                )
            }

            let targetIndex: UInt8
            if channel.name.hasPrefix("#"), let existing = hashtagNameToIndex[channel.name] {
                targetIndex = existing
            } else if let existing = secretToIndex[channel.secret.lowercased()] {
                targetIndex = existing
            } else if let empty = emptyIndices.first {
                emptyIndices.removeFirst()
                targetIndex = empty
            } else {
                throw NodeConfigServiceError.noAvailableChannelSlot(name: channel.name)
            }

            progress("Importing channel: \(channel.name)")
            try await channelService.setChannelWithSecret(
                radioID: radioID,
                index: targetIndex,
                name: channel.name,
                secret: secretData
            )
            logger.info("Set channel \(targetIndex): \(channel.name)")
        }
    }

    /// Imports contacts to the device and local database, validating public keys.
    private func importContacts(
        _ contacts: [MeshCoreNodeConfig.ContactConfig],
        radioID: UUID,
        checkCancellation: () throws -> Void,
        progress: (String) -> Void
    ) async throws {
        for contact in contacts {
            try checkCancellation()
            progress("Importing contact: \(contact.name)")
            guard let publicKey = Data(hexString: contact.publicKey),
                  publicKey.count == ProtocolLimits.publicKeySize else {
                throw NodeConfigServiceError.invalidContactPublicKey(name: contact.name)
            }

            let outPath: Data
            let outPathLength: UInt8
            if let pathHex = contact.outPath, !pathHex.isEmpty,
               let pathData = Data(hexString: pathHex) {
                let mode = contact.pathHashMode ?? 0
                guard mode <= 2 else {
                    throw NodeConfigServiceError.invalidPathHashMode(name: contact.name, mode: mode)
                }
                let hashSize = Int(mode) + 1
                let hopCount = pathData.count / hashSize
                outPathLength = encodePathLen(hashSize: hashSize, hopCount: hopCount)
                outPath = pathData
            } else if contact.outPath != nil {
                // Direct contact: outPathLength must be 0 regardless of pathHashMode.
                outPath = Data()
                outPathLength = 0
            } else {
                outPath = Data()
                outPathLength = 0xFF
            }

            let meshContact = MeshContact(
                id: publicKey.hexString().lowercased(),
                publicKey: publicKey,
                type: ContactType(rawValue: contact.type) ?? .chat,
                flags: ContactFlags(rawValue: contact.flags),
                outPathLength: outPathLength,
                outPath: outPath,
                advertisedName: contact.name,
                lastAdvertisement: Date(timeIntervalSince1970: TimeInterval(contact.lastAdvert)),
                latitude: Double(contact.latitude) ?? 0,
                longitude: Double(contact.longitude) ?? 0,
                lastModified: Date(timeIntervalSince1970: TimeInterval(contact.lastModified))
            )
            try await session.addContact(meshContact)
            let frame = meshContact.toContactFrame()
            _ = try await dataStore.saveContact(radioID: radioID, from: frame)
            logger.info("Imported contact: \(contact.name)")
        }
    }

    /// Merges imported other-settings with current device values for fields not in the import.
    /// Note: `advertisementType` is exported for informational purposes but not imported here
    /// because `setOtherParams` does not accept it (firmware-managed field).
    private func importOtherParams(_ imported: MeshCoreNodeConfig.OtherSettings) async throws {
        let current = try await settingsService.getSelfInfo()

        let manualAdd = imported.manualAddContacts ?? (current.manualAddContacts ? 1 : 0)
        let advertPolicy = imported.advertLocationPolicy ?? current.advertisementLocationPolicy
        let telBase = imported.telemetryModeBase ?? current.telemetryModeBase
        let telLocation = imported.telemetryModeLocation ?? current.telemetryModeLocation
        let telEnvironment = imported.telemetryModeEnvironment ?? current.telemetryModeEnvironment
        let multiAcks = imported.multiAcks ?? current.multiAcks

        try await settingsService.setOtherParams(
            autoAddContacts: manualAdd == 0,
            telemetryModes: TelemetryModes(base: telBase, location: telLocation, environment: telEnvironment),
            advertLocationPolicy: AdvertLocationPolicy(rawValue: advertPolicy) ?? .none,
            multiAcks: multiAcks
        )
    }

    /// Counts the total import steps for progress reporting.
    private func countImportSteps(config: MeshCoreNodeConfig, sections: ConfigSections) -> Int {
        var count = 0
        if sections.nodeIdentity && config.privateKey != nil { count += 1 }
        if sections.nodeIdentity && config.name != nil { count += 1 }
        if sections.positionSettings && config.positionSettings != nil { count += 1 }
        if sections.otherSettings && config.otherSettings != nil { count += 1 }
        if sections.channels { count += (config.channels?.count ?? 0) + 1 } // +1 for reading current
        if sections.contacts { count += config.contacts?.count ?? 0 }
        if sections.radioSettings && config.radioSettings != nil { count += 2 } // radio + tx power
        return count
    }

}

// MARK: - Static Builders (testable without actor)

extension NodeConfigService {
    /// Builds radio settings from SelfInfo.
    public static func buildRadioSettings(from info: SelfInfo) -> MeshCoreNodeConfig.RadioSettings {
        MeshCoreNodeConfig.RadioSettings(
            frequency: UInt32(info.radioFrequency * 1000),
            bandwidth: UInt32(info.radioBandwidth * 1000),
            spreadingFactor: info.radioSpreadingFactor,
            codingRate: info.radioCodingRate,
            txPower: info.txPower
        )
    }

    /// Builds other settings from SelfInfo, matching official companion app format.
    public static func buildOtherSettings(from info: SelfInfo) -> MeshCoreNodeConfig.OtherSettings {
        MeshCoreNodeConfig.OtherSettings(
            manualAddContacts: info.manualAddContacts ? 1 : 0,
            advertLocationPolicy: info.advertisementLocationPolicy
        )
    }

    /// Builds a contact config from a MeshContact.
    static func buildContactConfig(from contact: MeshContact) -> MeshCoreNodeConfig.ContactConfig {
        let outPath: String?
        if contact.isFloodPath {
            outPath = nil
        } else if contact.pathByteLength > 0 && !contact.outPath.isEmpty {
            outPath = contact.outPath.prefix(contact.pathByteLength).hexString().lowercased()
        } else {
            outPath = ""
        }

        // Extract hash mode from encoded outPathLength (upper 2 bits)
        let pathHashMode: UInt8? = contact.isFloodPath ? nil : contact.outPathLength >> 6

        return MeshCoreNodeConfig.ContactConfig(
            type: contact.type.rawValue,
            name: contact.advertisedName,
            publicKey: contact.publicKey.hexString().lowercased(),
            flags: contact.flags.rawValue,
            latitude: String(contact.latitude),
            longitude: String(contact.longitude),
            lastAdvert: UInt32(contact.lastAdvertisement.timeIntervalSince1970),
            lastModified: UInt32(contact.lastModified.timeIntervalSince1970),
            outPath: outPath,
            pathHashMode: pathHashMode
        )
    }
}
