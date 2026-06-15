import Foundation
import Testing
@testable import MC1Services
@testable import MeshCore

/// Covers the pure validate/plan seam (`planConfigImport`) and the non-trapping coordinate/timestamp
/// encoders. These exercise the import safety guarantees without a live `MeshCoreSession`.
@Suite("NodeConfigImportPlanner Tests")
struct NodeConfigImportPlannerTests {

    // MARK: - Fixtures

    // Raw secret bytes paired with their hex string. Building the `Data` directly avoids the
    // ambiguous `Data(hexString:)` that exists in both @testable-imported modules.
    private static let secretBytesA = Data([
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff,
    ])
    private static let secretBytesB = Data([
        0xff, 0xee, 0xdd, 0xcc, 0xbb, 0xaa, 0x99, 0x88,
        0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00,
    ])
    private static let validChannelSecretA = "00112233445566778899aabbccddeeff"
    private static let validChannelSecretB = "ffeeddccbbaa99887766554433221100"
    private static let pubKeyHexA = String(repeating: "ab", count: 32)
    private static let pubKeyHexB = String(repeating: "cd", count: 32)

    private static func emptySlots(_ count: UInt8) -> [DeviceChannelSlot] {
        (0..<count).map { DeviceChannelSlot(index: $0, name: "", secret: Data(), isConfigured: false) }
    }

    private static func channelSections() -> ConfigSections {
        ConfigSections(
            nodeIdentity: false, radioSettings: false, positionSettings: false,
            otherSettings: false, channels: true, contacts: false
        )
    }

    private static func contactSections() -> ConfigSections {
        ConfigSections(
            nodeIdentity: false, radioSettings: false, positionSettings: false,
            otherSettings: false, channels: false, contacts: true
        )
    }

    private static func identitySections() -> ConfigSections {
        ConfigSections(
            nodeIdentity: true, radioSettings: false, positionSettings: false,
            otherSettings: false, channels: false, contacts: false
        )
    }

    private static func contact(
        type: UInt8 = 1,
        name: String,
        publicKey: String,
        latitude: String = "0",
        longitude: String = "0",
        lastModified: UInt32 = 0,
        outPath: String? = nil,
        pathHashMode: UInt8? = nil
    ) -> MeshCoreNodeConfig.ContactConfig {
        MeshCoreNodeConfig.ContactConfig(
            type: type, name: name, publicKey: publicKey, flags: 0,
            latitude: latitude, longitude: longitude,
            lastAdvert: 0, lastModified: lastModified,
            outPath: outPath, pathHashMode: pathHashMode
        )
    }

    private static func plan(
        channels: [MeshCoreNodeConfig.ChannelConfig]? = nil,
        contacts: [MeshCoreNodeConfig.ContactConfig]? = nil,
        positionSettings: MeshCoreNodeConfig.PositionSettings? = nil,
        radioSettings: MeshCoreNodeConfig.RadioSettings? = nil,
        privateKey: String? = nil,
        publicKey: String? = nil,
        name: String? = nil,
        sections: ConfigSections,
        maxChannels: UInt8 = 8,
        maxContacts: Int = 100,
        maxTxPower: Int8 = 30,
        existingChannels: [DeviceChannelSlot] = emptySlots(8),
        existingContacts: [String: MeshContact] = [:]
    ) throws -> ConfigImportPlan {
        var config = MeshCoreNodeConfig()
        config.channels = channels
        config.contacts = contacts
        config.positionSettings = positionSettings
        config.radioSettings = radioSettings
        config.privateKey = privateKey
        config.publicKey = publicKey
        config.name = name
        return try planConfigImport(
            config: config, sections: sections,
            maxChannels: maxChannels, maxContacts: maxContacts, maxTxPower: maxTxPower,
            existingChannels: existingChannels, existingContacts: existingContacts
        )
    }

    /// A present-key entry for the existing-contacts map whose fields deliberately differ from the
    /// capacity tests' imports, so the key counts toward capacity without triggering an M2 skip.
    /// The stored `publicKey` is irrelevant to capacity accounting (which keys on the map), so a
    /// placeholder avoids the cross-module `Data(hexString:)` ambiguity.
    private static func presentContact(keyedAs hexKey: String) -> (String, MeshContact) {
        let contact = MeshContact(
            id: hexKey,
            publicKey: Data(repeating: 0, count: 32),
            type: .chat,
            flags: ContactFlags(rawValue: 0),
            outPathLength: 0xFF,
            outPath: Data(),
            advertisedName: "Existing",
            lastAdvertisement: Date(timeIntervalSince1970: 0),
            latitude: 0,
            longitude: 0,
            lastModified: Date(timeIntervalSince1970: 0)
        )
        return (hexKey, contact)
    }

    // MARK: - Coordinate validation

    @Test("Position with NaN latitude is rejected before any write")
    func positionNaNRejected() {
        let sections = ConfigSections(
            nodeIdentity: false, radioSettings: false, positionSettings: true,
            otherSettings: false, channels: false, contacts: false
        )
        #expect {
            _ = try Self.plan(
                positionSettings: .init(latitude: "nan", longitude: "0"),
                sections: sections
            )
        } throws: { error in
            if case NodeConfigServiceError.invalidCoordinate(.positionLatitude) = error { return true }
            return false
        }
    }

    @Test("Position with out-of-range latitude is rejected")
    func positionOutOfRangeRejected() {
        let sections = ConfigSections(
            nodeIdentity: false, radioSettings: false, positionSettings: true,
            otherSettings: false, channels: false, contacts: false
        )
        #expect {
            _ = try Self.plan(
                positionSettings: .init(latitude: "1000000000", longitude: "0"),
                sections: sections
            )
        } throws: { error in
            if case NodeConfigServiceError.invalidCoordinate(.positionLatitude) = error { return true }
            return false
        }
    }

    @Test("Contact with infinite longitude is rejected")
    func contactInfiniteCoordinateRejected() {
        let contacts = [Self.contact(name: "Bad", publicKey: Self.pubKeyHexA, longitude: "inf")]
        #expect {
            _ = try Self.plan(contacts: contacts, sections: Self.contactSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidCoordinate(.contactLongitude(name: "Bad")) = error { return true }
            return false
        }
    }

    @Test("Valid position passes and is carried into the plan")
    func validPositionAccepted() throws {
        let sections = ConfigSections(
            nodeIdentity: false, radioSettings: false, positionSettings: true,
            otherSettings: false, channels: false, contacts: false
        )
        let plan = try Self.plan(
            positionSettings: .init(latitude: "47.6", longitude: "-122.3"),
            sections: sections
        )
        #expect(plan.position?.latitude == 47.6)
        #expect(plan.position?.longitude == -122.3)
    }

    // MARK: - Channels

    @Test("Two same-name hashtag channels fold onto one slot")
    func intraImportNameDedup() throws {
        let channels = [
            MeshCoreNodeConfig.ChannelConfig(name: "#rescue", secret: Self.validChannelSecretA),
            MeshCoreNodeConfig.ChannelConfig(name: "#rescue", secret: Self.validChannelSecretB),
        ]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections())
        #expect(plan.channelWrites.count == 2)
        #expect(Set(plan.channelWrites.map(\.index)).count == 1,
                "Same-name channels must not consume two slots")
    }

    @Test("Two same-secret channels fold onto one slot")
    func intraImportSecretDedup() throws {
        let channels = [
            MeshCoreNodeConfig.ChannelConfig(name: "Alpha", secret: Self.validChannelSecretA),
            MeshCoreNodeConfig.ChannelConfig(name: "Beta", secret: Self.validChannelSecretA),
        ]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections())
        #expect(plan.channelWrites.count == 2)
        #expect(Set(plan.channelWrites.map(\.index)).count == 1,
                "Same-secret channels must not consume two slots")
    }

    @Test("Two distinct channels land on two separate empty slots")
    func distinctChannelsTakeDistinctSlots() throws {
        let channels = [
            MeshCoreNodeConfig.ChannelConfig(name: "Alpha", secret: Self.validChannelSecretA),
            MeshCoreNodeConfig.ChannelConfig(name: "Beta", secret: Self.validChannelSecretB),
        ]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections())
        #expect(plan.channelWrites.count == 2,
                "Two genuinely distinct channels must not be merged into one write")
        #expect(Set(plan.channelWrites.map(\.index)).count == 2,
                "Distinct channels must occupy distinct slots")
    }

    @Test("Overwriting a configured slot with a differing secret is flagged")
    func overwriteDetected() throws {
        var slots = Self.emptySlots(8)
        slots[0] = DeviceChannelSlot(index: 0, name: "#old", secret: Self.secretBytesA, isConfigured: true)

        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "#old", secret: Self.validChannelSecretB)]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections(), existingChannels: slots)

        #expect(plan.channelsOverwriteExisting == true)
        #expect(plan.channelWrites.first?.index == 0)
    }

    @Test("Adding a channel into an empty slot is not an overwrite")
    func additiveChannelNotOverwrite() throws {
        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "#new", secret: Self.validChannelSecretA)]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections())
        #expect(plan.channelsOverwriteExisting == false)
    }

    @Test("Slot exhaustion is rejected before any write")
    func slotExhaustionRejected() {
        let slots = [DeviceChannelSlot(
            index: 0, name: "Existing",
            secret: Self.secretBytesA, isConfigured: true
        )]
        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "New", secret: Self.validChannelSecretB)]
        #expect {
            _ = try Self.plan(
                channels: channels, sections: Self.channelSections(),
                maxChannels: 1, existingChannels: slots
            )
        } throws: { error in
            if case NodeConfigServiceError.noAvailableChannelSlot(name: "New") = error { return true }
            return false
        }
    }

    @Test("Existing hashtag slot folds a same-secret non-hashtag import onto its slot")
    func existingHashtagSlotFoldsSameSecretImport() throws {
        var slots = Self.emptySlots(8)
        slots[0] = DeviceChannelSlot(index: 0, name: "#rescue", secret: Self.secretBytesA, isConfigured: true)

        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "Alpha", secret: Self.validChannelSecretA)]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections(), existingChannels: slots)

        #expect(plan.channelWrites.count == 1)
        #expect(plan.channelWrites.first?.index == 0,
                "A same-secret import must fold onto the existing hashtag slot, not consume a fresh one")
        #expect(plan.channelsOverwriteExisting == true)
    }

    @Test("Long hashtag name folds onto its existing slot despite device-side truncation")
    func longHashtagNameFoldsOntoExistingSlot() throws {
        let fullName = "#" + String(repeating: "a", count: 40)        // 41 bytes, exceeds the 31-byte field
        let deviceTruncated = "#" + String(repeating: "a", count: 30) // 31 bytes, what firmware stores
        var slots = Self.emptySlots(8)
        slots[0] = DeviceChannelSlot(index: 0, name: deviceTruncated, secret: Self.secretBytesA, isConfigured: true)

        let channels = [MeshCoreNodeConfig.ChannelConfig(name: fullName, secret: Self.validChannelSecretB)]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections(), existingChannels: slots)

        #expect(plan.channelWrites.count == 1)
        #expect(plan.channelWrites.first?.index == 0,
                "A long hashtag name must match its truncated device slot, not consume a fresh one")
    }

    @Test("Hashtag-name import whose secret already lives on another slot folds onto the secret's slot")
    func hashtagImportFoldsToExistingSecretSlot() throws {
        var slots = Self.emptySlots(8)
        slots[0] = DeviceChannelSlot(index: 0, name: "#general", secret: Self.secretBytesA, isConfigured: true)
        slots[1] = DeviceChannelSlot(index: 1, name: "Other", secret: Self.secretBytesB, isConfigured: true)
        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "#general", secret: Self.validChannelSecretB)]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections(), existingChannels: slots)
        #expect(plan.channelWrites.count == 1)
        #expect(plan.channelWrites.first?.index == 1,
                "A secret already on another slot must keep the write single-homed")
        #expect(plan.channelWrites.allSatisfy { $0.index != 0 },
                "The secret must not be duplicated onto the hashtag-name slot")
    }

    @Test("Non-canonical secret hex still dedups against the canonically-keyed existing slot")
    func nonCanonicalSecretHexFolds() throws {
        var slots = Self.emptySlots(8)
        slots[0] = DeviceChannelSlot(index: 0, name: "Existing", secret: Self.secretBytesA, isConfigured: true)

        // Same secret bytes as the existing slot but written in uppercase, a non-canonical casing a
        // hand-edited backup might use, paired with a changed name so the fold still produces a write
        // (a byte-identical import would correctly skip). It must dedup against the device's
        // lowercase-canonical key rather than consume a fresh slot.
        let uppercased = Self.validChannelSecretA.uppercased()
        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "Renamed", secret: uppercased)]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections(), existingChannels: slots)

        #expect(plan.channelWrites.count == 1)
        #expect(plan.channelWrites.first?.index == 0,
                "A non-canonical secret must dedup against the canonical existing slot, not consume a fresh one")
    }

    @Test("Invalid channel secret length is rejected")
    func invalidChannelSecretRejected() {
        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "Bad", secret: "abcd")]
        #expect {
            _ = try Self.plan(channels: channels, sections: Self.channelSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidChannelSecret = error { return true }
            return false
        }
    }

    @Test("Channel secret with trailing non-hex characters is rejected, not silently accepted")
    func channelSecretTrailingNonHexRejected() {
        // Valid 32-char hex with stray non-hex characters appended. The filtering parser would
        // strip "zz" and still yield 16 bytes; the strict guard must reject the whole string.
        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "Bad", secret: Self.validChannelSecretA + "zz")]
        #expect {
            _ = try Self.plan(channels: channels, sections: Self.channelSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidChannelSecret = error { return true }
            return false
        }
    }

    // MARK: - Private key (malformed / wrong length)

    @Test("Present-but-garbage private key is rejected, not silently skipped")
    func garbagePrivateKeyRejected() {
        #expect {
            _ = try Self.plan(privateKey: "zzzz", sections: Self.identitySections())
        } throws: { error in
            if case NodeConfigServiceError.invalidPrivateKey = error { return true }
            return false
        }
    }

    @Test("Wrong-length private key is rejected")
    func wrongLengthPrivateKeyRejected() {
        #expect {
            _ = try Self.plan(privateKey: "abcd", sections: Self.identitySections())
        } throws: { error in
            if case NodeConfigServiceError.invalidPrivateKey = error { return true }
            return false
        }
    }

    @Test("Valid 64-byte expanded private key without a public key is accepted")
    func validPrivateKeyAccepted() async throws {
        let identity = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
        let plan = try Self.plan(
            privateKey: identity.expandedPrivateKey.hexString,
            sections: Self.identitySections()
        )
        #expect(plan.importPrivateKey == identity.expandedPrivateKey)
    }

    /// Regression: the real export format is the 64-byte expanded key (`clamp(SHA512(seed))`)
    /// alongside its public key, which export always emits. The public key is derivable from the
    /// expanded scalar, but MC1's CryptoKit API works from the 32-byte seed (which the export omits),
    /// so the plan accepts the pair on trust rather than re-deriving and cross-checking it.
    @Test("Expanded private key with its public key round-trips and is accepted")
    func matchingKeyPairAccepted() async throws {
        let identity = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
        let plan = try Self.plan(
            privateKey: identity.expandedPrivateKey.hexString,
            publicKey: identity.publicKey.hexString,
            sections: Self.identitySections()
        )
        #expect(plan.importPrivateKey == identity.expandedPrivateKey)
    }

    // MARK: - Radio validation

    private static func radioSections() -> ConfigSections {
        ConfigSections(
            nodeIdentity: false, radioSettings: true, positionSettings: false,
            otherSettings: false, channels: false, contacts: false
        )
    }

    private static func validRadio(
        frequency: UInt32 = 910_525,
        bandwidth: UInt32 = 62_500,
        spreadingFactor: UInt8 = 7,
        codingRate: UInt8 = 5,
        txPower: Int8 = 20
    ) -> MeshCoreNodeConfig.RadioSettings {
        .init(
            frequency: frequency, bandwidth: bandwidth,
            spreadingFactor: spreadingFactor, codingRate: codingRate, txPower: txPower
        )
    }

    @Test("Valid radio settings within firmware ranges are carried into the plan")
    func validRadioAccepted() throws {
        let plan = try Self.plan(radioSettings: Self.validRadio(), sections: Self.radioSections())
        #expect(plan.radioSettings == Self.validRadio())
    }

    @Test("Out-of-range spreading factor is rejected before any write")
    func radioSpreadingFactorRejected() {
        #expect {
            _ = try Self.plan(radioSettings: Self.validRadio(spreadingFactor: 99), sections: Self.radioSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidRadioSettings(.spreadingFactor) = error { return true }
            return false
        }
    }

    @Test("Frequency below the firmware floor is rejected")
    func radioFrequencyRejected() {
        #expect {
            _ = try Self.plan(radioSettings: Self.validRadio(frequency: 100_000), sections: Self.radioSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidRadioSettings(.frequency) = error { return true }
            return false
        }
    }

    @Test("TX power above the device's reported maximum is rejected")
    func radioTxPowerAboveDeviceMaxRejected() {
        #expect {
            _ = try Self.plan(
                radioSettings: Self.validRadio(txPower: 25),
                sections: Self.radioSections(), maxTxPower: 20
            )
        } throws: { error in
            if case NodeConfigServiceError.invalidRadioSettings(.txPower) = error { return true }
            return false
        }
    }

    @Test("TX power up to the device's reported maximum is accepted")
    func radioTxPowerAtDeviceMaxAccepted() throws {
        let plan = try Self.plan(
            radioSettings: Self.validRadio(txPower: 30),
            sections: Self.radioSections(), maxTxPower: 30
        )
        #expect(plan.radioSettings?.txPower == 30)
    }

    // MARK: - Contacts (dedup, capacity, out_path, unknown type)

    @Test("Duplicate contacts dedup by public key, newest last_modified wins")
    func contactDedupNewestWins() throws {
        let contacts = [
            Self.contact(name: "Older", publicKey: Self.pubKeyHexA, lastModified: 100),
            Self.contact(name: "Newer", publicKey: Self.pubKeyHexA, lastModified: 200),
        ]
        let plan = try Self.plan(contacts: contacts, sections: Self.contactSections())
        #expect(plan.contactRecords.count == 1)
        #expect(plan.contactRecords.first?.advertisedName == "Newer")
    }

    @Test("Dedup keeps the newer record even when it appears first (inverse comparison branch)")
    func contactDedupNewestWinsReversedOrder() throws {
        let contacts = [
            Self.contact(name: "Newer", publicKey: Self.pubKeyHexA, lastModified: 200),
            Self.contact(name: "Older", publicKey: Self.pubKeyHexA, lastModified: 100),
        ]
        let plan = try Self.plan(contacts: contacts, sections: Self.contactSections())
        #expect(plan.contactRecords.count == 1)
        #expect(plan.contactRecords.first?.advertisedName == "Newer",
                "The older record must not overwrite the already-seen newer one")
    }

    @Test("Dedup of equal-timestamp duplicates collapses to one record deterministically")
    func contactDedupEqualTimestamps() throws {
        let contacts = [
            Self.contact(name: "First", publicKey: Self.pubKeyHexA, lastModified: 100),
            Self.contact(name: "Second", publicKey: Self.pubKeyHexA, lastModified: 100),
        ]
        let plan = try Self.plan(contacts: contacts, sections: Self.contactSections())
        #expect(plan.contactRecords.count == 1)
        // The `>=` comparison adopts a same-timestamp later entry, so the last one read wins.
        #expect(plan.contactRecords.first?.advertisedName == "Second")
    }

    @Test("Contact with a valid out_path but out-of-range path hash mode is rejected")
    func contactInvalidPathHashModeRejected() {
        let contacts = [Self.contact(
            name: "BadMode", publicKey: Self.pubKeyHexA, outPath: "aabb", pathHashMode: 3
        )]
        #expect {
            _ = try Self.plan(contacts: contacts, sections: Self.contactSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidPathHashMode(name: "BadMode", mode: 3) = error { return true }
            return false
        }
    }

    @Test("Exceeding device contact capacity is rejected")
    func contactCapacityRejected() {
        let contacts = [
            Self.contact(name: "A", publicKey: Self.pubKeyHexA),
            Self.contact(name: "B", publicKey: Self.pubKeyHexB),
        ]
        #expect {
            _ = try Self.plan(contacts: contacts, sections: Self.contactSections(), maxContacts: 1)
        } throws: { error in
            if case NodeConfigServiceError.contactCapacityExceeded = error { return true }
            return false
        }
    }

    @Test("Exactly filling the remaining contact slots is accepted")
    func contactCapacityBoundaryAccepted() throws {
        let contacts = [
            Self.contact(name: "A", publicKey: Self.pubKeyHexA),
            Self.contact(name: "B", publicKey: Self.pubKeyHexB),
        ]
        let plan = try Self.plan(contacts: contacts, sections: Self.contactSections(), maxContacts: 2)
        #expect(plan.contactRecords.count == 2)
    }

    @Test("Capacity check credits keys already on the device (updates consume no slot)")
    func contactCapacityCreditsExistingKeys() throws {
        // Device table is full (maxContacts 1, one existing key), but the import only updates that key.
        let plan = try Self.plan(
            contacts: [Self.contact(name: "Update", publicKey: Self.pubKeyHexA)],
            sections: Self.contactSections(),
            maxContacts: 1,
            existingContacts: Dictionary(uniqueKeysWithValues: [Self.presentContact(keyedAs: Self.pubKeyHexA.lowercased())])
        )
        #expect(plan.contactRecords.count == 1)
    }

    @Test("A new contact has no free slot once the device table is full")
    func contactCapacityRejectsNewWhenFull() {
        #expect {
            _ = try Self.plan(
                contacts: [Self.contact(name: "New", publicKey: Self.pubKeyHexB)],
                sections: Self.contactSections(),
                maxContacts: 1,
                existingContacts: Dictionary(uniqueKeysWithValues: [Self.presentContact(keyedAs: Self.pubKeyHexA.lowercased())])
            )
        } throws: { error in
            if case NodeConfigServiceError.contactCapacityExceeded = error { return true }
            return false
        }
    }

    @Test("Invalid out_path hex is rejected instead of silently downgrading to direct")
    func invalidOutPathRejected() {
        let contacts = [Self.contact(name: "BadPath", publicKey: Self.pubKeyHexA, outPath: "zzz")]
        #expect {
            _ = try Self.plan(contacts: contacts, sections: Self.contactSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidOutPath(name: "BadPath") = error { return true }
            return false
        }
    }

    @Test("Out_path longer than the firmware buffer is rejected")
    func outPathExceedingBufferRejected() {
        // 66 bytes of 3-byte hashes = 22 hops: within the 6-bit hop field but past MAX_PATH_SIZE,
        // so only the total-length guard catches it, not the hop-count guard.
        let longPath = String(repeating: "ab", count: PathEncoding.maxPathBytes / 3 * 3 + 3)
        let contacts = [Self.contact(
            name: "TooLong", publicKey: Self.pubKeyHexA, outPath: longPath, pathHashMode: 2
        )]
        #expect {
            _ = try Self.plan(contacts: contacts, sections: Self.contactSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidOutPath(name: "TooLong") = error { return true }
            return false
        }
    }

    @Test("Invalid contact public key is rejected")
    func invalidContactPublicKeyRejected() {
        let contacts = [Self.contact(name: "BadKey", publicKey: "abcd")]
        #expect {
            _ = try Self.plan(contacts: contacts, sections: Self.contactSections())
        } throws: { error in
            if case NodeConfigServiceError.invalidContactPublicKey(name: "BadKey") = error { return true }
            return false
        }
    }

    @Test("Unknown contact type byte is preserved verbatim while UI type falls back to chat")
    func unknownContactTypePreserved() throws {
        let contacts = [Self.contact(type: 99, name: "FutureType", publicKey: Self.pubKeyHexA)]
        let plan = try Self.plan(contacts: contacts, sections: Self.contactSections())
        let record = try #require(plan.contactRecords.first)
        #expect(record.typeRawValue == 99)
        #expect(record.type == .chat)
    }

    @Test("Absent out_path plans flood routing; empty string plans direct")
    func outPathRoutingModes() throws {
        let flood = try Self.plan(
            contacts: [Self.contact(name: "Flood", publicKey: Self.pubKeyHexA, outPath: nil)],
            sections: Self.contactSections()
        )
        #expect(flood.contactRecords.first?.outPathLength == 0xFF)

        let direct = try Self.plan(
            contacts: [Self.contact(name: "Direct", publicKey: Self.pubKeyHexB, outPath: "")],
            sections: Self.contactSections()
        )
        #expect(direct.contactRecords.first?.outPathLength == 0)
    }

    @Test("Valid out_path resolves to its bytes and encoded length for each hash mode")
    func validOutPathPerMode() throws {
        // Expected encoded length is a pinned literal (mode << 6 | hopCount), not a re-derivation of
        // the planner's own encodePathLen, so a bug in that encoder cannot be mirrored into the value.
        let cases: [(mode: UInt8, hex: String, bytes: [UInt8], length: UInt8)] = [
            (0, "aabb", [0xaa, 0xbb], 0x02),
            (1, "aabbccdd", [0xaa, 0xbb, 0xcc, 0xdd], 0x42),
            (2, "aabbccddeeff", [0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff], 0x82),
        ]
        for testCase in cases {
            let plan = try Self.plan(
                contacts: [Self.contact(
                    name: "Routed", publicKey: Self.pubKeyHexA,
                    outPath: testCase.hex, pathHashMode: testCase.mode
                )],
                sections: Self.contactSections()
            )
            let record = try #require(plan.contactRecords.first)
            #expect(record.outPath == Data(testCase.bytes))
            #expect(record.outPathLength == testCase.length)
        }
    }

    // MARK: - Empty-but-present sections / identity carry-through

    @Test("Empty-but-present channel and contact arrays plan no writes and no overwrite")
    func emptyArraysPlanNothing() throws {
        let sections = ConfigSections(
            nodeIdentity: false, radioSettings: false, positionSettings: false,
            otherSettings: false, channels: true, contacts: true
        )
        let plan = try Self.plan(channels: [], contacts: [], sections: sections)
        #expect(plan.channelWrites.isEmpty)
        #expect(plan.contactRecords.isEmpty)
        #expect(plan.channelsOverwriteExisting == false)
    }

    @Test("Identity plan carries a non-nil node name verbatim")
    func identityCarriesNodeName() throws {
        let plan = try Self.plan(name: "Rescue Base", sections: Self.identitySections())
        #expect(plan.nodeName == "Rescue Base")
    }

    // MARK: - M1: byte-identical channel slot skip

    @Test("A channel byte-identical to its resolved slot plans no write")
    func identicalChannelSlotSkipped() throws {
        var slots = Self.emptySlots(8)
        slots[0] = DeviceChannelSlot(index: 0, name: "Alpha", secret: Self.secretBytesA, isConfigured: true)

        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "Alpha", secret: Self.validChannelSecretA)]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections(), existingChannels: slots)

        #expect(plan.channelWrites.isEmpty, "An identical slot must not re-commit /channels2")
        #expect(plan.channelsOverwriteExisting == false)
    }

    @Test("A name-only diff, secret-only diff, and secret relocation each still plan one write")
    func channelDiffsStillWrite() throws {
        var nameDiff = Self.emptySlots(8)
        nameDiff[0] = DeviceChannelSlot(index: 0, name: "Old", secret: Self.secretBytesA, isConfigured: true)
        let nameDiffPlan = try Self.plan(
            channels: [MeshCoreNodeConfig.ChannelConfig(name: "New", secret: Self.validChannelSecretA)],
            sections: Self.channelSections(), existingChannels: nameDiff)
        #expect(nameDiffPlan.channelWrites.count == 1, "A name change must still write")

        var secretDiff = Self.emptySlots(8)
        secretDiff[0] = DeviceChannelSlot(index: 0, name: "Alpha", secret: Self.secretBytesA, isConfigured: true)
        let secretDiffPlan = try Self.plan(
            channels: [MeshCoreNodeConfig.ChannelConfig(name: "Alpha", secret: Self.validChannelSecretB)],
            sections: Self.channelSections(), existingChannels: secretDiff)
        #expect(secretDiffPlan.channelWrites.count == 1, "A secret change must still write")

        // Secret A is homed at slot 2 under a different name: the import folds onto that slot and
        // writes because the name differs, so the skip must not swallow it.
        var relocate = Self.emptySlots(8)
        relocate[2] = DeviceChannelSlot(index: 2, name: "Alpha", secret: Self.secretBytesA, isConfigured: true)
        let relocatePlan = try Self.plan(
            channels: [MeshCoreNodeConfig.ChannelConfig(name: "Renamed", secret: Self.validChannelSecretA)],
            sections: Self.channelSections(), existingChannels: relocate)
        #expect(relocatePlan.channelWrites.count == 1, "A name change on the secret's existing slot still writes")
        #expect(relocatePlan.channelWrites.first?.index == 2, "It folds onto the secret's existing slot")
    }

    @Test("A brand-new channel into an empty slot still plans one write")
    func newChannelStillWrites() throws {
        let channels = [MeshCoreNodeConfig.ChannelConfig(name: "#new", secret: Self.validChannelSecretA)]
        let plan = try Self.plan(channels: channels, sections: Self.channelSections())
        #expect(plan.channelWrites.count == 1)
    }

    @Test("A duplicate restoring a slot an earlier write changed is not swallowed by the skip")
    func duplicateRestoringFoldedSlotStillWrites() throws {
        // Same #hashtag homed at slot 0: the first entry changes the secret, the second restores
        // the device's original secret. The skip must compare against the slot's planned value, not
        // the frozen original, so the restoring write survives and wins last (slot ends at S1).
        var hashtagSlots = Self.emptySlots(8)
        hashtagSlots[0] = DeviceChannelSlot(index: 0, name: "#general", secret: Self.secretBytesA, isConfigured: true)
        let hashtagPlan = try Self.plan(
            channels: [
                MeshCoreNodeConfig.ChannelConfig(name: "#general", secret: Self.validChannelSecretB),
                MeshCoreNodeConfig.ChannelConfig(name: "#general", secret: Self.validChannelSecretA),
            ],
            sections: Self.channelSections(), existingChannels: hashtagSlots)
        #expect(hashtagPlan.channelWrites.count == 2, "Both folded writes are planned, last wins")
        #expect(hashtagPlan.channelWrites.last?.secret == Self.secretBytesA, "The restoring write must win, not be dropped")

        // Same-secret name fold: the first entry renames the slot, the second restores the device's
        // original name. The restoring write must survive so the slot ends at "Foo", not "Bar".
        var secretSlots = Self.emptySlots(8)
        secretSlots[0] = DeviceChannelSlot(index: 0, name: "Foo", secret: Self.secretBytesA, isConfigured: true)
        let secretPlan = try Self.plan(
            channels: [
                MeshCoreNodeConfig.ChannelConfig(name: "Bar", secret: Self.validChannelSecretA),
                MeshCoreNodeConfig.ChannelConfig(name: "Foo", secret: Self.validChannelSecretA),
            ],
            sections: Self.channelSections(), existingChannels: secretSlots)
        #expect(secretPlan.channelWrites.count == 2, "Both folded writes are planned, last wins")
        #expect(secretPlan.channelWrites.last?.name == "Foo", "The restoring write must win, not be dropped")
    }

    // MARK: - M2: byte-identical contact skip

    /// Models the device-resident form of a contact `session.getContacts` would report: the planner's
    /// record encoded to the wire and decoded back through the same `parseContactData` the live read
    /// uses, so the fixture reflects the device's stored bytes (name trimmed to the field width, coords
    /// scaled, path sliced) rather than the planner's own `buildContactRecord` output. `lastModified`
    /// models the firmware restamp the sub-148-byte add frame triggers: it defaults to the record's own
    /// value (the same-device export/re-import round-trip the skip targets), or pass a different value to
    /// model a foreign config the firmware re-stamped.
    private func deviceStored(_ record: MeshContact, lastModified deviceLastModified: Date? = nil) -> MeshContact {
        // The add frame omits last_modified (3 reserved bytes); the contact-response frame carries it at
        // offset 143. Reuse the add encoder for offsets 0..<143, then append the device's last_modified.
        var frame = Data(PacketBuilder.updateContact(record).dropFirst(1).prefix(143))
        frame.appendLittleEndian(UInt32((deviceLastModified ?? record.lastModified).timeIntervalSince1970))
        return Parsers.parseContactData(frame)!
    }

    /// Runs the import once against an empty device to capture the record the planner builds, then
    /// returns the device-resident form `getContacts` would report for it.
    private func recordFor(_ contact: MeshCoreNodeConfig.ContactConfig) throws -> MeshContact {
        let plan = try Self.plan(contacts: [contact], sections: Self.contactSections())
        return deviceStored(try #require(plan.contactRecords.first))
    }

    @Test("A contact equal on all persisted fields is dropped")
    func identicalContactDropped() throws {
        let config = Self.contact(
            name: "Bravo", publicKey: Self.pubKeyHexA, latitude: "47.5", longitude: "-122.5",
            lastModified: 1000, outPath: "aabb", pathHashMode: 0)
        let existing = try recordFor(config)

        let plan = try Self.plan(
            contacts: [config], sections: Self.contactSections(),
            existingContacts: [existing.id: existing])
        #expect(plan.contactRecords.isEmpty, "A byte-identical contact must not re-commit /contacts3")
    }

    @Test("A diff in any single persisted field still emits the contact")
    func contactFieldDiffStillEmits() throws {
        let base = Self.contact(
            name: "Bravo", publicKey: Self.pubKeyHexA, latitude: "47.5", longitude: "-122.5",
            lastModified: 1000, outPath: "aabb", pathHashMode: 0)
        let existing = try recordFor(base)

        let variants: [(label: String, config: MeshCoreNodeConfig.ContactConfig)] = [
            ("type", Self.contact(type: 2, name: "Bravo", publicKey: Self.pubKeyHexA, latitude: "47.5", longitude: "-122.5", lastModified: 1000, outPath: "aabb", pathHashMode: 0)),
            ("name", Self.contact(name: "Charlie", publicKey: Self.pubKeyHexA, latitude: "47.5", longitude: "-122.5", lastModified: 1000, outPath: "aabb", pathHashMode: 0)),
            ("path", Self.contact(name: "Bravo", publicKey: Self.pubKeyHexA, latitude: "47.5", longitude: "-122.5", lastModified: 1000, outPath: "ccdd", pathHashMode: 0)),
            ("coords", Self.contact(name: "Bravo", publicKey: Self.pubKeyHexA, latitude: "48.0", longitude: "-122.5", lastModified: 1000, outPath: "aabb", pathHashMode: 0)),
            ("lastModified", Self.contact(name: "Bravo", publicKey: Self.pubKeyHexA, latitude: "47.5", longitude: "-122.5", lastModified: 2000, outPath: "aabb", pathHashMode: 0)),
        ]
        for variant in variants {
            let plan = try Self.plan(
                contacts: [variant.config], sections: Self.contactSections(),
                existingContacts: [existing.id: existing])
            #expect(plan.contactRecords.count == 1, "A \(variant.label) diff must still write the contact")
        }
    }

    @Test("A name differing only past the firmware field width is treated as equal and dropped")
    func contactNameDiffPastFieldWidthDropped() throws {
        let shortConfig = Self.contact(
            name: "#" + String(repeating: "a", count: 30), publicKey: Self.pubKeyHexA)
        let existing = try recordFor(shortConfig)   // 31-byte name, what the device stores

        // Import the same contact whose name only diverges past the 31-byte field width.
        let longConfig = Self.contact(
            name: "#" + String(repeating: "a", count: 30) + "EXTRA", publicKey: Self.pubKeyHexA)
        let plan = try Self.plan(
            contacts: [longConfig], sections: Self.contactSections(),
            existingContacts: [existing.id: existing])
        #expect(plan.contactRecords.isEmpty, "A name the device cannot represent differently must be treated as equal")
    }

    @Test("A new contact is unaffected by the skip and capacity stays correct")
    func newContactStillEmittedWithSkip() throws {
        // One existing key matches its import (dropped); a second key is new (emitted).
        let matchConfig = Self.contact(name: "Match", publicKey: Self.pubKeyHexA, lastModified: 5)
        let existing = try recordFor(matchConfig)
        let newConfig = Self.contact(name: "Fresh", publicKey: Self.pubKeyHexB, lastModified: 5)

        let plan = try Self.plan(
            contacts: [matchConfig, newConfig], sections: Self.contactSections(),
            existingContacts: [existing.id: existing])
        #expect(plan.contactRecords.count == 1, "Only the unchanged contact is dropped")
        #expect(plan.contactRecords.first?.advertisedName == "Fresh")
    }

    @Test("A contact the firmware re-stamped is re-emitted, not dropped (skip is safe-fail)")
    func restampedContactReEmitted() throws {
        // The sub-148-byte contact-add frame carries no last_modified, so firmware stamps its own clock.
        // For a config exported from a different device, the device-read last_modified therefore differs
        // from the config's, and the skip must re-emit rather than drop: the optimization only fires for
        // a same-device export/re-import round-trip, never for a foreign config the firmware re-stamped.
        let config = Self.contact(
            name: "Bravo", publicKey: Self.pubKeyHexA, latitude: "47.5", longitude: "-122.5",
            lastModified: 1000, outPath: "aabb", pathHashMode: 0)
        let record = try #require(
            try Self.plan(contacts: [config], sections: Self.contactSections()).contactRecords.first)
        let existing = deviceStored(record, lastModified: Date(timeIntervalSince1970: 9_999))

        let plan = try Self.plan(
            contacts: [config], sections: Self.contactSections(),
            existingContacts: [existing.id: existing])
        #expect(plan.contactRecords.count == 1, "A re-stamped last_modified must re-emit, never silently drop")
    }
}

// MARK: - Encoder no-trap coverage

@Suite("PacketBuilder coordinate/timestamp encoders do not trap")
struct PacketBuilderEncoderTests {

    private func int32LE(_ data: Data, at offset: Int) -> Int32 {
        var value: UInt32 = 0
        for byte in 0..<4 {
            value |= UInt32(data[data.startIndex + offset + byte]) << (8 * byte)
        }
        return Int32(bitPattern: value)
    }

    private func uint32LE(_ data: Data, at offset: Int) -> UInt32 {
        var value: UInt32 = 0
        for byte in 0..<4 {
            value |= UInt32(data[data.startIndex + offset + byte]) << (8 * byte)
        }
        return value
    }

    @Test("setCoordinates clamps NaN to zero instead of trapping")
    func setCoordinatesNaN() {
        let data = PacketBuilder.setCoordinates(latitude: .nan, longitude: .nan)
        #expect(int32LE(data, at: 1) == 0)
        #expect(int32LE(data, at: 5) == 0)
    }

    @Test("setCoordinates clamps out-of-range degrees to the valid bounds")
    func setCoordinatesOutOfRange() {
        let data = PacketBuilder.setCoordinates(latitude: 9999, longitude: -9999)
        // 90 * 1_000_000 and -180 * 1_000_000
        #expect(int32LE(data, at: 1) == 90_000_000)
        #expect(int32LE(data, at: 5) == -180_000_000)
    }

    @Test("setCoordinates handles infinity without trapping")
    func setCoordinatesInfinity() {
        let data = PacketBuilder.setCoordinates(latitude: .infinity, longitude: -.infinity)
        #expect(int32LE(data, at: 1) == 0)
        #expect(int32LE(data, at: 5) == 0)
    }

    @Test("updateContact saturates a pre-1970 advertisement timestamp to zero")
    func updateContactNegativeTimestamp() {
        let contact = MeshContact(
            id: "neg", publicKey: Data(repeating: 1, count: 32),
            type: .chat, flags: [], outPathLength: 0xFF, outPath: Data(),
            advertisedName: "Old",
            lastAdvertisement: Date(timeIntervalSince1970: -1_000_000),
            latitude: 0, longitude: 0, lastModified: .now
        )
        let data = PacketBuilder.updateContact(contact)
        #expect(uint32LE(data, at: 132) == 0)
    }

    @Test("updateContact saturates a post-2106 advertisement timestamp to UInt32.max")
    func updateContactOverflowTimestamp() {
        let farFuture = Date(timeIntervalSince1970: TimeInterval(UInt32.max) + 1_000_000)
        let contact = MeshContact(
            id: "future", publicKey: Data(repeating: 1, count: 32),
            type: .chat, flags: [], outPathLength: 0xFF, outPath: Data(),
            advertisedName: "Future",
            lastAdvertisement: farFuture,
            latitude: 0, longitude: 0, lastModified: .now
        )
        let data = PacketBuilder.updateContact(contact)
        #expect(uint32LE(data, at: 132) == UInt32.max)
    }

    @Test("updateContact clamps out-of-range coordinates without trapping")
    func updateContactCoordinateClamp() {
        let contact = MeshContact(
            id: "coord", publicKey: Data(repeating: 1, count: 32),
            type: .chat, flags: [], outPathLength: 0xFF, outPath: Data(),
            advertisedName: "Coord",
            lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
            latitude: .nan, longitude: 9999, lastModified: .now
        )
        let data = PacketBuilder.updateContact(contact)
        #expect(int32LE(data, at: 136) == 0)
        #expect(int32LE(data, at: 140) == 180_000_000)
    }

    /// `addContact` now encodes via `PacketBuilder.updateContact`. Pin the frame length so a
    /// silent layout drift can't change the wire size firmware validates against.
    @Test("updateContact emits a 147-byte frame")
    func updateContactFrameLength() {
        let contact = MeshContact(
            id: "len", publicKey: Data(repeating: 1, count: 32),
            type: .chat, flags: [], outPathLength: 0xFF, outPath: Data(),
            advertisedName: "Len",
            lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
            latitude: 0, longitude: 0, lastModified: .now
        )
        #expect(PacketBuilder.updateContact(contact).count == 147)
    }

    /// Modeled types must encode their raw byte at the type offset unchanged, and an unmodeled
    /// byte must pass through verbatim — the type byte sits at packet offset 33 (cmd + 32-byte key).
    @Test("updateContact writes the raw type byte verbatim for modeled and unmodeled types")
    func updateContactPreservesRawTypeByte() {
        for raw: UInt8 in [0x01, 0x02, 0x03, 0x04, 0x99] {
            let contact = MeshContact(
                id: "type", publicKey: Data(repeating: 1, count: 32),
                type: ContactType(rawValue: raw) ?? .chat, typeRawValue: raw,
                flags: [], outPathLength: 0xFF, outPath: Data(),
                advertisedName: "T",
                lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
                latitude: 0, longitude: 0, lastModified: .now
            )
            let data = PacketBuilder.updateContact(contact)
            #expect(data[data.startIndex + 33] == raw)
        }
    }
}

// MARK: - Contact type-byte preservation

/// An unmodeled wire type byte must survive OTA decode, the
/// `ContactFrame`/`MeshContact`/`Contact` conversions, and config export, rather than being
/// coerced onto a modeled `ContactType` at any hop.
@Suite("Contact type-byte round-trips through decode, cache, and export")
struct ContactTypeBytePreservationTests {

    /// Builds a 147-byte contact structure (no command byte) as `parseContactData` consumes it.
    private func contactBytes(typeByte: UInt8) -> Data {
        var data = Data(repeating: 0, count: 147)
        for i in 0..<32 { data[i] = 0xAB }   // public key
        data[32] = typeByte                  // type
        data[34] = 0xFF                       // path length = flood
        return data
    }

    @Test("parseContactData preserves an unmodeled type byte while falling back to .chat")
    func decodePreservesUnmodeledByte() throws {
        let contact = try #require(Parsers.parseContactData(contactBytes(typeByte: 0x04)))
        #expect(contact.typeRawValue == 0x04)
        #expect(contact.type == .chat)
    }

    @Test("parseContactData keeps modeled type bytes and their enum in sync")
    func decodePreservesModeledBytes() throws {
        for (raw, expected): (UInt8, ContactType) in [(0x01, .chat), (0x02, .repeater), (0x03, .room)] {
            let contact = try #require(Parsers.parseContactData(contactBytes(typeByte: raw)))
            #expect(contact.typeRawValue == raw)
            #expect(contact.type == expected)
        }
    }

    @Test("ContactFrame ↔ MeshContact ↔ Contact/ContactDTO and export all keep 0x04")
    func roundTripPreservesUnmodeledByte() throws {
        let radioID = UUID()
        let frame = ContactFrame(
            publicKey: Data(repeating: 0xAB, count: 32),
            type: .chat, typeRawValue: 0x04, flags: 0,
            outPathLength: 0xFF, outPath: Data(), name: "FutureType",
            lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0
        )

        // Frame → MeshContact → Frame
        let meshContact = frame.toMeshContact()
        #expect(meshContact.typeRawValue == 0x04)
        #expect(meshContact.toContactFrame().typeRawValue == 0x04)

        // Frame → Contact @Model → Frame, and through the DTO
        let contact = Contact(radioID: radioID, from: frame)
        #expect(contact.typeRawValue == 0x04)
        #expect(contact.toContactFrame().typeRawValue == 0x04)
        #expect(ContactDTO(from: contact).toContactFrame().typeRawValue == 0x04)

        // Export re-emits the raw byte
        #expect(NodeConfigService.buildContactConfig(from: meshContact).type == 0x04)
    }

    @Test("Export and decode stay byte-identical for modeled types")
    func exportPreservesModeledBytes() {
        for raw: UInt8 in [0x01, 0x02, 0x03] {
            let meshContact = MeshContact(
                id: "m", publicKey: Data(repeating: 0xAB, count: 32),
                type: ContactType(rawValue: raw)!, typeRawValue: raw,
                flags: [], outPathLength: 0xFF, outPath: Data(), advertisedName: "M",
                lastAdvertisement: Date(timeIntervalSince1970: 0),
                latitude: 0, longitude: 0, lastModified: Date(timeIntervalSince1970: 0)
            )
            #expect(NodeConfigService.buildContactConfig(from: meshContact).type == raw)
        }
    }
}
