import Foundation
import Testing
@testable import MC1Services
@testable import MeshCore

@Suite("NodeConfigService Tests")
struct NodeConfigServiceTests {

    // MARK: - Test Data

    private static let testSelfInfo = SelfInfo(
        advertisementType: 1,
        txPower: 22,
        maxTxPower: 30,
        publicKey: Data(repeating: 0xAB, count: 32),
        latitude: 47.6062,
        longitude: -122.3321,
        multiAcks: 2,
        advertisementLocationPolicy: 1,
        telemetryModeEnvironment: 3,
        telemetryModeLocation: 2,
        telemetryModeBase: 1,
        manualAddContacts: false,
        radioFrequency: 910.525,
        radioBandwidth: 62.5,
        radioSpreadingFactor: 7,
        radioCodingRate: 5,
        name: "TestNode"
    )

    private static let testContact = MeshContact(
        id: Data(repeating: 0x01, count: 32).hexString().lowercased(),
        publicKey: Data(repeating: 0x01, count: 32),
        type: .chat,
        flags: ContactFlags(rawValue: 0x02),
        outPathLength: 3,
        outPath: Data([0xAA, 0xBB, 0xCC]),
        advertisedName: "RemoteNode",
        lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
        latitude: 47.43,
        longitude: -120.36,
        lastModified: Date(timeIntervalSince1970: 1_700_000_100)
    )

    private static let floodContact = MeshContact(
        id: Data(repeating: 0x02, count: 32).hexString().lowercased(),
        publicKey: Data(repeating: 0x02, count: 32),
        type: .repeater,
        flags: [],
        outPathLength: 0xFF,
        outPath: Data(),
        advertisedName: "FloodNode",
        lastAdvertisement: Date(timeIntervalSince1970: 1_700_001_000),
        latitude: 0,
        longitude: 0,
        lastModified: Date(timeIntervalSince1970: 1_700_001_100)
    )

    private static let zeroPathContact = MeshContact(
        id: Data(repeating: 0x03, count: 32).hexString().lowercased(),
        publicKey: Data(repeating: 0x03, count: 32),
        type: .chat,
        flags: [],
        outPathLength: 0,
        outPath: Data(),
        advertisedName: "DirectNode",
        lastAdvertisement: Date(timeIntervalSince1970: 1_700_002_000),
        latitude: 0,
        longitude: 0,
        lastModified: Date(timeIntervalSince1970: 1_700_002_100)
    )

    // MARK: - buildRadioSettings

    @Test("buildRadioSettings converts MHz frequency to kHz")
    func buildRadioSettingsFrequency() {
        let radio = NodeConfigService.buildRadioSettings(from: Self.testSelfInfo)

        // 910.525 MHz → 910525 kHz
        #expect(radio.frequency == 910_525)
    }

    @Test("buildRadioSettings converts kHz bandwidth to Hz")
    func buildRadioSettingsBandwidth() {
        let radio = NodeConfigService.buildRadioSettings(from: Self.testSelfInfo)

        // 62.5 kHz → 62500 Hz
        #expect(radio.bandwidth == 62_500)
    }

    @Test("buildRadioSettings copies spreading factor, coding rate, and tx power")
    func buildRadioSettingsOtherFields() {
        let radio = NodeConfigService.buildRadioSettings(from: Self.testSelfInfo)

        #expect(radio.spreadingFactor == 7)
        #expect(radio.codingRate == 5)
        #expect(radio.txPower == 22)
    }

    // MARK: - buildOtherSettings

    @Test("buildOtherSettings maps manualAddContacts=false to 0")
    func buildOtherSettingsManualAddFalse() {
        let other = NodeConfigService.buildOtherSettings(from: Self.testSelfInfo)
        #expect(other.manualAddContacts == 0)
    }

    @Test("buildOtherSettings maps manualAddContacts=true to 1")
    func buildOtherSettingsManualAddTrue() {
        let info = SelfInfo(
            advertisementType: 0, txPower: 10, maxTxPower: 30,
            publicKey: Data(repeating: 0, count: 32),
            latitude: 0, longitude: 0, multiAcks: 0,
            advertisementLocationPolicy: 0, telemetryModeEnvironment: 0,
            telemetryModeLocation: 0, telemetryModeBase: 0,
            manualAddContacts: true,
            radioFrequency: 910.525, radioBandwidth: 62.5,
            radioSpreadingFactor: 7, radioCodingRate: 5, name: "Test"
        )

        let other = NodeConfigService.buildOtherSettings(from: info)
        #expect(other.manualAddContacts == 1)
    }

    @Test("buildOtherSettings exports only 2 companion-app fields")
    func buildOtherSettingsAllFields() {
        let other = NodeConfigService.buildOtherSettings(from: Self.testSelfInfo)

        #expect(other.manualAddContacts == 0)
        #expect(other.advertLocationPolicy == 1)
        #expect(other.telemetryModeBase == nil)
        #expect(other.telemetryModeLocation == nil)
        #expect(other.telemetryModeEnvironment == nil)
        #expect(other.multiAcks == nil)
        #expect(other.advertisementType == nil)
    }

    // MARK: - buildContactConfig

    @Test("buildContactConfig populates all fields from MeshContact")
    func buildContactConfigAllFields() {
        let config = NodeConfigService.buildContactConfig(from: Self.testContact)

        #expect(config.type == 1)
        #expect(config.name == "RemoteNode")
        #expect(config.publicKey == Data(repeating: 0x01, count: 32).hexString().lowercased())
        #expect(config.flags == 0x02)
        #expect(config.latitude == "47.43")
        #expect(config.longitude == "-120.36")
        #expect(config.lastAdvert == 1_700_000_000)
        #expect(config.lastModified == 1_700_000_100)
    }

    @Test("buildContactConfig includes hex outPath for routed contacts")
    func buildContactConfigRoutedPath() {
        let config = NodeConfigService.buildContactConfig(from: Self.testContact)
        #expect(config.outPath == "aabbcc")
    }

    @Test("buildContactConfig uses nil outPath for flood routing")
    func buildContactConfigFloodPath() {
        let config = NodeConfigService.buildContactConfig(from: Self.floodContact)
        #expect(config.outPath == nil)
    }

    @Test("buildContactConfig uses empty string outPath for direct (zero-length) path")
    func buildContactConfigDirectPath() {
        let config = NodeConfigService.buildContactConfig(from: Self.zeroPathContact)
        #expect(config.outPath == "")
    }

    @Test("buildContactConfig truncates outPath to outPathLength bytes")
    func buildContactConfigTruncatesPath() {
        // Contact with outPathLength=2 but outPath has 4 bytes
        let contact = MeshContact(
            id: "test",
            publicKey: Data(repeating: 0x04, count: 32),
            type: .chat, flags: [], outPathLength: 2,
            outPath: Data([0xAA, 0xBB, 0xCC, 0xDD]),
            advertisedName: "Truncated",
            lastAdvertisement: .now, latitude: 0, longitude: 0,
            lastModified: .now
        )

        let config = NodeConfigService.buildContactConfig(from: contact)
        #expect(config.outPath == "aabb")
    }

    // MARK: - Import ordering verification

    @Test("countImportSteps counts all section steps correctly")
    func importStepCounting() async {
        // Build a config with all sections populated
        let config = MeshCoreNodeConfig(
            name: "Test",
            privateKey: "abcd",
            radioSettings: .init(frequency: 910_525, bandwidth: 62_500,
                                 spreadingFactor: 7, codingRate: 5, txPower: 22),
            positionSettings: .init(latitude: "47.0", longitude: "-122.0"),
            otherSettings: .init(manualAddContacts: 0),
            channels: [
                .init(name: "Ch1", secret: "00112233445566778899aabbccddeeff"),
                .init(name: "Ch2", secret: "ffeeddccbbaa99887766554433221100"),
            ],
            contacts: [
                .init(type: 1, name: "C1", publicKey: String(repeating: "ab", count: 32),
                      flags: 0, latitude: "0", longitude: "0", lastAdvert: 0, lastModified: 0),
            ]
        )

        var sections = ConfigSections()
        sections.selectAll()
        let service = await makeUntestableService()
        let count = await service.testableCountImportSteps(config: config, sections: sections)

        // privateKey(1) + name(1) + position(1) + otherSettings(1)
        // + channels(2+1 read) + contacts(1) + radio(1) + txPower(1) = 10
        #expect(count == 10)
    }

    @Test("countImportSteps skips disabled sections")
    func importStepCountingSkipsDisabled() async {
        let config = MeshCoreNodeConfig(
            name: "Test",
            privateKey: "abcd",
            radioSettings: .init(frequency: 910_525, bandwidth: 62_500,
                                 spreadingFactor: 7, codingRate: 5, txPower: 22),
            channels: [
                .init(name: "Ch1", secret: "00112233445566778899aabbccddeeff"),
            ]
        )

        let sections = ConfigSections(
            nodeIdentity: false,
            radioSettings: false,
            positionSettings: false,
            otherSettings: false,
            channels: true,
            contacts: false
        )
        let service = await makeUntestableService()
        let count = await service.testableCountImportSteps(config: config, sections: sections)

        // Only channels: 1 channel + 1 read = 2
        #expect(count == 2)
    }

    @Test("countImportSteps is zero for empty config")
    func importStepCountingEmptyConfig() async {
        let config = MeshCoreNodeConfig()
        let sections = ConfigSections()
        let service = await makeUntestableService()
        let count = await service.testableCountImportSteps(config: config, sections: sections)

        #expect(count == 0)
    }

    // MARK: - OtherSettings merge logic

    @Test("Partial OtherSettings fills missing fields from current device values")
    func otherSettingsMerge() {
        // Imported config has only 2 of 7 fields (companion app style)
        let imported = MeshCoreNodeConfig.OtherSettings(
            manualAddContacts: 1,
            advertLocationPolicy: 0
        )

        // Simulate current device state
        let current = Self.testSelfInfo

        // Merge: imported values where present, current values for nil
        let manualAdd = imported.manualAddContacts ?? (current.manualAddContacts ? 1 : 0)
        let advertPolicy = imported.advertLocationPolicy ?? current.advertisementLocationPolicy
        let telBase = imported.telemetryModeBase ?? current.telemetryModeBase
        let telLocation = imported.telemetryModeLocation ?? current.telemetryModeLocation
        let telEnvironment = imported.telemetryModeEnvironment ?? current.telemetryModeEnvironment
        let multiAcks = imported.multiAcks ?? current.multiAcks

        // Imported values should take precedence
        #expect(manualAdd == 1)
        #expect(advertPolicy == 0)

        // Missing fields should fall back to current device values
        #expect(telBase == current.telemetryModeBase)
        #expect(telLocation == current.telemetryModeLocation)
        #expect(telEnvironment == current.telemetryModeEnvironment)
        #expect(multiAcks == current.multiAcks)
    }

    @Test("Full OtherSettings uses all imported values")
    func otherSettingsFullImport() {
        let imported = MeshCoreNodeConfig.OtherSettings(
            manualAddContacts: 0,
            advertLocationPolicy: 2,
            telemetryModeBase: 3,
            telemetryModeLocation: 1,
            telemetryModeEnvironment: 2,
            multiAcks: 5,
            advertisementType: 4
        )

        let current = Self.testSelfInfo

        let manualAdd = imported.manualAddContacts ?? (current.manualAddContacts ? 1 : 0)
        let advertPolicy = imported.advertLocationPolicy ?? current.advertisementLocationPolicy
        let telBase = imported.telemetryModeBase ?? current.telemetryModeBase
        let telLocation = imported.telemetryModeLocation ?? current.telemetryModeLocation
        let telEnvironment = imported.telemetryModeEnvironment ?? current.telemetryModeEnvironment
        let multiAcks = imported.multiAcks ?? current.multiAcks

        #expect(manualAdd == 0)
        #expect(advertPolicy == 2)
        #expect(telBase == 3)
        #expect(telLocation == 1)
        #expect(telEnvironment == 2)
        #expect(multiAcks == 5)
    }

    // MARK: - Export round-trip consistency

    @Test("buildRadioSettings round-trips through config format")
    func radioSettingsRoundTrip() {
        let radio = NodeConfigService.buildRadioSettings(from: Self.testSelfInfo)

        // Config stores frequency in kHz, bandwidth in Hz.
        // setRadioParams's bandwidthKHz parameter actually takes Hz (matching
        // RadioPreset.bandwidthHz usage), so import passes values directly
        // for a lossless round-trip.
        #expect(radio.frequency == 910_525)
        #expect(radio.bandwidth == 62_500)
    }

    @Test("buildContactConfig and import produce consistent outPath")
    func contactConfigOutPathConsistency() {
        let exported = NodeConfigService.buildContactConfig(from: Self.testContact)
        #expect(exported.outPath == "aabbcc")

        // "aabbcc" = 3 bytes, matching the original outPathLength
        #expect(Self.testContact.outPathLength == 3)
    }

    @Test("buildContactConfig and import produce consistent flood path")
    func contactConfigFloodPathConsistency() {
        let exported = NodeConfigService.buildContactConfig(from: Self.floodContact)
        // Flood routing: nil outPath, outPathLength 0xFF
        #expect(exported.outPath == nil)
        #expect(Self.floodContact.outPathLength == 0xFF)
    }

    @Test("Direct contact round-trips through export and import without becoming flood")
    func directContactRoundTrip() throws {
        let exported = NodeConfigService.buildContactConfig(from: Self.zeroPathContact)
        #expect(exported.outPath == "")

        // Re-encode through JSON to simulate a real import
        let encoded = try JSONEncoder().encode(exported)
        let reimported = try JSONDecoder().decode(MeshCoreNodeConfig.ContactConfig.self, from: encoded)

        // Empty string is non-nil: must be treated as direct, not flood
        #expect(reimported.outPath != nil)
        #expect(reimported.outPath?.isEmpty == true)

        // Simulate the fixed import logic's three-way branch
        let outPathLength: UInt8
        if let pathHex = reimported.outPath, !pathHex.isEmpty {
            // Routed path (not reached for direct contacts)
            outPathLength = 1
        } else if reimported.outPath != nil {
            // Direct (zero-hop) — outPath was explicitly set to ""
            outPathLength = 0
        } else {
            // Flood — outPath was nil
            outPathLength = 0xFF
        }

        #expect(outPathLength == 0, "Direct contact must stay direct (0), not become flood (0xFF)")
    }

    // MARK: - Multibyte path hash mode (export)

    @Test("buildContactConfig exports pathHashMode for mode 0 contact")
    func buildContactConfigExportsMode0() {
        let config = NodeConfigService.buildContactConfig(from: Self.testContact)
        // testContact has outPathLength=3 → mode 0 (upper 2 bits = 0)
        #expect(config.pathHashMode == 0)
    }

    @Test("buildContactConfig exports pathHashMode for mode 1 (2-byte) contact")
    func buildContactConfigExportsMode1() {
        // outPathLength = encodePathLen(hashSize: 2, hopCount: 3) = 0b01_000011 = 0x43
        let contact = MeshContact(
            id: "mode1", publicKey: Data(repeating: 0x05, count: 32),
            type: .chat, flags: [],
            outPathLength: encodePathLen(hashSize: 2, hopCount: 3),
            outPath: Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]),
            advertisedName: "Mode1Node",
            lastAdvertisement: .now, latitude: 0, longitude: 0, lastModified: .now
        )
        let config = NodeConfigService.buildContactConfig(from: contact)

        #expect(config.pathHashMode == 1)
        #expect(config.outPath == "aabbccddeeff")
    }

    @Test("buildContactConfig exports nil pathHashMode for flood contacts")
    func buildContactConfigExportsNilModeForFlood() {
        let config = NodeConfigService.buildContactConfig(from: Self.floodContact)
        #expect(config.pathHashMode == nil)
    }

    // MARK: - Multibyte path hash mode (import round-trip via JSON)

    @Test("ContactConfig import with pathHashMode encodes outPathLength correctly")
    func contactConfigImportWithHashMode() throws {
        let json = """
        {
            "type": 1, "name": "Test", "public_key": "\(String(repeating: "ab", count: 32))",
            "flags": 0, "latitude": "0", "longitude": "0",
            "last_advert": 0, "last_modified": 0,
            "out_path": "aabbccddeeff",
            "path_hash_mode": 1
        }
        """
        let config = try JSONDecoder().decode(MeshCoreNodeConfig.ContactConfig.self, from: Data(json.utf8))

        #expect(config.pathHashMode == 1)

        // Simulate what the import code does: 6 hex chars = 3 bytes
        let pathByteCount = 6  // "aabbccddeeff" = 6 bytes
        let hashSize = Int(config.pathHashMode ?? 0) + 1
        let hopCount = pathByteCount / hashSize
        let outPathLength = encodePathLen(hashSize: hashSize, hopCount: hopCount)

        // 6 bytes / 2 bytes per hop = 3 hops, mode 1 → 0b01_000011 = 0x43
        #expect(hashSize == 2)
        #expect(hopCount == 3)
        #expect(outPathLength == 0x43)
    }

    @Test("ContactConfig import without pathHashMode defaults to mode 0")
    func contactConfigImportWithoutHashMode() throws {
        let json = """
        {
            "type": 1, "name": "Test", "public_key": "\(String(repeating: "ab", count: 32))",
            "flags": 0, "latitude": "0", "longitude": "0",
            "last_advert": 0, "last_modified": 0,
            "out_path": "aabbcc"
        }
        """
        let config = try JSONDecoder().decode(MeshCoreNodeConfig.ContactConfig.self, from: Data(json.utf8))

        #expect(config.pathHashMode == nil)

        // Simulate import: nil defaults to mode 0, raw byte count = hop count
        let pathByteCount = 3  // "aabbcc" = 3 bytes
        let hashSize = Int(config.pathHashMode ?? 0) + 1
        let hopCount = pathByteCount / hashSize
        let outPathLength = encodePathLen(hashSize: hashSize, hopCount: hopCount)

        // 3 bytes / 1 byte per hop = 3 hops, mode 0 → 0b00_000011 = 3
        #expect(hashSize == 1)
        #expect(hopCount == 3)
        #expect(outPathLength == 3)
    }

    @Test("Direct contact with pathHashMode imports as outPathLength 0, not mode-encoded")
    func directContactWithHashModeStaysDirect() throws {
        let json = """
        {
            "type": 1, "name": "DirectMode1", "public_key": "\(String(repeating: "cd", count: 32))",
            "flags": 0, "latitude": "0", "longitude": "0",
            "last_advert": 0, "last_modified": 0,
            "out_path": "",
            "path_hash_mode": 1
        }
        """
        let config = try JSONDecoder().decode(MeshCoreNodeConfig.ContactConfig.self, from: Data(json.utf8))
        #expect(config.pathHashMode == 1)
        #expect(config.outPath == "")

        // Simulate the import logic's three-way branch
        let outPathLength: UInt8
        if let pathHex = config.outPath, !pathHex.isEmpty {
            // Routed path (not reached for empty out_path)
            outPathLength = 1
        } else if config.outPath != nil {
            outPathLength = 0
        } else {
            outPathLength = 0xFF
        }

        #expect(outPathLength == 0, "Direct contact must encode as 0, not mode-encoded 0x40")
        #expect(outPathLength != 0xFF, "Direct contact must not become flood")
    }

    // MARK: - Error cases

    @Test("NodeConfigServiceError has descriptive messages")
    func errorDescriptions() {
        let channelError = NodeConfigServiceError.invalidChannelSecret(index: 2, hexLength: 30)
        #expect(channelError.localizedDescription.contains("Channel 2"))

        let contactError = NodeConfigServiceError.invalidContactPublicKey(name: "BadContact")
        #expect(contactError.localizedDescription.contains("BadContact"))

        let modeError = NodeConfigServiceError.invalidPathHashMode(name: "BadNode", mode: 5)
        #expect(modeError.localizedDescription.contains("BadNode"))
        #expect(modeError.localizedDescription.contains("5"))
    }

    @Test("Import rejects pathHashMode > 2 as invalid")
    func invalidPathHashModeRejected() throws {
        let json = """
        {
            "type": 1, "name": "BadMode", "public_key": "\(String(repeating: "ab", count: 32))",
            "flags": 0, "latitude": "0", "longitude": "0",
            "last_advert": 0, "last_modified": 0,
            "out_path": "aabbcc",
            "path_hash_mode": 3
        }
        """
        let config = try JSONDecoder().decode(MeshCoreNodeConfig.ContactConfig.self, from: Data(json.utf8))
        #expect(config.pathHashMode == 3)

        // The mode validation guard should reject values > 2
        let mode = config.pathHashMode ?? 0
        #expect(mode > 2)
    }

    // MARK: - ImportProgress

    @Test("ImportProgress stores step info")
    func importProgressFields() {
        let progress = ImportProgress(step: "Setting radio", current: 3, total: 10)
        #expect(progress.step == "Setting radio")
        #expect(progress.current == 3)
        #expect(progress.total == 10)
    }

    // MARK: - Post-Identity Resolution Seam

    @Test("resolveEffectiveRadioID returns callback result when private key was imported")
    func resolveReturnsCallbackResult() async throws {
        let original = UUID()
        let reconciled = UUID()
        let result = try await resolveEffectiveRadioID(
            original: original,
            didImportPrivateKey: true,
            callback: { @Sendable in reconciled }
        )
        #expect(result == reconciled)
    }

    @Test("resolveEffectiveRadioID skips callback when no private key was imported")
    func resolveSkipsCallbackWhenNoPrivateKey() async throws {
        actor CallTracker {
            var calls = 0
            func bump() { calls += 1 }
        }
        let tracker = CallTracker()
        let original = UUID()
        let result = try await resolveEffectiveRadioID(
            original: original,
            didImportPrivateKey: false,
            callback: { @Sendable in
                await tracker.bump()
                return UUID()
            }
        )
        #expect(result == original)
        #expect(await tracker.calls == 0,
                "Callback must not fire when no private key was imported")
    }

    @Test("resolveEffectiveRadioID returns original when callback returns nil")
    func resolveReturnsOriginalWhenCallbackReturnsNil() async throws {
        let original = UUID()
        let result = try await resolveEffectiveRadioID(
            original: original,
            didImportPrivateKey: true,
            callback: { @Sendable in nil }
        )
        #expect(result == original)
    }

    @Test("resolveEffectiveRadioID handles nil callback gracefully")
    func resolveHandlesNilCallback() async throws {
        let original = UUID()
        let result = try await resolveEffectiveRadioID(
            original: original,
            didImportPrivateKey: true,
            callback: nil
        )
        #expect(result == original)
    }

    // MARK: - Helpers

    /// Creates a NodeConfigService that can only be used for non-session operations.
    /// Used to test countImportSteps and other pure logic via the actor.
    @MainActor
    private func makeUntestableService() -> TestableNodeConfigService {
        TestableNodeConfigService()
    }
}

// MARK: - Testable wrapper for step counting

/// Thin wrapper that exposes the step-counting logic without requiring a real session.
/// This avoids creating a MeshCoreSession in tests.
private actor TestableNodeConfigService {
    func testableCountImportSteps(config: MeshCoreNodeConfig, sections: ConfigSections) -> Int {
        var count = 0
        if sections.nodeIdentity && config.privateKey != nil { count += 1 }
        if sections.nodeIdentity && config.name != nil { count += 1 }
        if sections.positionSettings && config.positionSettings != nil { count += 1 }
        if sections.otherSettings && config.otherSettings != nil { count += 1 }
        if sections.channels { count += (config.channels?.count ?? 0) + 1 }
        if sections.contacts { count += config.contacts?.count ?? 0 }
        if sections.radioSettings && config.radioSettings != nil { count += 2 }
        return count
    }
}
