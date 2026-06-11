import Foundation
import MeshCore

// MARK: - Device Channel Slot

/// A snapshot of one channel slot read from the device during the import read phase.
/// Consumed by ``planConfigImport``
/// so slot planning stays a pure, testable function.
struct DeviceChannelSlot: Sendable, Equatable {
    let index: UInt8
    let name: String
    let secret: Data
    let isConfigured: Bool
}

// MARK: - Config Import Plan

/// A fully-resolved, validated set of writes produced from a `MeshCoreNodeConfig`
/// *before* any destructive device/database write begins.
///
/// Building a plan throws `NodeConfigServiceError` on any structural problem, so a
/// malformed or poison config is rejected up front and nothing is half-applied. The
/// execute phase then performs only writes that have already been proven applyable.
struct ConfigImportPlan: Sendable, Equatable {
    struct Coordinate: Sendable, Equatable {
        let latitude: Double
        let longitude: Double
    }

    struct ChannelWrite: Sendable, Equatable {
        let index: UInt8
        let name: String
        let secret: Data
    }

    /// Validated 64-byte private key to push, when identity is selected and present.
    var importPrivateKey: Data?
    /// Node name to set, when identity is selected and present.
    var nodeName: String?
    /// Validated, in-range position to set.
    var position: Coordinate?
    /// Other-settings to merge at execute time (passed through verbatim, raw bytes preserved).
    var otherSettings: MeshCoreNodeConfig.OtherSettings?
    /// Validated radio parameters to write, when the radio section is selected and present.
    var radioSettings: MeshCoreNodeConfig.RadioSettings?
    /// Resolved channel writes (deduplicated; intra-import duplicates folded onto one slot).
    var channelWrites: [ChannelWrite]
    /// True when any channel write replaces an already-configured slot whose name/secret differs
    /// — i.e. the channels section is not purely additive for this config.
    var channelsOverwriteExisting: Bool
    /// Validated, deduplicated contact records ready to write (raw type byte preserved).
    var contactRecords: [MeshContact]
}

// MARK: - Planner

/// Validates a `MeshCoreNodeConfig` against the device's capabilities and current channel
/// state, returning a ready-to-execute ``ConfigImportPlan`` or throwing the first problem.
///
/// Pure and synchronous so it is unit-testable without a live `MeshCoreSession` — mirrors the
/// `resolveEffectiveRadioID` seam pattern. Only sections present in `sections` are planned.
func planConfigImport(
    config: MeshCoreNodeConfig,
    sections: ConfigSections,
    maxChannels: UInt8,
    maxContacts: Int,
    maxTxPower: Int8,
    existingChannels: [DeviceChannelSlot],
    existingContactKeys: Set<String>
) throws -> ConfigImportPlan {
    var plan = ConfigImportPlan(
        importPrivateKey: nil,
        nodeName: nil,
        position: nil,
        otherSettings: nil,
        radioSettings: nil,
        channelWrites: [],
        channelsOverwriteExisting: false,
        contactRecords: []
    )

    if sections.nodeIdentity {
        plan.importPrivateKey = try planPrivateKey(config: config)
        plan.nodeName = config.name
    }

    if sections.positionSettings, let position = config.positionSettings {
        let lat = try validatedCoordinate(position.latitude, field: .positionLatitude, range: PacketBuilder.latitudeRange)
        let lon = try validatedCoordinate(position.longitude, field: .positionLongitude, range: PacketBuilder.longitudeRange)
        plan.position = ConfigImportPlan.Coordinate(latitude: lat, longitude: lon)
    }

    if sections.otherSettings {
        plan.otherSettings = config.otherSettings
    }

    if sections.radioSettings, let radio = config.radioSettings {
        plan.radioSettings = try planRadioSettings(radio, maxTxPower: maxTxPower)
    }

    if sections.channels, let channels = config.channels {
        let (writes, overwrite) = try planChannelWrites(
            channels, maxChannels: maxChannels, existingChannels: existingChannels
        )
        plan.channelWrites = writes
        plan.channelsOverwriteExisting = overwrite
    }

    if sections.contacts, let contacts = config.contacts {
        plan.contactRecords = try planContactRecords(
            contacts, maxContacts: maxContacts, existingContactKeys: existingContactKeys
        )
    }

    return plan
}

// MARK: - Identity

private func planPrivateKey(config: MeshCoreNodeConfig) throws -> Data? {
    guard let privateKeyHex = config.privateKey else { return nil }

    // A present-but-unparseable key must be rejected, not silently skipped. The key is the 64-byte
    // expanded Ed25519 secret (`clamp(SHA512(seed))`). The public key is derivable from this scalar
    // (firmware re-derives and validates it on import), but MC1's CryptoKit API operates on the
    // 32-byte seed, which the export omits, so MC1 cannot cheaply re-derive and cross-check it here
    // and takes the pairing on trust. Firmware additionally rejects all-00/FF-prefix keys and the
    // known test keypair; that content check is intentionally deferred to the device and is the one
    // identity failure that can surface at execute time, but firmware checks it before saving the
    // identity, so it still cannot leave a half-rotated identity.
    guard privateKeyHex.allSatisfy(\.isHexDigit),
          let privateKeyData = Data(hexString: privateKeyHex),
          privateKeyData.count == ProtocolLimits.privateKeySize else {
        throw NodeConfigServiceError.invalidPrivateKey(hexLength: privateKeyHex.count)
    }

    return privateKeyData
}

// MARK: - Coordinates

private func validatedCoordinate(_ raw: String, field: CoordinateField, range: ClosedRange<Double>) throws -> Double {
    guard let value = Double(raw), value.isFinite, range.contains(value) else {
        throw NodeConfigServiceError.invalidCoordinate(field: field)
    }
    return value
}

// MARK: - Radio

/// Validates radio parameters against the firmware-accepted ranges so an out-of-range value from a
/// hand-edited backup is rejected up front rather than throwing at execute time, after the identity
/// has already been rotated. The txPower upper bound is the device-reported `maxTxPower`, since it is
/// hardware/build-specific; the other ranges are fixed firmware limits in `PacketBuilder`.
private func planRadioSettings(
    _ radio: MeshCoreNodeConfig.RadioSettings,
    maxTxPower: Int8
) throws -> MeshCoreNodeConfig.RadioSettings {
    guard PacketBuilder.frequencyRangeKHz.contains(radio.frequency) else {
        throw NodeConfigServiceError.invalidRadioSettings(field: .frequency)
    }
    guard PacketBuilder.bandwidthRangeHz.contains(radio.bandwidth) else {
        throw NodeConfigServiceError.invalidRadioSettings(field: .bandwidth)
    }
    guard PacketBuilder.spreadingFactorRange.contains(radio.spreadingFactor) else {
        throw NodeConfigServiceError.invalidRadioSettings(field: .spreadingFactor)
    }
    guard PacketBuilder.codingRateRange.contains(radio.codingRate) else {
        throw NodeConfigServiceError.invalidRadioSettings(field: .codingRate)
    }
    guard radio.txPower >= PacketBuilder.txPowerFloor, radio.txPower <= maxTxPower else {
        throw NodeConfigServiceError.invalidRadioSettings(field: .txPower)
    }
    return radio
}

// MARK: - Channels

/// Plans channel slot assignment with merge semantics, folding intra-import duplicates — same
/// hashtag name or secret — onto one slot so a config never consumes two slots for the same
/// channel, and flagging overwrites of already-configured slots.
private func planChannelWrites(
    _ channels: [MeshCoreNodeConfig.ChannelConfig],
    maxChannels: UInt8,
    existingChannels: [DeviceChannelSlot]
) throws -> (writes: [ConfigImportPlan.ChannelWrite], overwrite: Bool) {
    var hashtagNameToIndex: [String: UInt8] = [:]
    var secretToIndex: [String: UInt8] = [:]
    var emptyIndices: [UInt8] = []
    var existingByIndex: [UInt8: (name: String, secret: Data)] = [:]

    for slot in existingChannels where slot.index < maxChannels {
        if slot.isConfigured {
            existingByIndex[slot.index] = (slot.name, slot.secret)
            // Index every configured slot by secret, so a same-secret import folds onto it
            // regardless of whether the existing slot is a hashtag channel. Hashtag slots are
            // additionally indexed by their (already device-truncated) name.
            secretToIndex[slot.secret.hexString] = slot.index
            if slot.name.hasPrefix("#") {
                hashtagNameToIndex[slot.name.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)] = slot.index
            }
        } else {
            emptyIndices.append(slot.index)
        }
    }

    var writes: [ConfigImportPlan.ChannelWrite] = []
    var overwrite = false

    for (i, channel) in channels.enumerated() {
        guard channel.secret.allSatisfy(\.isHexDigit),
              let secretData = Data(hexString: channel.secret),
              secretData.count == ProtocolLimits.channelSecretSize else {
            throw NodeConfigServiceError.invalidChannelSecret(index: i, hexLength: channel.secret.count)
        }
        // Key on the re-hexed parsed bytes (canonical) so a config secret with non-canonical
        // casing still dedups against the device's canonically-keyed slots.
        let secretKey = secretData.hexString
        // The device stores names truncated to the firmware field width, so dedup and overwrite
        // comparison must use the truncated form — otherwise a long hashtag name misses its slot.
        let lookupName = channel.name.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)

        // The secret is firmware's channel-match key (findChannelIdx memcmp), so it must stay
        // single-homed. Resolve any slot the secret already occupies first; a hashtag-name match
        // must defer to it, otherwise a "#name"+new-secret import would duplicate that secret onto
        // the name's slot while its original slot still holds it, mis-attributing mesh traffic.
        let secretSlot = secretToIndex[secretKey]

        let targetIndex: UInt8
        if let secretSlot {
            targetIndex = secretSlot
        } else if channel.name.hasPrefix("#"), let existing = hashtagNameToIndex[lookupName] {
            targetIndex = existing
        } else if let empty = emptyIndices.first {
            emptyIndices.removeFirst()
            targetIndex = empty
        } else {
            throw NodeConfigServiceError.noAvailableChannelSlot(name: channel.name)
        }

        // Overwrite of a slot that was configured on the device with a different name or secret,
        // comparing the truncated name the device actually stores.
        if let prior = existingByIndex[targetIndex], prior.name != lookupName || prior.secret != secretData {
            overwrite = true
        }

        // Update lookup tables so a later same-name/same-secret import folds onto this slot
        // instead of consuming a fresh one.
        if channel.name.hasPrefix("#") {
            hashtagNameToIndex[lookupName] = targetIndex
        }
        secretToIndex[secretKey] = targetIndex

        writes.append(ConfigImportPlan.ChannelWrite(index: targetIndex, name: channel.name, secret: secretData))
    }

    return (writes, overwrite)
}

// MARK: - Contacts

/// Validates and deduplicates the contacts array, returning ready-to-write records.
/// Dedups by public key (newest by `last_modified` wins), enforces device capacity, and rejects
/// invalid keys, path modes, coordinates, and routing paths before any write.
private func planContactRecords(
    _ contacts: [MeshCoreNodeConfig.ContactConfig],
    maxContacts: Int,
    existingContactKeys: Set<String>
) throws -> [MeshContact] {
    var byKey: [String: (config: MeshCoreNodeConfig.ContactConfig, publicKey: Data)] = [:]
    var order: [String] = []

    for contact in contacts {
        guard contact.publicKey.allSatisfy(\.isHexDigit),
              let publicKey = Data(hexString: contact.publicKey),
              publicKey.count == ProtocolLimits.publicKeySize else {
            throw NodeConfigServiceError.invalidContactPublicKey(name: contact.name)
        }
        let key = publicKey.hexString
        if let existing = byKey[key] {
            if contact.lastModified >= existing.config.lastModified {
                byKey[key] = (contact, publicKey)
            }
        } else {
            byKey[key] = (contact, publicKey)
            order.append(key)
        }
    }

    // A firmware update of a key already on the device consumes no slot, so only keys not already
    // present count against free capacity. Checking remaining slots (not the absolute table size)
    // lets the non-destructive preview reject an overflow up front instead of failing partway
    // through the contact writes with `TABLE_FULL`, after identity and channels have committed.
    let newKeyCount = order.reduce(0) { count, key in
        existingContactKeys.contains(key) ? count : count + 1
    }
    let availableSlots = maxContacts - existingContactKeys.count
    guard newKeyCount <= availableSlots else {
        throw NodeConfigServiceError.contactCapacityExceeded(needed: newKeyCount, available: availableSlots)
    }

    return try order.map { key in
        let entry = byKey[key]!
        return try buildContactRecord(entry.config, publicKey: entry.publicKey, hexKey: key)
    }
}

/// Builds one validated contact record. `publicKey` and `hexKey` are the already-decoded key
/// and its lowercase hex from ``planContactRecords``, so the key is parsed exactly once.
private func buildContactRecord(
    _ contact: MeshCoreNodeConfig.ContactConfig,
    publicKey: Data,
    hexKey: String
) throws -> MeshContact {
    let (outPath, outPathLength) = try resolveOutPath(contact)

    let lat = try validatedCoordinate(contact.latitude, field: .contactLatitude(name: contact.name), range: PacketBuilder.latitudeRange)
    let lon = try validatedCoordinate(contact.longitude, field: .contactLongitude(name: contact.name), range: PacketBuilder.longitudeRange)

    return MeshContact(
        id: hexKey,
        publicKey: publicKey,
        type: ContactType(rawValue: contact.type) ?? .chat,
        typeRawValue: contact.type,
        flags: ContactFlags(rawValue: contact.flags),
        outPathLength: outPathLength,
        outPath: outPath,
        advertisedName: contact.name,
        lastAdvertisement: Date(timeIntervalSince1970: TimeInterval(contact.lastAdvert)),
        latitude: lat,
        longitude: lon,
        lastModified: Date(timeIntervalSince1970: TimeInterval(contact.lastModified))
    )
}

/// Resolves the encoded out-path for a contact, rejecting malformed routing data instead of
/// silently downgrading a routed contact to direct.
private func resolveOutPath(_ contact: MeshCoreNodeConfig.ContactConfig) throws -> (path: Data, length: UInt8) {
    guard let pathHex = contact.outPath else {
        // Absent path: flood routing.
        return (Data(), PacketBuilder.floodPathSentinel)
    }
    if pathHex.isEmpty {
        // Explicit empty string: direct contact.
        return (Data(), 0)
    }
    // `Data(hexString:)` silently drops non-hex characters, so a routed path like "zzz" would
    // parse to empty and masquerade as direct. Require contiguous, even-length hex up front.
    guard pathHex.count.isMultiple(of: 2),
          pathHex.allSatisfy(\.isHexDigit),
          let pathData = Data(hexString: pathHex), !pathData.isEmpty else {
        throw NodeConfigServiceError.invalidOutPath(name: contact.name)
    }
    let mode = contact.pathHashMode ?? 0
    guard mode <= UInt8(PathEncoding.maxPathHashMode) else {
        throw NodeConfigServiceError.invalidPathHashMode(name: contact.name, mode: mode)
    }
    let hashSize = Int(mode) + 1
    // Reject paths that would silently truncate (non-multiple length, hop count past the 6-bit
    // field) or exceed the firmware out-path buffer (firmware `isValidPathLen`).
    guard pathData.count % hashSize == 0,
          pathData.count / hashSize <= PathEncoding.maxHopCount,
          pathData.count <= PathEncoding.maxPathBytes else {
        throw NodeConfigServiceError.invalidOutPath(name: contact.name)
    }
    let hopCount = pathData.count / hashSize
    return (pathData, encodePathLen(hashSize: hashSize, hopCount: hopCount))
}
